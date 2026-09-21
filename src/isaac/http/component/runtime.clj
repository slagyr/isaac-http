(ns isaac.http.component.runtime
  (:require
    [clojure.string :as str]
    [isaac.component.factory :as component-factory]
    [isaac.component.protocol :as component]
    [isaac.component.registry :as component-registry]
    [isaac.config.loader :as loader]
    [isaac.config.runtime :as runtime]
    [isaac.fs :as fs]
    [isaac.http.http :as http]
    [isaac.logger :as log]))

(def ^:private optional-registry-syms
  '[isaac.hail.bands/registry
    isaac.hooks/registry
    isaac.cron.service/registry])

(defn- resolve-var [sym]
  (try (requiring-resolve sym) (catch Throwable _ nil)))

(defn- resolve-registry [sym]
  (when-let [v (resolve-var sym)]
    (if (var? v) @v v)))

(defn -registries []
  (vec (keep resolve-registry optional-registry-syms)))

(defn- host-context [config root opts]
  {:connect-ws! (:connect-ws! opts)
   :module-index (:module-index config)
   :root root})

(deftype ServerRuntime [config root opts contribution-module-index running*]
  component/Component
  (start [this]
    (let [module-index  (or (:module-index config) contribution-module-index)
          config*       (assoc config :module-index module-index)
          registries    (-registries)
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

(defn valid-start? [config opts]
  (let [host          (or (:host opts) (get-in config [:http :host]) "127.0.0.1")
        start-http?   (not (false? (:start-http-server? opts)))
        auth-token    (get-in config [:http :auth :token])
        dropped?      (http-auth-dropped? (:config-warnings opts))]
    (cond
      dropped?
      (do (log/error :auth/config-dropped
                     :host host
                     :message "refusing to start: :http :auth was dropped as an unknown key")
          false)

      (not start-http?)
      true

      (or (http/loopback-host? host) (seq auth-token))
      true

      :else
      (do (log/error :server/auth-required
                     :host host
                     :message "missing :http :auth :token for non-loopback bind")
          false))))

(defmethod component-factory/create :server-runtime
  [_ {:keys [config module-index opts root]}]
  (->ServerRuntime config root opts module-index (atom nil)))
