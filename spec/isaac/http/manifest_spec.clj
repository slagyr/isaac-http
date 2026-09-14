(ns isaac.http.manifest-spec
  (:require
    [clojure.edn :as edn]
    [speclj.core :refer :all]))

(describe "server manifest"

  (it "declares server CLI command contributions"
    (let [commands (->> "resources/isaac-manifest.edn"
                        slurp
                        edn/read-string
                        :isaac/cli
                        keys
                        (map name)
                        set)]
      (should= #{"mcp-bridge"}
               commands)))

  (it "is a builtin module"
    (let [manifest (edn/read-string (slurp "resources/isaac-manifest.edn"))]
      (should= :isaac.http (:id manifest))
      (should (true? (:builtin? manifest)))))

  (it "does not contribute the server log stream — that is foundation's"
    (let [manifest (edn/read-string (slurp "resources/isaac-manifest.edn"))]
      (should= nil (:isaac/log-stream manifest))))))