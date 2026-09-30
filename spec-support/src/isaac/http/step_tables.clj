(ns isaac.http.step-tables
  (:require
    [clojure.string :as str]))

;; region ----- Path Parsing -----

(defn- parse-segment [s]
  (let [parts (re-seq #"([\w-]+)|\[(\d+)\]" s)]
    (mapv (fn [[_ key idx]]
            (if key
              [:key key]
              [:idx (parse-long idx)]))
          parts)))

(defn parse-path [path]
  (mapcat parse-segment (str/split path #"\.")))

(defn get-path [entity path-str]
  (reduce
    (fn [obj [tag v]]
      (case tag
        :key (get obj (keyword v))
        :idx (nth obj v nil)))
    entity
    (parse-path path-str)))

;; endregion ^^^^^ Path Parsing ^^^^^

;; region ----- Cell Parsing -----

(defn- parse-cell [s]
  (cond
    (str/blank? s)
    {:type :nil}

    (= "#*" s)
    {:type :wildcard}

    (str/starts-with? s "#\"")
    (if-let [[_ pattern name] (re-matches #"#\"(.+)\":([\w-]+)" s)]
      {:type :regex-capture :pattern (re-pattern (str "(?s)" pattern)) :name name}
      (let [[_ pattern] (re-matches #"#\"(.+)\"" s)]
        {:type :regex :pattern (re-pattern (str "(?s)" pattern))}))

    (re-matches #"#([\w-]+)" s)
    {:type :ref :name (second (re-matches #"#([\w-]+)" s))}

    (re-matches #"-?\d+" s)
    {:type :literal :value (parse-long s)}

    (= "true" s)  {:type :literal :value true}
    (= "false" s) {:type :literal :value false}

    :else
    {:type :literal :value s}))

;; endregion ^^^^^ Cell Parsing ^^^^^

;; region ----- Cell Matching -----

(defn- match-cell [cell actual captures]
  (let [actual (if (and (vector? actual)
                         (every? map? actual)
                         (every? #(= "text" (:type %)) actual))
                  (->> actual (map :text) (apply str))
                  actual)]
    (case (:type cell)
    :wildcard      (if (some? actual)
                     {:match true}
                     {:match false :message "Expected any non-nil value, got: nil"})
    :nil           (if (nil? actual)
                     {:match true}
                     {:match false :message (str "Expected nil, got: " (pr-str actual))})
    :literal       (if (or (= (:value cell) actual)
                            (= (str (:value cell)) (str actual))
                            (and (string? (:value cell))
                                 (keyword? actual)
                                 (= (:value cell) (name actual)))
                            (and (keyword? (:value cell))
                                 (string? actual)
                                 (or (= (name (:value cell)) actual)
                                     (= (str (:value cell)) actual))))
                      {:match true}
                      {:match false :message (str "Expected " (pr-str (:value cell)) ", got: " (pr-str actual))})
    :regex         (if (and (some? actual) (re-matches (:pattern cell) (str actual)))
                     {:match true}
                     {:match false :message (str "Expected match for " (:pattern cell) ", got: " (pr-str actual))})
    :regex-capture (if (and (some? actual) (re-matches (:pattern cell) (str actual)))
                     {:match true :capture {(:name cell) (str actual)}}
                     {:match false :message (str "Expected match for " (:pattern cell) ", got: " (pr-str actual))})
    :ref           (let [expected (get captures (:name cell))]
                      (if (= expected (str actual))
                        {:match true}
                        {:match false :message (str "Expected #" (:name cell) " (" (pr-str expected) "), got: " (pr-str actual))})))))

;; endregion ^^^^^ Cell Matching ^^^^^

;; region ----- Row Matching -----

(defn- match-row [headers values entity captures]
  (reduce
    (fn [{:keys [captures errors]} [header cell-str]]
      (let [cell   (parse-cell cell-str)
            actual (get-path entity header)
            result (match-cell cell actual captures)]
        (if (:match result)
          {:captures (merge captures (:capture result)) :errors errors}
          {:captures captures :errors (conj errors (str header ": " (:message result)))})))
    {:captures captures :errors []}
    (map vector headers values)))

(defn- row-matches? [headers values entity captures]
  (empty? (:errors (match-row headers values entity captures))))

(defn- best-match [headers values entries captures]
  (reduce
    (fn [best entry]
      (let [result (match-row headers values entry captures)]
        (if (< (count (:errors result)) (count (:errors best)))
          result
          best)))
     (match-row headers values (first entries) captures)
     (rest entries)))

(defn- resolve-index [idx entries-count]
  (let [resolved (if (neg? idx)
                   (+ entries-count idx)
                   idx)]
    (when (<= 0 resolved (dec entries-count))
      resolved)))

;; endregion ^^^^^ Row Matching ^^^^^

;; region ----- Public API -----

(defn match-entries
  "Match a horizontal table against a collection of entries.
   Returns {:pass? bool :failures [...] :captures {}}"
  [table entries]
  (let [headers    (:headers table)
        rows       (:rows table)
        has-index? (some #(= "#index" %) headers)
        meta-col?  (fn [h] (and (str/starts-with? h "#") (not= "#index" h)))
        data-hdrs  (vec (remove #(or (= "#index" %) (meta-col? %)) headers))]
    (loop [remaining rows
           row-num   0
           captures  {}
           failures  []]
      (if (empty? remaining)
        {:pass? (empty? failures) :failures failures :captures captures}
        (let [row       (first remaining)
              row-map   (zipmap headers row)
              idx       (when has-index? (parse-long (get row-map "#index")))
              data-vals (mapv #(get row-map %) data-hdrs)
              resolved-idx (when (some? idx) (resolve-index idx (count entries)))
              result    (cond
                          (and (some? idx) (nil? resolved-idx))
                          {:captures captures :errors [(str "index out of range: " idx)]}

                          (some? idx)
                          (match-row data-hdrs data-vals (nth entries resolved-idx nil) captures)

                          :else
                          (let [entity (first (filter #(row-matches? data-hdrs data-vals % captures) entries))]
                            (cond
                              (some? entity) (match-row data-hdrs data-vals entity captures)
                              (seq entries)  (best-match data-hdrs data-vals entries captures)
                              :else          {:captures captures :errors ["no entries to match against"]})))]
          (recur (rest remaining)
                 (inc row-num)
                 (merge captures (:captures result))
                 (into failures (map #(str "Row " row-num ": " %) (:errors result)))))))))

(defn match-value
  "Match a single expected cell against an actual value.
   Supports step_tables DSL (#\"regex\", #ref, wildcards, literals).
   Returns {:match bool :capture map}."
  [expected-text actual & {:keys [captures]}]
  (match-cell (parse-cell expected-text) actual (or captures {})))

(defn match-object
  "Match a vertical key|value table against a single object.
   Returns {:pass? bool :failures [...] :captures {}}"
  [table object]
  (let [rows (:rows table)]
    (loop [remaining rows
           captures  {}
           failures  []]
      (if (empty? remaining)
        {:pass? (empty? failures) :failures failures :captures captures}
        (let [[path expected] (first remaining)
              cell            (parse-cell expected)
              actual          (get-path object path)
              result          (match-cell cell actual captures)]
          (if (:match result)
            (recur (rest remaining) (merge captures (:capture result)) failures)
            (recur (rest remaining) captures (conj failures (str path ": " (:message result))))))))))

;; endregion ^^^^^ Public API ^^^^^
