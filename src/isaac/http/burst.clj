(ns isaac.http.burst
  "Per-client sliding window of unauthenticated responses.
   Absent :http :burst group = off. Memory only; no transcript, no disk."
  (:require
    [clojure.string :as str]
    [isaac.log.file :as log-file]
    [isaac.logger :as log]))

(defonce ^:private state* (atom {}))

(defn clear-state!
  "Test hook — drop all per-client burst windows."
  []
  (reset! state* {}))

(defn- now-ms []
  (.toEpochMilli (log-file/instant-now)))

(defn- notify? [burst-cfg]
  (not (false? (:notify? burst-cfg))))

(defn- parse-ipv4 [client]
  (when (and (string? client) (re-matches #"\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}" client))
    (mapv parse-long (str/split client #"\."))))

(defn throttle-exempt?
  "Loopback and tailnet (100.64.0.0/10) are never throttled."
  [client]
  (or (str/blank? client)
      (= "localhost" client)
      (= "::1" client)
      (= "0:0:0:0:0:0:0:1" client)
      (str/starts-with? (str client) "127.")
      (when-let [[a b] (parse-ipv4 client)]
        (and (= 100 a) (<= 64 b 127)))))

(defn- sample-path [paths uri]
  (let [paths (or paths [])]
    (cond
      (some #{uri} paths) paths
      (< (count paths) 5)  (conj paths uri)
      :else                paths)))

(defn- apply-hit [st now burst-cfg uri]
  (let [window-ms   (or (:window-ms burst-cfg) 60000)
        threshold   (or (:threshold burst-cfg) 30)
        prior-hits  (filterv #(>= % (- now window-ms)) (or (:hits st) []))
        hits        (conj prior-hits now)
        detected?   (boolean (:detected? st))
        total       (if detected? (inc (or (:total st) 0)) (count hits))
        first-ms    (cond
                      detected?                           (:first-ms st)
                      (seq prior-hits)                    (or (:first-ms st) now)
                      :else                               now)
        crossed?    (and (not detected?) (>= (count hits) threshold))]
    {:hits              hits
     :total             total
     :first-ms          first-ms
     :last-ms           now
     :paths             (sample-path (:paths st) uri)
     :detected?         (or detected? crossed?)
     :just-detected?    crossed?
     :window-count      (count hits)
     :throttled-logged? (:throttled-logged? st)}))

(defn delivery-enqueue-fn
  "Agent-owned. Nil when isaac.comm.delivery.queue is not on the classpath."
  []
  (try (requiring-resolve 'isaac.comm.delivery.queue/enqueue!)
       (catch Throwable _ nil)))

(defn enqueue-attention! [cfg content]
  (when-let [{:keys [comm target]} (get-in cfg [:attention :notify])]
    (when (and comm target)
      (if-let [enqueue! (delivery-enqueue-fn)]
        (try
          (enqueue! {:comm    (if (string? comm) (keyword comm) comm)
                     :target  target
                     :content content})
          (catch Exception e
            (log/warn :server/burst-notify-failed :error-message (.getMessage e))))
        (log/debug :server/burst-notify-skipped :reason :no-delivery-queue)))))

(defn- notify-detected! [cfg client count window-ms paths]
  (let [path-part (when (seq paths)
                    (str ", paths like " (str/join ", " paths)))
        content   (str "Unauthenticated burst from " client ": "
                       count " requests in " window-ms "ms" path-part)]
    (enqueue-attention! cfg content)))

(defn- notify-ended! [cfg client total duration-ms]
  (enqueue-attention! cfg
                      (str "Unauthenticated burst from " client " ended: "
                           total " requests in " duration-ms "ms")))

(defn record-unauthenticated!
  "Count a 401 against `client`. On crossing threshold, log
   :server/burst-detected once and optionally enqueue attention."
  [burst-cfg cfg client uri]
  (when (and burst-cfg (not (str/blank? client)))
    (let [now  (now-ms)
          next (get (swap! state* update client
                           (fn [st] (apply-hit st now burst-cfg uri)))
                    client)]
      (when (:just-detected? next)
        (log/warn :server/burst-detected
                  :client    client
                  :count     (:window-count next)
                  :window-ms (or (:window-ms burst-cfg) 60000))
        (when (notify? burst-cfg)
          (notify-detected! cfg client
                            (:window-count next)
                            (or (:window-ms burst-cfg) 60000)
                            (:paths next)))))))

(defn sweep-ended!
  "Emit :server/burst-ended (and a closing attention post) for any client
   whose last unauthenticated hit is older than cooldown-ms."
  [burst-cfg cfg]
  (when burst-cfg
    (let [now         (now-ms)
          cooldown-ms (or (:cooldown-ms burst-cfg) 600000)
          snapshot    @state*
          quiet?      (fn [st]
                        (>= (- now (or (:last-ms st) 0)) cooldown-ms))
          ended       (filterv (fn [[_ st]] (and (:detected? st) (quiet? st))) snapshot)
          stale       (filterv (fn [[_ st]] (and (not (:detected? st)) (quiet? st))) snapshot)
          drop-keys   (mapv first (concat ended stale))]
      (when (seq drop-keys)
        (swap! state* (fn [m] (apply dissoc m drop-keys))))
      (doseq [[client st] ended]
        (log/info :server/burst-ended :client client :total (:total st))
        (when (notify? burst-cfg)
          (notify-ended! cfg client (:total st) (- now (or (:first-ms st) now))))))))

(defn throttle-client?
  [burst-cfg client]
  (boolean
    (and burst-cfg
         (:throttle? burst-cfg)
         (not (throttle-exempt? client))
         (:detected? (get @state* client)))))

(defn throttled-response!
  "Bare 429. Logs :server/burst-throttled once per burst."
  [_burst-cfg client]
  (when-not (:throttled-logged? (get @state* client))
    (swap! state* assoc-in [client :throttled-logged?] true)
    (log/info :server/burst-throttled :client client))
  {:status 429 :headers {} :body ""})