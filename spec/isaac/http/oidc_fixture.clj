(ns isaac.http.oidc-fixture
  "JWT/JWKS fixture shared by the OIDC unit spec and the server feature steps.
   Runs natively under babashka: keys are generated and tokens signed with
   java.security, and the JWK parts are read back out of the key's DER
   SubjectPublicKeyInfo (bb exposes .getEncoded but not .getModulus)."
  (:require
    [cheshire.core :as json]
    [isaac.http.auth]
    [isaac.http.oidc :as oidc])
  (:import
    (java.math BigInteger)
    (java.security KeyPairGenerator Signature)
    (java.time Instant)
    (java.util Base64)))

;; ----- encoding -----

(defn b64url [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- b64url-str [s]
  (b64url (.getBytes (str s) "UTF-8")))

(defn- unsigned-bytes
  "Big-endian magnitude of a non-negative BigInteger without the sign byte."
  ^bytes [^BigInteger n]
  (let [bs (.toByteArray n)]
    (if (and (> (alength bs) 1) (zero? (aget bs 0)))
      (java.util.Arrays/copyOfRange bs 1 (alength bs))
      bs)))

;; ----- minimal DER reader, enough for SubjectPublicKeyInfo -----

(defn- der-node
  "Reads the TLV at `offset`: {:tag :start :end} with :start/:end bounding the content."
  [^bytes bs offset]
  (let [tag   (bit-and 0xff (aget bs offset))
        len-byte (bit-and 0xff (aget bs (inc offset)))]
    (if (< len-byte 128)
      {:tag tag :start (+ offset 2) :end (+ offset 2 len-byte)}
      (let [n   (bit-and len-byte 0x7f)
            len (reduce (fn [acc i] (+ (* acc 256) (bit-and 0xff (aget bs (+ offset 2 i))))) 0 (range n))]
        {:tag tag :start (+ offset 2 n) :end (+ offset 2 n len)}))))

(defn- der-children [^bytes bs {:keys [start end]}]
  (loop [offset start acc []]
    (if (>= offset end)
      acc
      (let [node (der-node bs offset)]
        (recur (:end node) (conj acc node))))))

(defn- der-content ^bytes [^bytes bs {:keys [start end]}]
  (java.util.Arrays/copyOfRange bs (int start) (int end)))

(defn- spki-bit-string
  "Content of the subjectPublicKey BIT STRING (unused-bits byte stripped)."
  ^bytes [^bytes spki]
  (let [[_algorithm key-bits] (der-children spki (der-node spki 0))
        content (der-content spki key-bits)]
    (java.util.Arrays/copyOfRange content 1 (alength content))))

(defn rsa-jwk
  "JWK (n, e) for an RSA public key, read from its X.509 encoding."
  [public-key kid]
  (let [rsa-key (spki-bit-string (.getEncoded public-key))
        [n e]   (map #(BigInteger. 1 ^bytes (der-content rsa-key %))
                     (der-children rsa-key (der-node rsa-key 0)))]
    {:kty "RSA" :use "sig" :alg "RS256" :kid kid
     :n (b64url (unsigned-bytes n)) :e (b64url (unsigned-bytes e))}))

(defn ec-jwk
  "JWK (x, y) for a P-256 public key, read from its X.509 encoding."
  [public-key kid]
  (let [point (spki-bit-string (.getEncoded public-key))] ; 04 || x || y
    {:kty "EC" :crv "P-256" :use "sig" :alg "ES256" :kid kid
     :x (b64url (java.util.Arrays/copyOfRange point 1 33))
     :y (b64url (java.util.Arrays/copyOfRange point 33 65))}))

;; ----- keys and signing -----

(defn generate-rsa []
  (let [kp (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048)))]
    {:private (.getPrivate kp) :public (.getPublic kp)}))

(defn generate-p256 []
  (let [kp (.generateKeyPair (doto (KeyPairGenerator/getInstance "EC")
                               (.initialize (java.security.spec.ECGenParameterSpec. "secp256r1"))))]
    {:private (.getPrivate kp) :public (.getPublic kp)}))

(defn- der-ecdsa->jose
  "java.security emits DER SEQUENCE{r, s}; JOSE wants r||s, 32 bytes each."
  ^bytes [^bytes der]
  (let [[r s] (map #(unsigned-bytes (BigInteger. 1 ^bytes (der-content der %)))
                   (der-children der (der-node der 0)))
        pad   (fn [^bytes b] (byte-array (concat (repeat (- 32 (alength b)) 0) (seq b))))]
    (byte-array (concat (seq (pad r)) (seq (pad s))))))

