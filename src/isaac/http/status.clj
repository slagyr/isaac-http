(ns isaac.http.status
  (:require
    [cheshire.core :as json]
    [isaac.component.supervisor :as supervisor]))

(defn- subsystem-view [health]
  (into {} (map (fn [[id v]] [id (select-keys v [:status :restarts :last-error])]) health)))

(defn- overall-status [health]
  ;; A dead (circuit-broken) subsystem makes the server unhealthy; a subsystem
  ;; mid-restart is degraded but not yet a hard failure (isaac-royn).
  (let [statuses (map (comp :status val) health)]
    (cond
      (some #{:down} statuses)       "unhealthy"
      (some #{:restarting} statuses) "degraded"
      :else                          "ok")))

(defn handle [_request]
  (let [health (supervisor/health)
        status (overall-status health)]
    {:status  (if (= "unhealthy" status) 503 200)
     :headers {"Content-Type" "application/json"}
     :body    (json/generate-string {:status     status
                                     :services   {:isaac "running"}
                                     :subsystems (subsystem-view health)})}))
