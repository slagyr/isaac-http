(ns isaac.http.component.http
  (:require
    [c3kit.apron.refresh :as refresh]
    [isaac.component.factory :as component]
    [isaac.component.protocol :as protocol]
    [isaac.config.loader :as loader]
    [isaac.logger :as log]
    [isaac.http.http :as http]
    [org.httpkit.server :as httpkit]))

(defn- dev-handler [handler-opts]
  (refresh/init refresh/services "isaac" [])
  (let [refreshing (refresh/refresh-handler 'isaac.http.http/root-handler)]
    (http/wrap-logging
      (http/wrap-auth handler-opts
                      (fn [request]
                        (log/debug :server/dev-reload-scan
                                   :method (:request-method request)
                                   :uri (:uri request))
                        (refreshing request))))))

(deftype HttpComponent [ctx server*]
  protocol/Component
  (start [_]
    (let [{:keys [config opts root]} ctx]
      (when-not (false? (:start-http-server? opts))
        (let [server-cfg   (:server config)
              host         (or (:host opts) (:host server-cfg) "127.0.0.1")
              port         (or (:port opts) (:port server-cfg) 6674)
              handler-opts (assoc (dissoc opts :home)
                                  :root root
                                  :cfg-fn #(or (loader/snapshot "http request config") config))
              handler      (if (:dev opts)
                             (dev-handler handler-opts)
                             (http/create-handler handler-opts))
              server       (httpkit/run-server handler {:port port :ip host :legacy-return-value? false})]
          (reset! server* server)))))
  (stop [_]
    (when-let [server @server*]
      (if (fn? server)
        (server)
        (httpkit/server-stop! server)))
    (reset! server* nil))
  protocol/BoundPort
  (bound-port [_]
    (some-> @server* httpkit/server-port)))

(defmethod component/create :http [_ ctx]
  (->HttpComponent ctx (atom nil)))
