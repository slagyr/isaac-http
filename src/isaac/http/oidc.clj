(ns isaac.http.oidc
  "Compact JWS/JWT verification against a JWKS document.
   Modules contribute data-shaped :isaac.http/identity trust rules;
   this namespace owns the crypto."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [org.httpkit.client :as http])
  (:import
    (java.math BigInteger)
    (java.security KeyFactory Signature)
    (java.security.spec X509EncodedKeySpec)
    (java.time Instant)
    (java.util Base64)))

(def ^:dynamic *fetch-jwks*
  "Outbound JWKS fetch seam. Specs bind this. Production hits the issuer URL."
  (fn [url]
    (let [resp @(http/get url {:as :text :timeout 5000})]
      {:status  (:status resp)
       :body    (when (string? (:body resp))
                  (try (json/parse-string (:body resp) true)
                       (catch Exception _ (:body resp))))
       :headers (or (:headers resp) {})})))

(defonce ^:private jwks-cache* (atom {}))

(defn reset-jwks-cache!
  "Test helper."
  []
  (reset! jwks-cache* {}))

(defn- now-epoch []
  (.getEpochSecond (Instant/now)))

(defn- b64url-decode [s]
  (let [padded (case (mod (count s) 4)
                 2 (str s "==")
                 3 (str s "=")
                 s)]
    (.decode (Base64/getUrlDecoder) ^String padded)))

(defn- b64url-decode-str [s]
  (String. ^bytes (b64url-decode s) "UTF-8"))

