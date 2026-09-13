(ns isaac.http.http
  (:require
    [clojure.string :as str]
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

(defn wrap-auth [opts handler]
  (fn [request]
    (let [cfg   (request-cfg opts)
          token (get-in cfg [:server :auth :token])]
      ;; If a token is configured, enforce it on every request regardless
      ;; of bind host. Forwarding layers (Tailscale, ngrok, ssh -L,
      ;; reverse proxies) can map remote traffic onto loopback, so the
      ;; old "loopback bind ignores token" rule was unsafe. The startup
      ;; gate (in app.clj) still refuses to bind non-loopback without a
      ;; token, so a token-less server is only reachable from real
      ;; loopback peers.
      (if (or (str/blank? token)
              (= token (bearer-token request)))
        (handler request)
        (let [response {:status  401
                        :headers {"Content-Type"        "text/plain"
                                  "WWW-Authenticate"    "Bearer"}
                        :body    "Unauthorized"}]
          (when-let [burst-cfg (get-in cfg [:server :burst])]
            (burst/record-unauthenticated! burst-cfg cfg (client-address request) (:uri request)))
          response)))))

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
        (let [response (handler request)
              ms       (- (System/currentTimeMillis) start)]
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
      (wrap-burst opts-or-handler
        (wrap-logging
          (wrap-auth opts-or-handler
                     (fn [request]
                       (routes/handler opts-or-handler request))))))))
