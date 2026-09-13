(ns isaac.http.app-runner-spec
  (:require
    [isaac.runner :as runner]
    [isaac.http.app :as sut]
    [isaac.http.component.runtime :as runtime]
    [speclj.core :refer :all]))

(describe "server app runner"

  (it "delegates startup to Foundation without lifecycle hook bindings"
    (let [opts {:config {} :root "/isaac"}
          seen (atom nil)]
      (with-redefs [runtime/valid-start? (constantly true)
                    runner/start!        #(do (reset! seen %) ::started)]
        (should= ::started (sut/start! opts)))
      (should= opts (select-keys @seen (keys opts)))))

  (it "rejects loader errors before delegating startup"
    (let [started? (atom false)]
      (with-redefs [runtime/valid-start? (constantly true)
                    runner/start!        (fn [_] (reset! started? true))]
        (should-be-nil
          (sut/start! {:config        {}
                       :config-errors [{:key "comms.bigbird"
                                        :value "unknown :type \"unknown-type\""}]})))
      (should-not @started?)))

  (it "ignores pre-discovery comm errors after the discovered module validates the comm type"
    (let [started (atom nil)
          config  {:module-index {:isaac.http.test-comm
                                  {:manifest {:isaac.http/comm {:test-comm {}}}}}}]
      (with-redefs [runtime/valid-start? (constantly true)
                    runner/start!        #(reset! started %)]
        (sut/start! {:config        config
                     :config-errors [{:key "comms[:bert]"
                                      :path "comms.bert"
                                      :value "unknown :type \"test-comm\""}]}))
      (should= config (:config @started))))

  (it "delegates shutdown to Foundation"
    (with-redefs [runner/stop! (constantly ::stopped)]
      (should= ::stopped (sut/stop!))))

  )
