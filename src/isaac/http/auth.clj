(ns isaac.http.auth
  "Principal authentication and handler-level scope enforcement."
  (:require
    [c3kit.apron.util :as util])
  (:import
    (java.nio.charset StandardCharsets)
    (java.security MessageDigest)
    (java.time LocalDate)
    (java.time.format DateTimeParseException)))

(defn sha256 [secret]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str secret) StandardCharsets/UTF_8))]
    (str "sha256:" (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))

(defn- secure= [left right]
  (MessageDigest/isEqual (.getBytes (str left) StandardCharsets/UTF_8)
                         (.getBytes (str right) StandardCharsets/UTF_8)))

(defn expired? [expires]
  (when (seq expires)
    (try
      (.isBefore (LocalDate/parse expires) (LocalDate/now))
      (catch DateTimeParseException _
        true))))

(def ^:dynamic *identity-verifiers* (atom {}))

(defn register-identity-verifier! [verifier]
  (let [id (or (some-> verifier meta :name) verifier)]
    (swap! *identity-verifiers* assoc id
           (if (symbol? verifier)
             (some-> (util/resolve-var verifier) deref)
             verifier))
    id))

(defn identity-verifiers []
  (vals @*identity-verifiers*))

(defn principals [cfg]
  (let [configured (get-in cfg [:server :auth :principals] {})
        legacy     (get-in cfg [:server :auth :token])]
    (cond-> configured
      (and (seq legacy) (not (contains? configured :admin)))
      (assoc :admin {:hash (sha256 legacy) :scopes #{:*} :legacy? true}))))

(defn authenticate [cfg bearer]
  (when (seq bearer)
    (let [candidate (sha256 bearer)
          matches   (mapv (fn [[name principal]]
                            [(secure= (:hash principal) candidate) name principal])
                          (principals cfg))]
      (some (fn [[match? name principal]]
              (when match? (assoc principal :name name)))
            matches))))

(defn authorized? [principal scope]
  (let [scopes (set (map #(if (keyword? %) % (keyword %)) (:scopes principal)))]
    (or (contains? scopes :*)
        (contains? scopes scope))))

(defn valid-expiration? [expires]
  (or (nil? expires)
      (try
        (LocalDate/parse expires)
        true
        (catch DateTimeParseException _
          false))))

(defn validate-principals [{:keys [config]}]
  (let [principals (get-in config [:server :auth :principals])]
    {:errors
     (vec
       (mapcat
         (fn [[name principal]]
           (let [prefix (str "server.auth.principals." (clojure.core/name name))]
             (cond-> []
               (empty? (:scopes principal))
               (conj {:key (str prefix ".scopes") :value "must not be empty"})

               (not (valid-expiration? (:expires principal)))
               (conj {:key (str prefix ".expires") :value "must be an ISO date (YYYY-MM-DD)"}))))
         principals))}))

(defn require-scope! [request scope]
  (when-not (authorized? (:isaac/principal request) scope)
    (throw (ex-info "forbidden" {:status 403 :isaac.http/reason :scope :scope scope})))
  request)