(defn- split-jwt [token]
  (when (string? token)
    (let [parts (str/split token #"\." 3)]
      (when (= 3 (count parts))
        parts))))

(defn jwt-shaped? [token]
  (boolean (split-jwt token)))

(defn- parse-json-part [part]
  (try
    (json/parse-string (b64url-decode-str part) true)
    (catch Exception _ nil)))

(defn- decode-jwt [token]
  (when-let [[h p s] (split-jwt token)]
    (when-let [header (parse-json-part h)]
      (when-let [payload (parse-json-part p)]
        {:header header :payload payload :sig s :signing-input (str h "." p)}))))

(defn- int-from-b64url [s]
  (BigInteger. 1 ^bytes (b64url-decode s)))

;; ----- JWK -> java.security.PublicKey -----
;; The server runs under babashka, which exposes KeyFactory and X509EncodedKeySpec
;; but not RSAPublicKeySpec/ECPublicKeySpec. So the JWK parts are wrapped in a
;; DER SubjectPublicKeyInfo by hand and handed to KeyFactory as X.509 bytes —
;; the one key-spec route that works natively and on the JVM alike.

(defn- der-length [n]
  (if (< n 128)
    [n]
    (let [digits (loop [n n acc ()]
                   (if (zero? n) acc (recur (quot n 256) (cons (rem n 256) acc))))]
      (into [(bit-or 0x80 (count digits))] digits))))

(defn- der [tag content]
  (byte-array (map unchecked-byte (concat [tag] (der-length (count content)) content))))

(defn- der-sequence [& parts] (der 0x30 (mapcat seq parts)))
(defn- der-integer [^BigInteger i] (der 0x02 (seq (.toByteArray i))))
(defn- der-bit-string [content] (der 0x03 (cons 0 (seq content))))
(defn- der-null [] (der 0x05 []))
(defn- der-oid [encoded] (der 0x06 encoded))

(def ^:private OID-RSA   [0x2a 0x86 0x48 0x86 0xf7 0x0d 0x01 0x01 0x01]) ; 1.2.840.113549.1.1.1 rsaEncryption
(def ^:private OID-EC    [0x2a 0x86 0x48 0xce 0x3d 0x02 0x01])           ; 1.2.840.10045.2.1 ecPublicKey
(def ^:private OID-P256  [0x2a 0x86 0x48 0xce 0x3d 0x03 0x01 0x07])      ; 1.2.840.10045.3.1.7 prime256v1

(defn rsa-spki
  "DER SubjectPublicKeyInfo for an RSA public key (modulus n, exponent e)."
  ^bytes [^BigInteger n ^BigInteger e]
  (der-sequence (der-sequence (der-oid OID-RSA) (der-null))
                (der-bit-string (der-sequence (der-integer n) (der-integer e)))))

(defn p256-spki
  "DER SubjectPublicKeyInfo for a P-256 public key from its uncompressed point (x, y)."
  ^bytes [^bytes x ^bytes y]
  (der-sequence (der-sequence (der-oid OID-EC) (der-oid OID-P256))
                (der-bit-string (concat [4] (seq x) (seq y)))))

(defn- x509-public-key [algorithm ^bytes spki]
  (.generatePublic (KeyFactory/getInstance algorithm) (X509EncodedKeySpec. spki)))

(defn- rsa-public-key [{:keys [kty n e]}]
  (when (and (or (nil? kty) (= "RSA" kty)) n e)
    (x509-public-key "RSA" (rsa-spki (int-from-b64url n) (int-from-b64url e)))))

(defn- ec-public-key [{:keys [kty crv x y]}]
  (when (and (or (nil? kty) (= "EC" kty)) (or (nil? crv) (= "P-256" crv)) x y)
    (x509-public-key "EC" (p256-spki (b64url-decode x) (b64url-decode y)))))

(defn- cache-ttl-ms [headers cache-s]
  (let [cc (or (get headers "cache-control") (get headers :cache-control) "")
        max-age (when (re-find #"(?i)max-age=(\d+)" cc)
                  (parse-long (second (re-find #"(?i)max-age=(\d+)" cc))))]
    (* 1000 (or max-age cache-s 3600))))

(defn- cache-entry [url]
  (get @jwks-cache* url))

(defn- put-cache! [url body headers cache-s]
  (let [entry {:body body
               :fetched-at (System/currentTimeMillis)
               :ttl-ms (cache-ttl-ms headers cache-s)
               :keys (or (:keys body) [])}]
    (swap! jwks-cache* assoc url entry)
    entry))

(defn- fresh? [entry]
  (and entry
       (let [age (- (System/currentTimeMillis) (:fetched-at entry))]
         (< age (:ttl-ms entry)))))

(defn- fetch-jwks! [url cache-s]
  (let [resp (*fetch-jwks* url)]
    (if (and resp (= 200 (:status resp)) (map? (:body resp)))
      (put-cache! url (:body resp) (:headers resp) cache-s)
      :unavailable)))

(defn- find-jwk [entry kid]
  (when entry
    (some (fn [k]
            (when (= (str kid) (str (:kid k))) k))
          (:keys entry))))

(defn- load-jwk [url kid cache-s]
  (let [cached (cache-entry url)
        entry  (if (fresh? cached) cached (fetch-jwks! url cache-s))]
    (if (= :unavailable entry)
      :unavailable
      (or (find-jwk entry kid)
          (let [refreshed (fetch-jwks! url cache-s)]
            (if (= :unavailable refreshed)
              :unavailable
              (or (find-jwk refreshed kid) :unknown-kid)))))))

(defn- verify-rs256 [signing-input sig-b64 jwk]
  (try
    (when-let [pub (rsa-public-key jwk)]
      (let [sig (doto (Signature/getInstance "SHA256withRSA")
                  (.initVerify pub)
                  (.update (.getBytes ^String signing-input "UTF-8")))]
        (.verify sig (b64url-decode sig-b64))))
    (catch Exception _
      false)))

(defn- jose-es-sig->der
  "JOSE ES256 signatures are r||s (32 bytes each); java.security wants DER SEQUENCE{r, s}."
  ^bytes [^bytes raw]
  (let [half (quot (alength raw) 2)
        r    (BigInteger. 1 (java.util.Arrays/copyOfRange raw 0 half))
        s    (BigInteger. 1 (java.util.Arrays/copyOfRange raw half (alength raw)))]
    (der-sequence (der-integer r) (der-integer s))))

(defn- verify-es256 [signing-input sig-b64 jwk]
  (try
    (when-let [pub (ec-public-key jwk)]
      (let [sig (doto (Signature/getInstance "SHA256withECDSA")
                  (.initVerify pub)
                  (.update (.getBytes ^String signing-input "UTF-8")))]
        (.verify sig (jose-es-sig->der (b64url-decode sig-b64)))))
    (catch Exception _
      false)))

(defn- audience-of [aud]
  (cond
    (string? aud) #{aud}
    (sequential? aud) (set (map str aud))
    :else #{}))

(defn- claim-matches? [payload expected]
  (every? (fn [[k v]]
            (= v (get payload k (get payload (keyword k)))))
          expected))

(defn- skew-s [opts]
  (or (:skew-s opts) (get-in opts [:http :oidc :skew-s]) 60))

(defn- cache-s [opts]
  (or (:jwks-cache-s opts) (get-in opts [:http :oidc :jwks-cache-s]) 3600))

(defn- refused [reason]
  {:reason reason})

;; ----- config refs in rules -----
;; A rule value may be a vector of keywords — a path into the live config —
;; so a module's manifest can trust "the audience configured for this door"
;; without knowing the deployment. A rule with an unresolved ref is inert.

(defn- config-ref? [v]
  (and (vector? v) (seq v) (every? keyword? v)))

(defn- resolve-value [cfg v]
  (cond
    (config-ref? v) (get-in cfg v)
    (map? v)        (reduce-kv (fn [m k x] (assoc m k (resolve-value cfg x))) {} v)
    :else           v))

(defn- unresolved? [v]
  (cond
    (map? v) (some unresolved? (vals v))
    :else    (nil? v)))

(defn resolve-rule
  "The rule with its config refs resolved against `cfg`; nil when any ref has no value."
  [rule cfg]
  (let [resolved (resolve-value cfg (select-keys rule [:issuer :jwks :audience :claims]))]
    (when-not (some unresolved? (vals resolved))
      (merge rule resolved))))

(defn- accepted [rule]
  (merge (:principal rule) {:oidc? true}))

(defn verify
  "Verify compact JWT `token` against a data-shaped trust `rule`.
   Returns the principal map with :oidc? true on success, {:reason ...} on
   a JWT that failed this rule, or nil when the bearer is not JWT-shaped."
  [token rule opts]
  (if-not (jwt-shaped? token)
    nil
    (let [decoded (decode-jwt token)
          rule    (resolve-rule rule (:cfg opts))]
      (cond
        (nil? rule) nil
        (not decoded) (refused :signature)
        :else
        (let [{:keys [header payload sig signing-input]} decoded
              skew (skew-s opts)
              now  (now-epoch)
              iss  (:iss payload)
              aud  (:aud payload)
              exp  (:exp payload)
              nbf  (:nbf payload)
              iat  (:iat payload)
              kid  (:kid header)
              alg  (str (:alg header))]
          (cond
            (not= (str iss) (str (:issuer rule)))
            (refused :issuer)

            (not (contains? (audience-of aud) (str (:audience rule))))
            (refused :audience)

            (or (not (number? exp)) (> now (+ exp skew)))
            (refused :expired)

            (and nbf (< now (- nbf skew)))
            (refused :nbf)

            (and iat (< now (- iat skew)))
            (refused :nbf)

            (not (claim-matches? payload (or (:claims rule) {})))
            (refused :claims)

            :else
            (let [jwk (load-jwk (:jwks rule) kid (cache-s opts))]
              (cond
                (= :unavailable jwk) (refused :jwks-unavailable)
                (= :unknown-kid jwk) (refused :signature)
                (and (#{"RS256"} alg) (verify-rs256 signing-input sig jwk))
                (accepted rule)
                (and (#{"ES256"} alg) (verify-es256 signing-input sig jwk))
                (accepted rule)
                :else (refused :signature)))))))))
