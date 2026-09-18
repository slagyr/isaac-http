(ns isaac.http.audit-spec
  (:require
    [isaac.fs :as fs]
    [isaac.http.audit :as sut]
    [isaac.http.burst :as burst]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "auth audit"

  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs) :root "/root"}
      (example)))

  (before (sut/reset-state!))
  (after (sut/reset-state!))

  (it "does not post first-use attention for a legacy token principal"
    (let [posted (atom [])]
      (with-redefs [burst/enqueue-attention! (fn [_cfg content] (swap! posted conj content))]
        (sut/record-use! {:http {:auth {:alerts {:first-use true}}}
                          :attention {:notify {:comm :discord :target "ops"}}}
                         "/root"
                         {:name :admin :legacy? true :scopes #{:*}})
        (should= [] @posted))))

  (it "posts first-use attention for a named principal"
    (let [posted (atom [])]
      (with-redefs [burst/enqueue-attention! (fn [_cfg content] (swap! posted conj content))]
        (sut/record-use! {:http {:auth {:alerts {:first-use true}}}
                          :attention {:notify {:comm :discord :target "ops"}}}
                         "/root"
                         {:name :ci :scopes #{:hail/send}})
        (should= ["first use of principal ci"] @posted))))

  )
