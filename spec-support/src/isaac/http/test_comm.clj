(ns isaac.http.test-comm
  (:require
    [c3kit.apron.env :as env]
    [isaac.api :as api]
    [isaac.comm.factory :as factory]
    [isaac.comm.protocol :as comm]))

(when (= "true" (env/env "ISAAC_TEST_COMM_FAIL_ON_LOAD"))
  (throw (ex-info "test comm load failed"
                  {:entry     'isaac.http.test-comm
                   :module-id :isaac.http.test-comm
                   :type      :module/activation-failed})))

(deftype TestComm [host state*])

(extend TestComm
  comm/Comm
  (merge comm/defaults
         {:send! (fn [_ _] {:ok false :transient? false})})
  api/Reconfigurable
  {:on-load
   (fn [this slice]
     (reset! (.-state* this) {:slice      slice
                              :started?   true
                              :host       (.-host this)
                              :last-event :started}))
   :on-config-change!
   (fn [this old-slice new-slice]
     (swap! (.-state* this) assoc
            :slice new-slice
            :last-event :changed
            :prior old-slice))
   :on-unload
   (fn [this old-slice]
     (reset! (.-state* this) {:slice      nil
                              :started?   false
                              :host       (.-host this)
                              :last-event :stopped
                              :prior      old-slice}))})

(defn make [host]
  (->TestComm host (atom {})))

(defmethod factory/create :test-comm [node-path _slice]
  (make {:name (last node-path)}))

(defn test-comm? [value]
  (instance? TestComm value))

(defn state [comm]
  @(.-state* ^TestComm comm))
