(ns isaac.http.auth-spec
  (:require
    [isaac.http.auth :as sut]
    [speclj.core :refer :all]))

(defn fixture-identity [_request]
  {:name :fixture :scopes #{:fixture}})

(describe "HTTP principals"
  (it "hashes bearer secrets without retaining plaintext"
    (should= "sha256:f82da6e2b2e51c046a2eaf10964ef184b69c0aee9892f10a2a7b657477d407e6"
             (sut/sha256 "ci-secret")))

  (it "authenticates an overlap twin stored under the previous-secret key as name@prev"
    (let [twin {:hash (sut/sha256 "old-secret") :scopes #{:hail/send}}
          cfg  {:http {:auth {:principals {:ci (assoc {:hash (sut/sha256 "new-secret")
                                                       :scopes #{:hail/send}}
                                                      :previous twin)}}}}
          principal (sut/authenticate cfg "old-secret")]
      (should= (keyword "ci@prev") (:name principal))
      (should (sut/authorized? principal :hail/send))))

  (it "treats an overlap Instant expiry in the future as not expired"
    (should-not (sut/expired? (str (.plus (java.time.Instant/now) (java.time.Duration/ofHours 24))))))

  (it "authenticates a matching principal and checks scopes"
    (let [cfg {:http {:auth {:principals {:ci {:hash (sut/sha256 "ci-secret")
                                                  :scopes #{:hail/send}}}}}}
          principal (sut/authenticate cfg "ci-secret")]
      (should= :ci (:name principal))
      (should (sut/authorized? principal :hail/send))
      (should-not (sut/authorized? principal :cli))))

  (it "synthesizes the legacy token as admin"
    (let [principal (sut/authenticate {:http {:auth {:token "legacy"}}} "legacy")]
      (should= :admin (:name principal))
      (should (sut/authorized? principal :anything))))

  (it "registers request identity verifier symbols"
    (binding [sut/*identity-verifiers* (atom {})]
      (sut/register-identity-verifier! 'isaac.http.auth-spec/fixture-identity)
      (should= [{:name :fixture :scopes #{:fixture}}]
               (mapv #(% {}) (sut/identity-verifiers)))))

  (it "registers a data-shaped identity rule as an OIDC verifier"
    (binding [sut/*identity-verifiers* (atom {})]
      (sut/register-identity-entry! [:google-pubsub {:issuer "https://accounts.lantern.test"
                                                     :jwks "https://accounts.lantern.test/certs"
                                                     :audience "projects/harbor/topics/push"
                                                     :principal {:name :google-pubsub :scopes #{:google/push}}}])
      (let [fn-or-rule (first (sut/identity-verifiers))]
        (should (or (fn? fn-or-rule) (map? fn-or-rule))))))

  (it "reads a trust rule declared under http.auth.identity"
    (binding [sut/*identity-verifiers* (atom {})]
      (let [rule {:issuer    "https://accounts.lantern.test"
                  :jwks      "https://accounts.lantern.test/certs"
                  :audience  "projects/harbor/topics/push"
                  :principal {:name :lantern-ci :scopes #{:google/push}}}]
        (should= [(assoc rule :id :lantern-ci)]
                 (sut/identity-rules {:http {:auth {:identity {:lantern-ci rule}}}})))))

  (it "lets a config rule replace the registered rule of the same id"
    (binding [sut/*identity-verifiers* (atom {})]
      (sut/register-identity-entry! [:google-pubsub {:issuer    "https://accounts.lantern.test"
                                                     :jwks      "https://accounts.lantern.test/certs"
                                                     :audience  "projects/harbor/topics/push"
                                                     :principal {:name :google-pubsub :scopes #{:google/push}}}])
      (let [rules (sut/identity-rules
                    {:http {:auth {:identity {:google-pubsub {:issuer    "https://accounts.lantern.test"
                                                              :jwks      "https://accounts.lantern.test/certs"
                                                              :audience  "projects/harbor/topics/push"
                                                              :principal {:name :harbor-door :scopes #{:google/push}}}}}}})]
        (should= 1 (count rules))
        (should= :harbor-door (get-in (first rules) [:principal :name])))))

  (it "keeps registered rules a config rule does not name"
    (binding [sut/*identity-verifiers* (atom {})]
      (sut/register-identity-entry! [:google-pubsub {:issuer "https://accounts.lantern.test"
                                                     :jwks "https://accounts.lantern.test/certs"
                                                     :audience "projects/harbor/topics/push"
                                                     :principal {:name :google-pubsub :scopes #{:google/push}}}])
      (should= #{:google-pubsub :lantern-ci}
               (set (map :id (sut/identity-rules
                               {:http {:auth {:identity {:lantern-ci {:issuer "https://ci.lantern.test"}}}}}))))))

  (it "validates that a config trust rule can verify something"
    (should= {:errors [{:key "http.auth.identity.lantern-ci.jwks" :value "must be present"}
                       {:key "http.auth.identity.lantern-ci.audience" :value "must be present"}]}
             (sut/validate-identity-rules
               {:config {:http {:auth {:identity {:lantern-ci {:issuer "https://accounts.lantern.test"
                                                               :principal {:name :lantern-ci}}}}}}})))

  (it "rejects malformed expiration dates safely"
    (should (sut/expired? "not-a-date")))

  (it "validates non-empty scopes and ISO expiration dates"
    (should= {:errors [{:key "http.auth.principals.ci.scopes" :value "must not be empty"}
                       {:key "http.auth.principals.ci.expires"
                        :value "must be an ISO date (YYYY-MM-DD)"}]}
             (sut/validate-principals
               {:config {:http {:auth {:principals {:ci {:scopes #{} :expires "2026-99-99"}}}}}})))
  )
