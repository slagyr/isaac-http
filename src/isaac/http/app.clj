(ns isaac.http.app
  (:require
    [isaac.config.loader :as loader]
    [isaac.logger :as log]
    [isaac.runner :as runner]
    [isaac.http.component.runtime :as runtime]))

(defn running? []
  (runner/running?))

(defn current-config []
  (loader/snapshot "server/current-config accessor"))

(defn- log-config-errors! [errors]
  (doseq [{:keys [key path value]} errors]
    (log/error :config/validation-error :path (or path key) :message value)))

(defn start! [opts]
  (let [config (or (:config opts) (:cfg opts) {})
        errors (:config-errors opts)
        opts*  (cond-> (-> opts
                           (dissoc :config-errors)
                           (assoc :config config))
                 (:module-index config) (assoc :module-index (:module-index config)))]
    (cond
      (seq errors) (log-config-errors! errors)
      (runtime/valid-start? config opts*) (runner/start! opts*))))

(defn stop! []
  (runner/stop!))
