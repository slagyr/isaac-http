(ns isaac.http.component.runtime-spec
  (:require
    [isaac.foundation.component.factory :as component-factory]
    [isaac.foundation.component.protocol :as component]
    [isaac.foundation.component.registry :as component-registry]
    [isaac.foundation.config.runtime :as runtime]
    [isaac.foundation.logger :as log]
    [isaac.foundation.runner :as runner]
    [isaac.foundation.spec-helper :as helper]
    [isaac.http.component.runtime :as sut]
    [speclj.core :refer :all]))

(describe "server runtime component"

  (it "does not run a server-owned comm validation pass"
    (should (sut/valid-start? {} {:start-http-server? false})))

  (helper/with-captured-logs)

  (it "refuses to start when :http :auth was dropped as an unknown key"
    (should-not (sut/valid-start? {:http {:host "0.0.0.0"}}
                                  {:start-http-server? false
                                   :config-warnings [{:key "http.auth.token" :value "unknown key"}]}))
    (should (seq (filter #(= :auth/config-dropped (:event %)) @log/captured-logs))))

  (it "starts and warns when a non-loopback bind has no auth configured at all"
    (should (sut/valid-start? {:http {:host "0.0.0.0"}} {}))
    (let [entry (first (filter #(= :server/auth-absent (:event %)) @log/captured-logs))]
      (should-not-be-nil entry)
      (should= "0.0.0.0" (:host entry))
      (should (re-find #"(?i)no auth configured.*open" (:message entry)))))

  (it "starts without the warning when a token is configured on a non-loopback bind"
    (should (sut/valid-start? {:http {:host "0.0.0.0" :auth {:token "s3cr3t"}}} {}))
    (should (empty? (filter #(= :server/auth-absent (:event %)) @log/captured-logs))))

  (it "starts without the warning when principals are configured on a non-loopback bind"
    (should (sut/valid-start? {:http {:host "0.0.0.0" :auth {:principals {:ci {:hash "x" :scopes #{:*}}}}}} {}))
    (should (empty? (filter #(= :server/auth-absent (:event %)) @log/captured-logs))))

  (it "starts without the warning when identity trust rules are configured on a non-loopback bind"
    (should (sut/valid-start? {:http {:host "0.0.0.0" :auth {:identity {:some-issuer {:issuer "https://example.com"}}}}} {}))
    (should (empty? (filter #(= :server/auth-absent (:event %)) @log/captured-logs))))

  (it "does not warn on a loopback bind with no auth configured"
    (should (sut/valid-start? {:http {:host "127.0.0.1"}} {}))
    (should (empty? (filter #(= :server/auth-absent (:event %)) @log/captured-logs))))

  (it "starts through the same runner used by the server command"
    (let [manifest     (read-string (slurp "resources/isaac-manifest.edn"))
          module-index {:isaac.http {:manifest manifest}}
          installed    (atom nil)]
      (with-redefs [sut/-registries               (constantly [])
                    runtime/install!               #(reset! installed %)
                    runtime/install-config-berths! (constantly nil)]
        (try
          (runner/start! {:config       {:module-index module-index}
                          :module-index module-index})
          (should-not-be-nil (component-registry/instance-for :server-runtime))
          (should= module-index (get-in @installed [:config :module-index]))
          (finally
            (runner/stop!))))))

  (it "installs runtime config, and reconciles it away again on stop"
    ;; Watching config for changes moved to foundation's runner (isaac-1pi2);
    ;; this component's job is the install/reconcile pair around its lifetime.
    (let [calls    (atom [])
          instance (component-factory/create
                     :server-runtime
                     {:config {:http {}}
                      :root   "/isaac"
                      :opts   {}
                      :module-index {:isaac.http {}}})]
      (with-redefs [sut/-registries               (constantly [::registry])
                    runtime/install!               #(swap! calls conj [:install %])
                    runtime/install-config-berths! #(swap! calls conj [:berths %])
                    runtime/reconcile!             #(swap! calls conj [:reconcile %1 %2 %3 %4])]
        (component/start instance)
        (component/stop instance))
      (should= [:install :berths :reconcile :berths] (mapv first @calls))))

  )
