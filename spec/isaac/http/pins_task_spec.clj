(ns isaac.http.pins-task-spec
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [clojure.string :as str]
    [speclj.core :refer :all]))

(describe "bb pins CLI resolution"
  (it "prefers the checked-out foundation CLI before ISAAC or PATH"
    (let [bb-edn (slurp "bb.edn")]
      (should (str/includes? bb-edn "../isaac-foundation/libexec/isaac"))
      (should (str/includes? bb-edn "fs/executable?"))
      (should (< (str/index-of bb-edn "fs/executable?")
                 (str/index-of bb-edn "System/getenv \"ISAAC\""))))))
