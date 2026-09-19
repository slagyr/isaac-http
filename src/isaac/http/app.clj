(ns isaac.http.app
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.http.component.runtime :as runtime]
    [isaac.logger :as log]
    [isaac.runner :as runner]
    [isaac.schema.registered-in :as registered-in]))

(defn running? []
  (runner/running?))

(defn current-config []
  (loader/snapshot "server/current-config accessor"))

(defn- log-config-errors! [errors]
  (doseq [{:keys [key path value]} errors]
    (log/error :config/validation-error :path (or path key) :message value)))

(defn- contributed-comm-types [config]
  (->> (:module-index config)
       vals
       (mapcat #(keys (or (get-in % [:manifest :isaac.server/comm])
                             (get-in % [:manifest :isaac.http/comm]))))
       (map (comp name keyword))
       set))

(defn- resolved-after-discovery? [comm-types {:keys [path value]}]
  (and (some-> path (str/starts-with? "comms."))
       (some->> value
                (re-matches #"unknown :type \"([^\"]+)\"")
                second
                (contains? comm-types))))

(defn- unresolved-errors [config errors]
  (let [comm-types (contributed-comm-types config)]
    (remove #(resolved-after-discovery? comm-types %) errors)))

(defn start! [opts]
  (let [config (or (:config opts) (:cfg opts) {})
        errors (unresolved-errors config (:config-errors opts))
        opts*  (cond-> (-> opts
                           (dissoc :config-errors)
                           (assoc :config config)
                           (assoc :config-warnings (or (:config-warnings opts) [])))
                 (:module-index config) (assoc :module-index (:module-index config)))]
    (cond
      (seq errors) (log-config-errors! errors)
      (runtime/valid-start? config opts*)
      (binding [registered-in/*module-index* (or (:module-index opts*) (:module-index config) {})]
        (runner/start! opts*)))))

(defn stop! []
  (runner/stop!))
