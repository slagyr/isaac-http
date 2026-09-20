(ns isaac.http.burst-spec
  (:require
    [clojure.string :as str]
    [isaac.log.file :as log-file]
    [isaac.logger :as log]
    [isaac.http.burst :as burst]
    [isaac.http.http :as http]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all]))

(def token "s3cr3t")

(def burst-on
  {:threshold   30
   :window-ms   60000
   :cooldown-ms 600000
   :notify?     false
   :throttle?   false})

(defn- handler-for [burst-cfg]
  (http/create-handler {:cfg {:http {:auth  {:token token}
                                     :burst burst-cfg}}}))

(defn- unauth
  ([handler client] (unauth handler client "/.env"))
  ([handler client path]
   (handler {:request-method :get
             :uri            path
             :headers        {"x-forwarded-for" client}})))

(defn- authed
  ([handler] (authed handler nil))
  ([handler client]
   (handler {:request-method :get
             :uri            "/status"
             :headers        (cond-> {"authorization" (str "Bearer " token)}
                               client (assoc "x-forwarded-for" client))})))

(defn- events [name]
  (filter #(= name (:event %)) @log/captured-logs))

(defn- instant [s] (java.time.Instant/parse s))

(def ^:private posts* (atom []))

(defn- notifying-handler [burst-cfg]
  (reset! posts* [])
  (http/create-handler {:cfg {:http      {:auth  {:token token}
                                          :burst (assoc burst-cfg :notify? true)}
                             :attention {:notify {:comm :discord :target "ops"}}}}))

(defn- with-posts [f]
  (with-redefs [burst/delivery-enqueue-fn (constantly (fn [m] (swap! posts* conj m)))]
    (f)))

(describe "unauthenticated burst control"

  (helper/with-captured-logs)

  (before (burst/clear-state!))

  (after (burst/clear-state!))

  (it "does not detect a burst below the threshold"
    (let [handler (handler-for burst-on)]
      (dotimes [_ 29] (unauth handler "203.0.113.9"))
      (should= 0 (count (events :server/burst-detected)))))

  (it "detects a burst on the 30th unauthenticated response from one client"
    (let [handler (handler-for burst-on)]
      (dotimes [_ 29] (unauth handler "203.0.113.9" "/wp-json/wc/v3/payment_gateways"))
      (unauth handler "203.0.113.9" "/wp-content/debug.log")
      (let [detected (events :server/burst-detected)]
        (should= 1 (count detected))
        (should= :warn (:level (first detected)))
        (should= "203.0.113.9" (:client (first detected)))
        (should= 30 (:count (first detected)))
        (should= 60000 (:window-ms (first detected))))))

  (it "does not post a second detection while the burst continues"
    (let [handler (handler-for burst-on)]
      (dotimes [_ 30] (unauth handler "203.0.113.9"))
      (dotimes [_ 60] (unauth handler "203.0.113.9" "/xmlrpc.php"))
      (should= 1 (count (events :server/burst-detected)))))

  (it "counts the window per client"
    (let [handler (handler-for burst-on)]
      (dotimes [_ 29] (unauth handler "203.0.113.9"))
      (dotimes [_ 29] (unauth handler "203.0.113.10"))
      (should= 0 (count (events :server/burst-detected)))))

  (it "ends a flagged burst after a quiet cooldown, using the foundation clock"
    (let [handler (handler-for burst-on)
          t0      (java.time.Instant/parse "2026-03-01T10:00:00Z")
          t1      (java.time.Instant/parse "2026-03-01T10:10:01Z")]
      (binding [log-file/*now* t0]
        (dotimes [_ 45] (unauth handler "203.0.113.9")))
      (binding [log-file/*now* t1]
        (authed handler))
      (let [ended (events :server/burst-ended)]
        (should= 1 (count ended))
        (should= :info (:level (first ended)))
        (should= "203.0.113.9" (:client (first ended)))
        (should= 45 (:total (first ended))))))

  (it "answers a flagged client with a bare 429 before auth when throttle? is on"
    (let [handler (handler-for (assoc burst-on :throttle? true))]
      (dotimes [_ 30] (unauth handler "203.0.113.9"))
      (let [response (unauth handler "203.0.113.9")]
        (should= 429 (:status response))
        (should (or (nil? (:body response)) (str/blank? (str (:body response)))))
        (should-be-nil (get-in response [:headers "WWW-Authenticate"])))
      (let [ok (authed handler "203.0.113.10")]
        (should= 200 (:status ok)))
      (let [throttled (events :server/burst-throttled)]
        (should= 1 (count throttled))
        (should= :info (:level (first throttled)))
        (should= "203.0.113.9" (:client (first throttled))))))

  (it "counts throttled requests apart from the ones that reached auth"
    (let [handler (handler-for (assoc burst-on :throttle? true))]
      (binding [log-file/*now* (instant "2026-03-01T10:00:00Z")]
        (dotimes [_ 30] (unauth handler "203.0.113.9"))
        (dotimes [_ 20] (unauth handler "203.0.113.9")))
      (binding [log-file/*now* (instant "2026-03-01T10:10:01Z")]
        (authed handler))
      (let [ended (first (events :server/burst-ended))]
        (should= 30 (:total ended))
        (should= 20 (:throttled ended)))))

  (it "keeps the burst alive while throttled traffic continues"
    (let [handler (handler-for (assoc burst-on :throttle? true))]
      (binding [log-file/*now* (instant "2026-03-01T10:00:00Z")]
        (dotimes [_ 30] (unauth handler "203.0.113.9")))
      (binding [log-file/*now* (instant "2026-03-01T10:09:00Z")]
        (dotimes [_ 5] (unauth handler "203.0.113.9")))
      (binding [log-file/*now* (instant "2026-03-01T10:10:01Z")]
        (authed handler))
      (should= 0 (count (events :server/burst-ended)))
      (binding [log-file/*now* (instant "2026-03-01T10:19:01Z")]
        (authed handler))
      (let [ended (first (events :server/burst-ended))]
        (should= 1 (count (events :server/burst-ended)))
        (should= 30 (:total ended))
        (should= 5 (:throttled ended)))))

  (it "reports the span from the first hit to the last, not the wait for the sweep"
    (let [handler (handler-for burst-on)]
      (binding [log-file/*now* (instant "2026-03-01T10:00:00Z")]
        (dotimes [_ 30] (unauth handler "203.0.113.9")))
      (binding [log-file/*now* (instant "2026-03-01T10:00:30Z")]
        (dotimes [_ 15] (unauth handler "203.0.113.9")))
      (binding [log-file/*now* (instant "2026-03-01T10:10:31Z")]
        (authed handler))
      (let [ended (first (events :server/burst-ended))]
        (should= 45 (:total ended))
        (should= 0 (:throttled ended))
        (should= 30000 (:duration-ms ended)))))

  (it "posts refused, throttled and the span when the burst ends"
    (with-posts
      (fn []
        (let [handler (notifying-handler (assoc burst-on :throttle? true))]
          (binding [log-file/*now* (instant "2026-03-01T10:00:00Z")]
            (dotimes [_ 30] (unauth handler "203.0.113.9")))
          (binding [log-file/*now* (instant "2026-03-01T10:00:30Z")]
            (dotimes [_ 20] (unauth handler "203.0.113.9")))
          (binding [log-file/*now* (instant "2026-03-01T10:10:31Z")]
            (authed handler))
          (let [content (:content (last @posts*))]
            (should-contain "203.0.113.9" content)
            (should-contain "30 refused" content)
            (should-contain "20 throttled" content)
            (should-contain "30000ms" content))))))

  (it "logs every throttled request with its uri"
    (let [handler (handler-for (assoc burst-on :throttle? true))]
      (dotimes [_ 30] (unauth handler "203.0.113.9"))
      (unauth handler "203.0.113.9" "/.env")
      (unauth handler "203.0.113.9" "/wp-login.php")
      (let [blocked (events :server/burst-throttled-request)]
        (should= 2 (count blocked))
        (should= ["/.env" "/wp-login.php"] (mapv :uri blocked))
        (should= "203.0.113.9" (:client (first blocked)))
        (should= 429 (:status (first blocked))))))

  (it "never throttles loopback clients"
    (let [handler (handler-for (assoc burst-on :throttle? true))
          req     {:request-method :get :uri "/.env" :headers {} :remote-addr "127.0.0.1"}]
      (dotimes [_ 31] (handler req))
      (should= 401 (:status (handler req)))
      (should= 0 (count (events :server/burst-throttled)))))

  (it "never throttles tailnet clients"
    (let [handler (handler-for (assoc burst-on :throttle? true))]
      (dotimes [_ 31] (unauth handler "100.64.1.9"))
      (should= 401 (:status (unauth handler "100.64.1.9")))
      (should= 0 (count (events :server/burst-throttled)))))

  (it "is on with defaults when the burst group is absent"
    (let [handler (http/create-handler {:cfg {:http {:auth {:token token}}}})]
      (dotimes [_ 10] (unauth handler "203.0.113.9"))
      (should= 1 (count (events :server/burst-detected)))
      (should= 429 (:status (unauth handler "203.0.113.9")))))

  (it "is off when enabled is false"
    (let [handler (handler-for {:enabled false})]
      (dotimes [_ 40] (unauth handler "203.0.113.9"))
      (should= 0 (count (events :server/burst-detected)))
      (should= 401 (:status (unauth handler "203.0.113.9")))))

  (it "counts a 401 produced by the route itself after wrap-auth lets the request through"
    (let [inner   (fn [_] {:status 401 :headers {"Content-Type" "text/plain"} :body "nope"})
          handler (http/create-handler {:cfg     {:http {:auth {:token token}}}
                                        :handler inner})]
      (dotimes [_ 10]
        (handler {:request-method :get
                  :uri            "/fixture/self-auth"
                  :headers        {"authorization"   (str "Bearer " token)
                                   "x-forwarded-for" "203.0.113.9"}}))
      (should= 1 (count (events :server/burst-detected)))))

  (it "an explicit threshold overrides the default and the rest keep theirs"
    (let [handler (handler-for {:threshold 3})]
      (dotimes [_ 3] (unauth handler "203.0.113.9"))
      (should= 1 (count (events :server/burst-detected)))
      (should= 429 (:status (unauth handler "203.0.113.9")))))

  (it "detects a burst without the agent delivery queue on the classpath"
    (with-redefs [burst/delivery-enqueue-fn (constantly nil)]
      (let [handler (http/create-handler
                      {:cfg {:http      {:auth  {:token token}
                                         :burst (assoc burst-on :notify? true)}
                             :attention {:notify {:comm :discord :target "ops"}}}})]
        (dotimes [_ 30] (unauth handler "203.0.113.9"))
        (should= 1 (count (events :server/burst-detected))))))

  )
