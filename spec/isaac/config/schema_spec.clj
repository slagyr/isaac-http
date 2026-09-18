(ns isaac.config.schema-spec
  (:require
    [c3kit.apron.schema :as schema]
    [clojure.edn :as edn]
    [speclj.core :refer :all]))

(defn- http-schema []
  (-> "resources/isaac-manifest.edn"
      slurp
      edn/read-string
      (get-in [:isaac.config/schema :http :schema])))

(describe "config schema"

  (it "http conforms"
    (should= {:host "localhost" :port 8080}
             (schema/conform (http-schema) {:host "localhost" :port 8080})))

  (it "http conforms with nested auth token"
    (should= {:host "localhost" :auth {:token "s3cr3t"}}
             (schema/conform (http-schema) {:host "localhost" :auth {:token "s3cr3t"}})))

  (it "http conforms with nested burst knobs"
    (should= {:host  "localhost"
              :burst {:threshold   30
                      :window-ms   60000
                      :cooldown-ms 600000
                      :notify?     true
                      :throttle?   false}}
             (schema/conform (http-schema)
                             {:host  "localhost"
                              :burst {:threshold   30
                                      :window-ms   60000
                                      :cooldown-ms 600000
                                      :notify?     true
                                      :throttle?   false}})))

  )