(defn sign
  "Compact JWS of `payload` under `header` (:alg RS256 or ES256) with `private-key`."
  [private-key header payload]
  (let [alg           (:alg header)
        signing-input (str (b64url-str (json/generate-string header))
                           "."
                           (b64url-str (json/generate-string payload)))
        signer        (doto (Signature/getInstance (case alg "RS256" "SHA256withRSA" "ES256" "SHA256withECDSA"))
                        (.initSign private-key)
                        (.update (.getBytes signing-input "UTF-8")))
        raw           (.sign signer)]
    (str signing-input "." (b64url (if (= "ES256" alg) (der-ecdsa->jose raw) raw)))))

(defn sign-rs256 [private-key header payload]
  (sign private-key (assoc header :alg "RS256") payload))

;; ----- the lantern issuer -----

(def issuer "https://accounts.lantern.test")
(def audience "projects/harbor/topics/push")
(def email "pubsub@harbor.test")
(def kid "lantern-1")

(defn claims [overrides]
  (merge {:iss            issuer
          :aud            audience
          :email          email
          :email_verified true
          :iat            (.getEpochSecond (Instant/now))
          :nbf            (.getEpochSecond (.minusSeconds (Instant/now) 5))
          :exp            (.getEpochSecond (.plusSeconds (Instant/now) 3600))}
         overrides))

(defn rule []
  {:issuer    issuer
   :jwks      (str issuer "/certs")
   :audience  audience
   :claims    {:email email :email_verified true}
   :principal {:name :google-pubsub :scopes #{:google/push}}})

;; ----- feature-step state -----

(defonce state* (atom {}))

(defn reset-fixture!
  "Per-scenario: drop tokens and stubs (the key pair is kept — generating one is slow),
   forget any JWKS stub, and empty the verifier's cache so nothing leaks between scenarios."
  []
  (swap! state* select-keys [:keys])
  (alter-var-root #'oidc/*fetch-jwks* (constantly (fn [_url] {:status 503 :body nil :headers {}})))
  (oidc/reset-jwks-cache!))

(defn- ensure-keys! []
  (or (:keys @state*)
      (let [k (generate-rsa)]
        (swap! state* assoc :keys k)
        k)))

(defn- serve-jwks! [doc-fn]
  (let [hits (atom 0)]
    (swap! state* assoc :jwks-hits hits)
    (oidc/reset-jwks-cache!)
    (alter-var-root #'oidc/*fetch-jwks*
                    (constantly (fn [_url]
                                  (swap! hits inc)
                                  (doc-fn @hits))))))

(defn register-trust-rule! []
  (ensure-keys!)
  (isaac.http.auth/register-identity-entry! [:google-pubsub (rule)]))

(defn register-trust-rule-with-config-refs! []
  (ensure-keys!)
  (isaac.http.auth/register-identity-entry!
    [:google-pubsub (assoc (rule) :audience [:lantern :push :endpoint]
                                  :claims {:email [:lantern :push :service-account] :email_verified true})]))

(defn stub-jwks-serves! []
  (let [doc {:keys [(rsa-jwk (:public (ensure-keys!)) kid)]}]
    (serve-jwks! (fn [_hit] {:status 200 :body doc :headers {}}))))

(defn stub-jwks-unreachable! []
  (serve-jwks! (fn [_hit] {:status 503 :body nil :headers {}})))

(defn stub-jwks-miss-then-serve! []
  (let [full {:keys [(rsa-jwk (:public (ensure-keys!)) kid)]}]
    (serve-jwks! (fn [hit] {:status 200 :body (if (= 1 hit) {:keys []} full) :headers {}}))))

(defn signed-token [kind]
  (let [k         (ensure-keys!)
        priv      (if (= "foreign-key" kind) (:private (generate-rsa)) (:private k))
        overrides (case kind
                    "expired"   {:exp (.getEpochSecond (.minusSeconds (Instant/now) 120))}
                    "nbf"       {:nbf (.getEpochSecond (.plusSeconds (Instant/now) 120))}
                    "wrong-aud" {:aud "someone-else"}
                    "wrong-iss" {:iss "https://impostor.test"}
                    {})
        token     (sign-rs256 priv {:kid kid} (claims overrides))]
    (swap! state* assoc :token token)
    token))

(defn last-token []
  (or (:token @state*) (signed-token "valid")))

(defn jwks-hit-count []
  (if-let [hits (:jwks-hits @state*)] @hits 0))
