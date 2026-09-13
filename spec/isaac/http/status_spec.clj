(ns isaac.http.status-spec
  (:require
    [cheshire.core :as json]
    [isaac.http.status :as sut]
    [isaac.component.protocol :as protocol]
    [isaac.component.supervisor :as supervisor]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-07-04T10:00:00Z"))

(defn- dead-service []
  (reify
    protocol/Component (start [_]) (stop [_])
    protocol/Supervised (alive? [_] false)))

(defn- body [response]
  (json/parse-string (:body response) true))

(describe "Status handler"

  ;; supervisor health is a process-global atom; start each example clean so
  ;; ordering across specs can't leak a wedged subsystem into these assertions.
  (before (supervisor/reset-health!))
  (after (supervisor/reset-health!))

  (it "returns HTTP 200"
    (let [response (sut/handle {})]
      (should= 200 (:status response))))

  (it "returns JSON content-type"
    (let [response (sut/handle {})]
      (should= "application/json" (get-in response [:headers "Content-Type"]))))

  (it "returns body with status ok"
    (should= "ok" (:status (body (sut/handle {})))))

  (it "returns services map in body"
    (should-not-be-nil (:services (body (sut/handle {})))))

  (it "reports isaac service as running"
    (should= "running" (get-in (body (sut/handle {})) [:services :isaac])))

  (it "reports degraded while a supervised subsystem is restarting (isaac-royn)"
    (supervisor/supervise-once! [{:id :discord :instance (dead-service)}] t0)
    (let [response (sut/handle {})]
      (should= 200 (:status response))
      (should= "degraded" (:status (body response)))
      (should= "restarting" (get-in (body response) [:subsystems :discord :status]))))

  (it "reports unhealthy with 503 once a supervised subsystem is circuit-broken down"
    (let [entries [{:id :discord :instance (dead-service)}]]
      (doseq [n (range 8)]
        (supervisor/supervise-once! entries (.plusSeconds t0 (* n 60))))
      (let [response (sut/handle {})]
        (should= 503 (:status response))
        (should= "unhealthy" (:status (body response)))
        (should= "down" (get-in (body response) [:subsystems :discord :status])))))

  )
