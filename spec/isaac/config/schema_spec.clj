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
    (let [result (schema/conform (http-schema) {:host "localhost" :port 8080})]
      (should= "localhost" (:host result))
      (should= 8080 (:port result))))

  (it "http fills burst defaults when the group is absent"
    (let [burst (:burst (schema/conform (http-schema) {:host "localhost"}))]
      (should= true (:enabled burst))
      (should= 10 (:threshold burst))
      (should= 60000 (:window-ms burst))
      (should= 600000 (:cooldown-ms burst))
      (should= true (:throttle? burst))
      (should= true (:notify? burst))))

  (it "http conforms with nested auth token"
    (let [result (schema/conform (http-schema) {:host "localhost" :auth {:token "s3cr3t"}})]
      (should= "localhost" (:host result))
      (should= "s3cr3t" (get-in result [:auth :token]))))

  (it "http conforms with nested burst knobs and fills remaining defaults"
    (let [burst (:burst (schema/conform (http-schema)
                                        {:host  "localhost"
                                         :burst {:threshold   30
                                                 :window-ms   60000
                                                 :cooldown-ms 600000
                                                 :notify?     true
                                                 :throttle?   false}}))]
      (should= 30 (:threshold burst))
      (should= false (:throttle? burst))
      (should= true (:enabled burst))
      (should= true (:notify? burst))))

  )
