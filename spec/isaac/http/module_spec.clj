(ns isaac.http.module-spec
  (:require
    [isaac.module.discovery :as discovery]
    [isaac.module.protocol]
    [isaac.http.module :as sut]
    [speclj.core :refer :all]))

(describe "isaac.http.module"

  (describe "comm-kinds"

    (it "returns empty when module index has no comm entries"
      (should= [] (sut/comm-kinds {})))

    (it "returns sorted comm kind name from a module"
      (let [index {:my.mod {:manifest {:isaac.agent/comm {:telly {:factory 'foo/make}}}}}]
        (should= ["telly"] (sut/comm-kinds index))))

    (it "filters out entries with :configurable? false"
      (let [index {:my.mod {:manifest {:isaac.agent/comm {:internal {:factory 'foo/make :configurable? false}
                                              :external {:factory 'bar/make}}}}}]
        (should= ["external"] (sut/comm-kinds index))))

    (it "aggregates and sorts kinds from multiple modules"
      (let [index {:mod-a {:manifest {:isaac.agent/comm {:bravo {:factory 'a/make}}}}
                   :mod-b {:manifest {:isaac.agent/comm {:alpha {:factory 'b/make}}}}}]
        (should= ["alpha" "bravo"] (sut/comm-kinds index))))

    (it "with no args falls back to builtin-index"
      (let [index {:isaac.http {:coord {} :manifest {:id :isaac.http :version "1"
                                                      :isaac.agent/comm {:widget {:factory 'foo/make}}}}}]
        (binding [discovery/*foundation-index-override* index]
          (should= ["widget"] (sut/comm-kinds))))))

  (describe "create-module"
    (it "returns a module record"
      (should (satisfies? isaac.module.protocol/Module (sut/create-module))))))