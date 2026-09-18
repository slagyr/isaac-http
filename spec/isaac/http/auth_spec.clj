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

  (it "rejects malformed expiration dates safely"
    (should (sut/expired? "not-a-date")))

  (it "validates non-empty scopes and ISO expiration dates"
    (should= {:errors [{:key "http.auth.principals.ci.scopes" :value "must not be empty"}
                       {:key "http.auth.principals.ci.expires"
                        :value "must be an ISO date (YYYY-MM-DD)"}]}
             (sut/validate-principals
               {:config {:http {:auth {:principals {:ci {:scopes #{} :expires "2026-99-99"}}}}}})))
  )
