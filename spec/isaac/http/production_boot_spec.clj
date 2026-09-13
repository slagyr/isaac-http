(ns isaac.http.production-boot-spec
  (:require
    [isaac.component.factory :as component-factory]
    [isaac.component.protocol :as component]
    [isaac.component.runtime :as component-runtime]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.module.discovery :as discovery]
    [isaac.runner :as runner]
    [isaac.runner.cli :as runner-cli]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all]))

(defonce events (atom []))

(deftype RecordingComponent [id]
  component/Component
  (start [this]
    (swap! events conj [:start id])
    this)
  (stop [this]
    (swap! events conj [:stop id])
    this)
  component/BoundPort
  (bound-port [_] 6674))

(doseq [id [:acceptance/resume :acceptance/delivery :acceptance/discord
            :acceptance/server-runtime :acceptance/http]]
  (defmethod component-factory/create id [id _]
    (->RecordingComponent id)))

(describe "production server command"

  (helper/with-captured-logs)

  (it "starts HTTP, reload, resume, delivery, and Discord components and stops in reverse"
    (let [component-ids [:acceptance/resume :acceptance/delivery :acceptance/discord
                         :acceptance/server-runtime :acceptance/http]
          module-index  (into {}
                              (map (fn [id]
                                     [(keyword (str "test." (name id)))
                                      {:manifest {:id id
                                                  :isaac/component
                                                  {id {:namespace 'isaac.http.production-boot-spec}}}}])
                                   component-ids))]
      (reset! events [])
      (nexus/init! {:fs (fs/mem-fs)})
      (with-redefs [discovery/discover! (fn [_ _] {:index module-index})
                    runner-cli/block!   (constantly nil)]
        (with-out-str
          (runner-cli/run {:config       {:module-index module-index}
                           :module-index module-index
                           :root         nil
                           :fs           (fs/mem-fs)
                           :port         "0"}))
        (runner/stop!))
      (let [started (take (count component-ids) @events)
            stopped (drop (count component-ids) @events)]
        (should= (set component-ids) (set (map second started)))
        (should= (mapv (comp #(vector :stop %) second) (reverse started))
                 stopped))
      (should (some #(and (= :runner/started (:event %))
                          (>= (:components %) 4))
                    @log/captured-logs))
      (should= [] (component-runtime/started-components))))

  )
