(ns isaac.http.component.http-spec
  (:require
    [isaac.component.factory :as component]
    [isaac.component.protocol :as protocol]
    [isaac.config.loader :as loader]
    [isaac.http.component.http]
    [isaac.logger :as log]
    [org.httpkit.server :as httpkit]
    [speclj.core :refer :all]))

(describe "HTTP component"

  (it "owns the HTTP listener lifecycle and exposes the bound port"
    (let [started (atom nil)
          stopped (atom nil)]
      (with-redefs [httpkit/run-server  (fn [_handler opts]
                                          (reset! started opts)
                                          ::server)
                    httpkit/server-port (constantly 7123)
                    httpkit/server-stop! #(reset! stopped %)]
        (let [instance (component/create :http {:config {:server {:auth {:token "test"}}}
                                                :opts {:host "127.0.0.1" :port 0}})]
          (protocol/run-start! instance)
          (should= {:port 0 :ip "127.0.0.1" :legacy-return-value? false} @started)
          (should= 7123 (protocol/bound-port instance))
          (protocol/run-stop! instance)
          (should= ::server @stopped)))))

  (it "reads the current auth token without restarting the listener"
    (let [handler* (atom nil)
          config*  (atom {:server {:auth {:token "marigold"}}})]
      (with-redefs [httpkit/run-server (fn [handler _opts]
                                         (reset! handler* handler)
                                         ::server)
                    loader/snapshot   (fn [_reason] @config*)]
        (log/capture-logs
          (let [instance (component/create :http {:config @config*
                                                  :opts {:host "127.0.0.1" :port 0}})
                request  (fn [token]
                           (@handler* {:request-method :get
                                       :uri "/status"
                                       :headers {"authorization" (str "Bearer " token)}}))]
            (protocol/run-start! instance)
            (should= 200 (:status (request "marigold")))
            (reset! config* {:server {:auth {:token "skybeam"}}})
            (should= 401 (:status (request "marigold")))
            (should= 200 (:status (request "skybeam"))))))))

  (it "does not bind when the runner disables HTTP"
    (let [started (atom false)]
      (with-redefs [httpkit/run-server (fn [& _] (reset! started true))]
        (let [instance (component/create :http {:config {}
                                                :opts {:start-http-server? false}})]
          (protocol/run-start! instance)
          (should-not @started)
          (should= nil (protocol/bound-port instance))))))
  )
