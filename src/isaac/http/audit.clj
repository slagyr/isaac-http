(ns isaac.http.audit
  "Last-used persistence and attention posts for principal auth events."
  (:require
    [clojure.edn :as edn]
    [isaac.fs :as fs]
    [isaac.http.burst :as burst]
    [isaac.log.file :as log-file])
  (:import
    (java.time Instant LocalDate ZoneOffset)))

(defonce ^:private seen-hashes* (atom {}))
(defonce ^:private last-write-at* (atom {}))
(defonce ^:private expiry-flagged* (atom {}))
(defonce ^:private last-used-write-count* (atom 0))

(defn reset-state!
  "Test hook."
  []
  (reset! seen-hashes* {})
  (reset! last-write-at* {})
  (reset! expiry-flagged* {})
  (reset! last-used-write-count* 0))

(defn last-used-write-count []
  @last-used-write-count*)

(defn- now-instant ^Instant []
  (log-file/instant-now))

(defn- now-str []
  (str (now-instant)))

(defn- today []
  (LocalDate/ofInstant (now-instant) ZoneOffset/UTC))

(defn- filesystem []
  (or (fs/instance) (fs/real-fs)))

(defn last-used-path [root]
  (str root "/state/auth/last-used.edn"))

(defn read-last-used [root]
  (let [fs*  (filesystem)
        path (last-used-path root)]
    (if (fs/exists? fs* path)
      (or (edn/read-string (fs/slurp fs* path)) {})
      {})))

(defn- write-last-used! [root data]
  (let [fs*  (filesystem)
        path (last-used-path root)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (pr-str data))
    (swap! last-used-write-count* inc)))

(defn- principal-name [principal]
  (when-let [n (:name principal)]
    (if (keyword? n) (name n) (str n))))

(defn remember-principal! [principal]
  (when (and principal (:hash principal) (:name principal))
    (swap! seen-hashes* assoc (:hash principal) (principal-name principal))))

(defn remembered-name [hash]
  (get @seen-hashes* hash))

(defn- alerts [cfg]
  (or (get-in cfg [:http :auth :alerts])
      (get-in cfg [:server :auth :alerts])))

(defn- alert-on? [cfg kind]
  (not (false? (get (alerts cfg) kind))))

(defn- due-to-write? [pname]
  (let [prev (get @last-write-at* pname)
        n    (now-instant)]
    (or (nil? prev)
        (>= (- (.toEpochMilli n) (.toEpochMilli ^Instant prev)) 60000))))

(defn record-use! [cfg root principal]
  (when (and (seq (str root)) (principal-name principal))
    (let [pname  (principal-name principal)
          kw     (keyword pname)
          stored (read-last-used root)
          first? (not (contains? stored kw))]
      (when (due-to-write? pname)
        (swap! last-write-at* assoc pname (now-instant))
        (write-last-used! root (assoc stored kw (now-str))))
      (when (and first? (not (:legacy? principal)) (alert-on? cfg :first-use))
        (burst/enqueue-attention! cfg (str "first use of principal " pname))))))

(defn note-refusal! [cfg principal reason remembered]
  (let [pname (or (principal-name principal) remembered)]
    (case reason
      :expired (when (and pname (alert-on? cfg :expired))
                 (burst/enqueue-attention! cfg (str "expired secret used for principal " pname)))
      :revoked (when (and pname (alert-on? cfg :revoked))
                 (burst/enqueue-attention! cfg (str "revoked secret used for principal " pname)))
      nil)))

(defn sweep-expiring! [cfg]
  (when (alert-on? cfg :expiring)
    (let [today   (today)
          horizon (.plusDays today 7)
          flag-on (str today)]
      (doseq [[pname principal] (or (get-in cfg [:http :auth :principals])
                                     (get-in cfg [:server :auth :principals]))]
        (when-let [exp (:expires principal)]
          (when-let [day (try (LocalDate/parse exp) (catch Exception _ nil))]
            (when (and (not (.isBefore day today)) (not (.isAfter day horizon)))
              (let [n (if (keyword? pname) (name pname) (str pname))]
                (when (not= flag-on (get @expiry-flagged* n))
                  (swap! expiry-flagged* assoc n flag-on)
                  (burst/enqueue-attention!
                    cfg (str "principal " n " expires " exp)))))))))))
