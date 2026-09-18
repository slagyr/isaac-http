(ns isaac.http.http
  (:require
    [clojure.string :as str]
    [isaac.http.auth :as auth]
    [isaac.logger :as log]
    [isaac.http.burst :as burst]
    [isaac.http.routes :as routes]))

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

(defn- verified-identity [request]
  (some #(% request) (auth/identity-verifiers)))

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
            auth-cfg   (get-in cfg [:server :auth])
            principals (auth/principals cfg)
            bearer     (bearer-token request)
            principal  (or (auth/authenticate cfg bearer) (verified-identity request))
            scope      (routes/required-scope request)
            auth-on?   (or (contains? auth-cfg :principals)
                           (seq principals)
                           (seq (auth/identity-verifiers)))
            reason     (when auth-on?
                         (cond
                           (and principal (auth/expired? (:expires principal))) :expired
                           (nil? principal) :unknown
                           (not (auth/authorized? principal scope)) :scope))
            status     (case reason :scope 403 (:unknown :expired) 401 nil)]
        (when (and (some :legacy? (vals principals)) (not= auth-cfg @warned-auth*))
          (reset! warned-auth* auth-cfg)
          (log/warn :auth/legacy-token))
        (if-not reason
          (let [identity (some-> principal (select-keys [:name :scopes]))
                response (invoke-handler handler (cond-> request identity (assoc :isaac/principal identity)))]
            (cond-> response identity (assoc :isaac/principal identity)))
          (do
            (log/warn :auth/refused :principal (:name principal) :reason reason)
            (when-let [burst-cfg (get-in cfg [:server :burst])]
              (burst/record-unauthenticated! burst-cfg cfg (client-address request) (:uri request)))
            (refused-response status)))))))

(defn wrap-burst
  "Optional unauthenticated burst control. Absent :server :burst = off.
   Throttle (when on) answers a flagged client with 429 before auth."
  [opts handler]
  (fn [request]
    (let [cfg       (request-cfg opts)
          burst-cfg (get-in cfg [:server :burst])]
      (if-not burst-cfg
        (handler request)
        (do
          (burst/sweep-ended! burst-cfg cfg)
          (let [client (client-address request)]
            (if (burst/throttle-client? burst-cfg client)
              (burst/throttled-response! burst-cfg client)
              (handler request))))))))

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
