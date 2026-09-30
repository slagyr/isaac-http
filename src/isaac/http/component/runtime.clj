(ns isaac.http.component.runtime
  (:require
    [clojure.string :as str]
    [isaac.foundation.component.factory :as component-factory]
    [isaac.foundation.component.protocol :as component]
    [isaac.foundation.component.registry :as component-registry]
    [isaac.foundation.config.configurator :as configurator]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.config.runtime :as runtime]
    [isaac.foundation.fs :as fs]
    [isaac.http.http :as http]
    [isaac.foundation.logger :as log]))

(defn -registries
  "What to reconcile at boot: whatever modules declared through
   :isaac.config/component. http names no module (isaac-bbe0)."
  ([] (-registries {}))
  ([module-index] (configurator/declared-registries module-index)))

(defn- host-context [config root opts]
  {:connect-ws! (:connect-ws! opts)
   :module-index (:module-index config)
   :root root})

(deftype ServerRuntime [config root opts contribution-module-index running*]
  component/Component
  (start [this]
    (let [module-index  (or (:module-index config) contribution-module-index)
          config*       (assoc config :module-index module-index)
          registries    (-registries module-index)
          host          (host-context config* root opts)]
      (runtime/install! {:config config* :registries registries :host host})
      (runtime/install-config-berths! {:config config* :module-index module-index})
      (reset! running* {:host       host
                        :registries registries})
      this))
  (stop [this]
    (when-let [{:keys [host registries]} @running*]
      (let [module-index (:module-index host)]
        (when (seq registries)
          (runtime/reconcile! host config nil registries))
        (runtime/install-config-berths! {:config       nil
                                         :old-config   config
                                         :module-index module-index})))
    (reset! running* nil)
    this))

(defn running-state []
  (some-> (component-registry/instance-for :server-runtime)
          .-running*
          deref))

(defn- http-auth-dropped? [warnings]
  (some (fn [{:keys [key value]}]
          (and (string? key)
               (str/starts-with? key "http.auth")
               (= "unknown key" value)))
        warnings))

(defn- auth-configured? [config]
  (let [auth (get-in config [:http :auth])]
    (or (seq (:token auth))
        (seq (:principals auth))
        (seq (:identity auth)))))

(defn valid-start? [config opts]
  (let [host          (or (:host opts) (get-in config [:http :host]) "127.0.0.1")
        start-http?   (not (false? (:start-http-server? opts)))
        dropped?      (http-auth-dropped? (:config-warnings opts))]
    (cond
      dropped?
      (do (log/error :auth/config-dropped
                     :host host
                     :message "refusing to start: :http :auth was dropped as an unknown key")
          false)

      (not start-http?)
      true

      (or (http/loopback-host? host) (auth-configured? config))
      true

      :else
      (do (log/warn :server/auth-absent
                    :host host
                    :message "no auth configured for a non-loopback bind — the server is open to any request")
          true))))

(defmethod component-factory/create :server-runtime
  [_ {:keys [config module-index opts root]}]
  (->ServerRuntime config root opts module-index (atom nil)))
