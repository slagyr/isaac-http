(ns isaac.http.auth-cli
  "isaac http auth mint|rotate|revoke|list — principal secrets."
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.config.mutate :as mutate]
    [isaac.http.audit :as audit]
    [isaac.http.auth :as auth]
    [isaac.http.oidc :as oidc])
  (:import
    (java.security SecureRandom)
    (java.time Instant Duration)
    (java.util Base64)))

(defn generate-secret
  "32 random bytes, unpadded base64url."
  []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- parse-scopes [scopes]
  (cond
    (set? scopes) scopes
    (sequential? scopes) (set (map keyword scopes))
    (string? scopes)
    (->> (str/split scopes #",")
         (map str/trim)
         (remove str/blank?)
         (map keyword)
         set)
    :else #{}))

(defn- load-config [root]
  (:config (loader/load-config-result {:root root})))

(defn- principal-path [name]
  (str "http.auth.principals." name))

(defn- write-principal! [root name principal]
  (mutate/set-config root (principal-path name) principal
                     :skip-ref-validation? true
                     :skip-module-validation? true))

(defn- principals-at [root]
  (get-in (load-config root) [:http :auth :principals] {}))

(defn- write-principals! [root principals]
  (mutate/set-config root "http.auth.principals" (or principals {})
                     :skip-ref-validation? true
                     :skip-module-validation? true))

(defn- unset-principal! [root name]
  (write-principals! root (dissoc (principals-at root) (keyword name))))

(defn- existing [root name]
  (get-in (load-config root) [:http :auth :principals (keyword name)]))

(defn- format-error [{:keys [key value]}]
  (str key ": " value))

(defn- format-errors
  "Renders each mutate/set-config validation error as its own `<path>: <message>`
   line, instead of the raw EDN vector — an operator reading `isaac http auth
   mint` output on a terminal wants readable lines, not (pr-str errors)."
  [errors]
  (str/join "\n" (map format-error errors)))

(defn mint!
  [root name {:keys [scopes expires]}]
  (let [scope-set (parse-scopes scopes)]
    (cond
      (empty? scope-set)
      {:exit 1 :error "--scopes is required"}

      (existing root name)
      {:exit 1 :error (str name " already exists; use rotate")}

      :else
      (let [secret    (generate-secret)
            principal (cond-> {:hash (auth/sha256 secret) :scopes scope-set}
                        (seq expires) (assoc :expires expires))
            result    (write-principal! root name principal)]
        (if (= :ok (:status result))
          {:exit 0 :secret secret}
          {:exit 1 :error (format-errors (:errors result))})))))

(defn- parse-overlap [overlap]
  (when (seq overlap)
    (let [s (str overlap)]
      (cond
        (re-matches #"\d+h" s) (Duration/ofHours (parse-long (subs s 0 (dec (count s)))))
        (re-matches #"\d+d" s) (Duration/ofDays (parse-long (subs s 0 (dec (count s)))))
        (re-matches #"\d+" s)  (Duration/ofHours (parse-long s))
        :else                  (Duration/ofHours 24)))))

(defn rotate!
  [root name {:keys [overlap]}]
  (let [current (existing root name)]
    (if-not current
      {:exit 1 :error (str "unknown principal: " name)}
      (let [secret   (generate-secret)
            duration (parse-overlap overlap)
            new-p    (cond-> {:hash (auth/sha256 secret) :scopes (:scopes current)}
                       (:expires current) (assoc :expires (:expires current))
                       duration (assoc :previous (assoc (dissoc current :previous)
                                                        :expires (str (.plus (Instant/now) duration)))))
            result   (write-principal! root name new-p)]
        (if (= :ok (:status result))
          {:exit 0 :secret secret}
          {:exit 1 :error (format-errors (:errors result))})))))

(defn revoke!
  [root name]
  (if-not (existing root name)
    {:exit 1 :error (str "unknown principal: " name)}
    (do
      (unset-principal! root name)
      {:exit 0})))

(defn- format-scope-kw [k]
  (let [n (name k)]
    (if (and (namespace k) (not= "*" n))
      (str (namespace k) "/" n)
      n)))

(defn- format-scopes [scopes]
  (let [s (set (map #(if (keyword? %) % (keyword %)) scopes))]
    (if (contains? s :*)
      "*"
      (->> s (map format-scope-kw) sort (str/join ",")))))

(defn- format-expires [expires]
  (if (seq expires) (str expires) "-"))

(defn- last-used-for [last-used name]
  (or (get last-used name)
      (get last-used (keyword name))
      "never"))

(defn- row [name principal last-used]
  {:name      (clojure.core/name name)
   :scopes    (format-scopes (:scopes principal))
   :expires   (format-expires (:expires principal))
   :last-used (last-used-for last-used (clojure.core/name name))})

(defn- oidc-row [rule cfg last-used]
  (let [principal (:principal rule)
        rule      (or (oidc/resolve-rule rule cfg) rule)
        n         (clojure.core/name (or (:name principal) (:id rule) :oidc))]
    {:name      (str n " (oidc)")
     :scopes    (format-scopes (:scopes principal))
     :expires   (str (or (:issuer rule) "-") " " (or (:audience rule) "-"))
     :last-used (last-used-for last-used n)}))

(defn list-rows [load-result]
  (let [principals (get-in load-result [:config :http :auth :principals] {})
        root       (or (get-in load-result [:config :root]) "")
        last-used  (try (audit/read-last-used root) (catch Exception _ {}))
        oidc-rows  (map #(oidc-row % (:config load-result) last-used)
                        (auth/identity-rules (:config load-result)))]
    (->> principals
         (mapcat (fn [[name principal]]
                   (cond-> [(row name principal last-used)]
                     (get principal :previous)
                     (conj (row (str (clojure.core/name name) "@prev") (get principal :previous) last-used)))))
         (concat oidc-rows)
         (sort-by :name)
         vec)))
