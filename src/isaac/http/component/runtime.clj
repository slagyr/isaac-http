(ns isaac.http.component.runtime
  (:require
    [isaac.component.factory :as component-factory]
    [isaac.component.protocol :as component]
    [isaac.component.registry :as component-registry]
    [isaac.config.loader :as loader]
    [isaac.config.runtime :as runtime]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.http.http :as http]))

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

(defn -start-config-source [config root opts]
  (or (:config-change-source opts)
      (when (and root (get-in config [:server :hot-reload]))
        (runtime/watch-service-source root))))

(defn -start-reloader! [source root host registries]
  (future
    (loop []
      (when-let [path (runtime/poll! source 5000)]
        (runtime/reload! {:root          root
                          :fs            (fs/instance)
                          :old-config    (loader/snapshot "reload: previous config for the reconcile diff")
                          :registries    registries
                          :host          host
                          :path          path}))
      (recur))))

(deftype ServerRuntime [config root opts contribution-module-index running*]
  component/Component
  (start [this]
    (let [module-index  (or (:module-index config) contribution-module-index)
          config*       (assoc config :module-index module-index)
          registries    (-registries)
          host          (host-context config* root opts)
          source        (-start-config-source config* root opts)]
      (runtime/install! {:config config* :registries registries :host host})
      (runtime/install-config-berths! {:config config* :module-index module-index})
      (some-> source runtime/start!)
      (reset! running* {:host          host
                        :registries    registries
                        :reloader      (when (and source root (not (false? (:start-config-reloader? opts))))
                                        (-start-reloader! source root host registries))
                        :source        source})
      this))
  (stop [this]
    (when-let [{:keys [host registries reloader source]} @running*]
      (let [module-index (:module-index host)]
        (when (seq registries)
          (runtime/reconcile! host config nil registries))
        (runtime/install-config-berths! {:config       nil
                                         :old-config   config
                                         :module-index module-index}))
      (some-> reloader future-cancel)
      (some-> source runtime/stop!))
    (reset! running* nil)
    this))

(defn running-state []
  (some-> (component-registry/instance-for :server-runtime)
          .-running*
          deref))

(defn valid-start? [config opts]
  (let [host          (or (:host opts) (get-in config [:server :host]) "127.0.0.1")
        start-http?   (not (false? (:start-http-server? opts)))
        auth-token    (get-in config [:server :auth :token])]
    (or (not start-http?)
             (http/loopback-host? host)
             (when (seq auth-token) true)
             (do (log/error :server/auth-required
                                     :host host
                                     :message "missing :server :auth :token for non-loopback bind")
                 false))))

(defmethod component-factory/create :server-runtime
  [_ {:keys [config module-index opts root]}]
  (->ServerRuntime config root opts module-index (atom nil)))
