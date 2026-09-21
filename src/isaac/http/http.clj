(ns isaac.http.http
  (:require
    [clojure.string :as str]
    [isaac.http.audit :as audit]
    [isaac.http.auth :as auth]
    [isaac.http.oidc :as oidc]
    [isaac.logger :as log]
    [isaac.http.burst :as burst]
    [isaac.http.routes :as routes]
    [isaac.nexus :as nexus]))

(defn loopback-host? [host]
  (boolean
    (when host
      (or (= "localhost" host)
          (= "::1" host)
          (= "0:0:0:0:0:0:0:1" host)
          (str/starts-with? host "127.")))))

(defn- bearer-token [request]
  (some-> (or (get-in request [:headers "authorization"])
              (get-in request [:headers :authorization]))
          (str/replace-first #"(?i)^Bearer\s+" "")))

(defn- request-cfg [opts]
  (or (when-let [cfg-fn (:cfg-fn opts)] (cfg-fn))
      (:cfg opts)
      {}))

(defn client-address
  "The originating client: the first hop of X-Forwarded-For when a proxy
   (Tailscale Funnel, ngrok) fronts the server, else the socket peer. Logged
   on every request so an unauthenticated scan is attributable."
  [request]
  (let [forwarded (some-> (get-in request [:headers "x-forwarded-for"])
                          (str/split #",")
                          first
                          str/trim
                          not-empty)]
    (or forwarded (:remote-addr request))))

(defn- refused-response [status]
  {:status status
   :headers (cond-> {"Content-Type" "text/plain"}
              (= 401 status) (assoc "WWW-Authenticate" "Bearer"))
   :body (if (= 401 status) "Unauthorized" "Forbidden")})

(defn- code-verifiers []
  (filter fn? (auth/identity-verifiers)))

(defn- oidc-rules [cfg]
  (auth/identity-rules cfg))

(defn- identity-declared? [cfg]
  (or (seq (code-verifiers)) (seq (oidc-rules cfg))))

(defn- oidc-identity [cfg bearer]
  (when (and (seq bearer) (oidc/jwt-shaped? bearer) (seq (oidc-rules cfg)))
    (let [opts     {:http (:http cfg) :cfg cfg}
          hits     (keep (fn [rule] (oidc/verify bearer rule opts)) (oidc-rules cfg))
          accepted (first (remove :reason hits))
          refused  (remove #(= :issuer (:reason %)) (filter :reason hits))]
      (or accepted (first refused)))))

(defn- verified-identity [request]
  (some #(% request) (code-verifiers)))

(defn- invoke-handler [handler request]
  (try
    (handler request)
    (catch clojure.lang.ExceptionInfo e
      (if (= 403 (:status (ex-data e)))
        (refused-response 403)
        (throw e)))))

(defn wrap-auth [opts handler]
  (let [warned-auth* (atom ::none)]
    (fn [request]
      (let [cfg        (request-cfg opts)
            auth-cfg   (get-in cfg [:http :auth])
            principals (auth/principals cfg)
            bearer     (bearer-token request)
            oidc-hit   (oidc-identity cfg bearer)
            principal  (or (when (and oidc-hit (not (:reason oidc-hit))) oidc-hit)
                           (auth/authenticate cfg bearer)
                           (verified-identity request))
            scope      (routes/required-scope request)
            ;; A presented bearer is always adjudicated: a server with no auth
            ;; configured still refuses a credential it cannot place rather
            ;; than serving the request as anonymous.
            auth-on?   (or (contains? auth-cfg :principals)
                           (seq principals)
                           (identity-declared? cfg)
                           (seq bearer))
            remembered (when (and (nil? principal) (seq bearer))
                        (audit/remembered-name (auth/sha256 bearer)))
            reason     (when auth-on?
                         (cond
                           (and oidc-hit (:reason oidc-hit) (nil? principal)) (:reason oidc-hit)
                           (and principal (auth/expired? (:expires principal))) :expired
                           (and (nil? principal) remembered) :revoked
                           (nil? principal) :unknown
                           (not (auth/authorized? principal scope)) :scope))
            status     (when reason (if (= :scope reason) 403 401))]
        (when (and (some :legacy? (vals principals)) (not= auth-cfg @warned-auth*))
          (reset! warned-auth* auth-cfg)
          (log/warn :auth/legacy-token))
        (if-not reason
          (do
            (audit/remember-principal! principal)
            (audit/record-use! cfg (or (nexus/get :root) (:root cfg) (:root opts)) principal)
            (let [identity (some-> principal (select-keys [:name :scopes]))
                  response (invoke-handler handler (cond-> request identity (assoc :isaac/principal identity)))]
              (cond-> response identity (assoc :isaac/principal identity))))
          (do
            (log/warn :auth/refused :principal (or (:name principal) remembered) :reason reason :uri (:uri request))
            (audit/note-refusal! cfg principal reason remembered)
            (refused-response status)))))))

(defn wrap-burst
  "Unauthenticated burst control. On by default; :http :burst :enabled false turns it off.
   Counts 401/403 responses from any source. Throttle answers a flagged client with 429 before auth."
  [opts handler]
  (fn [request]
    (let [cfg       (request-cfg opts)
          burst-cfg (burst/resolved (get-in cfg [:http :burst]))]
      (if-not burst-cfg
        (handler request)
        (do
          (burst/sweep-ended! burst-cfg cfg)
          (let [client (client-address request)]
            (if (burst/throttle-client? burst-cfg client)
              (do
                (burst/record-throttled! burst-cfg client (:uri request))
                (burst/throttled-response! burst-cfg client))
              (let [response (handler request)]
                (when (contains? #{401 403} (:status response))
                  (burst/record-unauthenticated! burst-cfg cfg client (:uri request)))
                response))))))))

(defn wrap-logging [handler]
  (fn [request]
    (let [method (:request-method request)
          uri    (:uri request)
          client (client-address request)
          start  (System/currentTimeMillis)]
      (log/debug :server/request-received :method method :uri uri :client client)
      (try
        (let [response  (handler request)
              ms        (- (System/currentTimeMillis) start)
              principal (or (get-in response [:isaac/principal :name])
                            (get-in request [:isaac/principal :name]))]
          (log/info :http/request :method method :uri uri :status (:status response) :ms ms :client client
                    :principal principal)
          (log/debug :server/response-sent :method method :uri uri :status (:status response) :ms ms :client client)
          response)
        (catch Exception e
          (let [ms (- (System/currentTimeMillis) start)]
           (log/ex :server/request-failed e {:method method
                                              :uri    uri
                                              :client client
                                              :status 500
                                              :ms     ms}))
          {:status 500 :headers {"Content-Type" "text/plain"} :body "Internal Server Error"}))))) 

(defn root-handler [request]
  (routes/handler request))

(defn create-handler
  ([]
   (create-handler {}))
  ([opts-or-handler]
   (if (fn? opts-or-handler)
     (wrap-logging opts-or-handler)
     (let [handler (or (:handler opts-or-handler)
                       (fn [request] (routes/handler opts-or-handler request)))]
       (wrap-burst opts-or-handler
         (wrap-logging
           (wrap-auth opts-or-handler handler)))))))
