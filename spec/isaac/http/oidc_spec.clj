(ns isaac.http.oidc-spec
  (:require
    [cheshire.core]
    [isaac.http.oidc :as sut]
    [isaac.http.oidc-fixture :as fx]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)))

(defn- seconds-from-now [n]
  (.getEpochSecond (.plusSeconds (Instant/now) n)))

(describe "OIDC JWT verification"

  (with rsa (fx/generate-rsa))
  (with jwks {:keys [(fx/rsa-jwk (:public @rsa) fx/kid)]})
  (with header {:kid fx/kid})
  (before (sut/reset-jwks-cache!))

  (around [it]
    (binding [sut/*fetch-jwks* (fn [_url] {:status 200 :body @jwks :headers {}})]
      (it)))

  (it "accepts a compact JWS signed by the stubbed issuer key with matching iss/aud/claims"
    (let [token (fx/sign-rs256 (:private @rsa) @header (fx/claims {}))]
      (should= {:name :google-pubsub :scopes #{:google/push} :oidc? true}
               (select-keys (sut/verify token (fx/rule) {}) [:name :scopes :oidc?]))))

  (it "accepts an ES256 JWS against a P-256 JWK"
    (let [ec    (fx/generate-p256)
          token (fx/sign (:private ec) {:alg "ES256" :kid "lantern-ec"} (fx/claims {}))]
      (binding [sut/*fetch-jwks* (fn [_url] {:status 200 :body {:keys [(fx/ec-jwk (:public ec) "lantern-ec")]} :headers {}})]
        (should= :google-pubsub (:name (sut/verify token (fx/rule) {}))))))

  (it "rejects a JWT signed by a different key with :reason :signature"
    (let [token (fx/sign-rs256 (:private (fx/generate-rsa)) @header (fx/claims {}))
          result (sut/verify token (fx/rule) {})]
      (should= :signature (:reason result))
      (should-be-nil (:name result))))

  (it "rejects a tampered payload with :reason :signature"
    (let [token   (fx/sign-rs256 (:private @rsa) @header (fx/claims {}))
          [h _ s] (clojure.string/split token #"\.")
          forged  (str h "." (fx/b64url (.getBytes (cheshire.core/generate-string (fx/claims {:sub "forged"})))) "." s)]
      (should= :signature (:reason (sut/verify forged (fx/rule) {})))))

  (it "rejects alg none regardless of claims"
    (let [token (fx/sign-rs256 (:private @rsa) @header (fx/claims {}))
          [_ p s] (clojure.string/split token #"\.")
          none    (str (fx/b64url (.getBytes "{\"alg\":\"none\",\"kid\":\"lantern-1\"}")) "." p "." s)]
      (should= :signature (:reason (sut/verify none (fx/rule) {})))))

  (it "rejects an expired JWT with :reason :expired"
    (let [token (fx/sign-rs256 (:private @rsa) @header (fx/claims {:exp (seconds-from-now -120)}))]
      (should= :expired (:reason (sut/verify token (fx/rule) {})))))

  (it "rejects a JWT with no exp claim with :reason :expired"
    (let [token (fx/sign-rs256 (:private @rsa) @header (dissoc (fx/claims {}) :exp))]
      (should= :expired (:reason (sut/verify token (fx/rule) {})))))

  (it "rejects a not-yet-valid JWT with :reason :nbf"
    (let [token (fx/sign-rs256 (:private @rsa) @header (fx/claims {:nbf (seconds-from-now 120)}))]
      (should= :nbf (:reason (sut/verify token (fx/rule) {})))))

  (it "rejects a JWT with the wrong audience with :reason :audience"
    (let [token (fx/sign-rs256 (:private @rsa) @header (fx/claims {:aud "someone-else"}))]
      (should= :audience (:reason (sut/verify token (fx/rule) {})))))

  (it "accepts the audience when aud is a list containing it"
    (let [token (fx/sign-rs256 (:private @rsa) @header (fx/claims {:aud ["other" fx/audience]}))]
      (should= :google-pubsub (:name (sut/verify token (fx/rule) {})))))

  (it "rejects a JWT with the wrong issuer with :reason :issuer"
    (let [token (fx/sign-rs256 (:private @rsa) @header (fx/claims {:iss "https://impostor.test"}))]
      (should= :issuer (:reason (sut/verify token (fx/rule) {})))))

  (it "rejects a JWT whose claims do not match the rule with :reason :claims"
    (let [token (fx/sign-rs256 (:private @rsa) @header (fx/claims {:email "someone-else@harbor.test"}))]
      (should= :claims (:reason (sut/verify token (fx/rule) {})))))

  (it "refreshes JWKS once on an unknown kid then accepts"
    (let [token (fx/sign-rs256 (:private @rsa) @header (fx/claims {}))
          hits  (atom 0)]
      (binding [sut/*fetch-jwks* (fn [_url]
                                   (swap! hits inc)
                                   {:status 200 :body (if (= 1 @hits) {:keys []} @jwks) :headers {}})]
        (should= :google-pubsub (:name (sut/verify token (fx/rule) {})))
        (should= 2 @hits))))

  (it "serves the second token from the JWKS cache"
    (let [hits (atom 0)]
      (binding [sut/*fetch-jwks* (fn [_url] (swap! hits inc) {:status 200 :body @jwks :headers {}})]
        (sut/verify (fx/sign-rs256 (:private @rsa) @header (fx/claims {})) (fx/rule) {})
        (sut/verify (fx/sign-rs256 (:private @rsa) @header (fx/claims {})) (fx/rule) {})
        (should= 1 @hits))))

  (it "fails closed when JWKS is unreachable with :reason :jwks-unavailable"
    (let [token (fx/sign-rs256 (:private @rsa) @header (fx/claims {}))]
      (binding [sut/*fetch-jwks* (fn [_url] {:status 503 :body nil :headers {}})]
        (should= :jwks-unavailable (:reason (sut/verify token (fx/rule) {}))))))

  (it "resolves config refs in the rule's audience and claims against the live config"
    (let [token (fx/sign-rs256 (:private @rsa) @header (fx/claims {}))
          rule  (assoc (fx/rule) :audience [:lantern :push :endpoint]
                                 :claims {:email [:lantern :push :service-account] :email_verified true})
          cfg   {:lantern {:push {:endpoint fx/audience :service-account fx/email}}}]
      (should= :google-pubsub (:name (sut/verify token rule {:cfg cfg})))
      (should= :audience (:reason (sut/verify token rule {:cfg {:lantern {:push {:endpoint "elsewhere" :service-account fx/email}}}})))))

  (it "treats a rule with an unresolved config ref as absent"
    (let [token (fx/sign-rs256 (:private @rsa) @header (fx/claims {}))
          rule  (assoc (fx/rule) :audience [:lantern :push :endpoint])]
      (should-be-nil (sut/verify token rule {:cfg {}}))))

  (it "returns nil for a non-JWT bearer so hash auth can try next"
    (should-be-nil (sut/verify "not-a-jwt" (fx/rule) {})))

  (it "refuses a JWT-shaped bearer whose parts are not JSON with :reason :signature"
    (should= :signature (:reason (sut/verify "abc.def.ghi" (fx/rule) {}))))
  )
