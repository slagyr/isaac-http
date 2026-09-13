(ns isaac.http.server-steps
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.component.protocol]
    [isaac.component.registry]
    [isaac.config.loader :as loader]
    [isaac.config.runtime :as runtime]
    [isaac.config.server-config :as srv-config]
    [isaac.foundation.cli-steps :as fcli]
    [isaac.foundation.harness-config-steps :as fconfig]
    [isaac.foundation.fs-steps :as ffs]
    [isaac.foundation.root-steps :as froot]
    [isaac.log.file :as log-file]
    [isaac.http.logging :as server-logging]
    [isaac.http.test-store]
    [isaac.http.cli :as server]
    [isaac.module.loader :as module-loader]
    [isaac.session.store.spi :as store]
    [isaac.comm.factory :as comm-factory]
    [isaac.comm.registry :as comm-registry]
    [isaac.http.component.runtime :as server-runtime]
    [isaac.nexus :as nexus]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.main :as main]
    [isaac.runner.cli :as runner-cli]
    [isaac.spec-helper :as helper]
    [isaac.http.app :as app]
    [isaac.http.lifecycle :as lifecycle]
    [isaac.http.burst :as burst]
    [isaac.http.http :as server-http]
    [isaac.http.routes :as routes]
    [isaac.step-tables :as match]
    [isaac.tool.names :as names]
    [org.httpkit.client :as http]
    [org.httpkit.server :as httpkit]
    [taoensso.timbre :as timbre]))

(helper! isaac.http.server-steps)

(g/after-scenario
  (fn []
    (burst/clear-state!)
    (when-let [clear-all! (some-> (find-ns 'isaac.mcp.turns)
                                  (ns-resolve 'clear-all!))]
      (clear-all!))))

;; c3kit.apron.refresh logs via timbre and forces :info level, bypassing
;; isaac.logger. Disable timbre's default println appender at step-namespace
;; load time so c3kit's internal logs (">>>>> Stopping App", etc.) don't
;; pollute feature test output. Gherclj loads isaac.features.steps.* for
;; every run, so this silences timbre for the whole feature suite.
(timbre/merge-config! {:appenders {:println {:enabled? false}}})

;; The foundation isaac-file write steps (moved to isaac.foundation.fs-steps)
;; fire post-write hooks; register the server-side config-change notification
;; so hot-reload scenarios still get notified when a config file is written.
(fcli/register-isaac-run-wrapper!
  (fn [thunk]
    (if-let [ct (g/get :current-time)]
      (binding [log-file/*now* ct]
        (thunk))
      (thunk))))

(froot/register-root-setup-hook!
  (fn [abs-dir]
    (reset! comm-registry/*registry* (comm-registry/fresh-registry))
    (when-let [ns-obj (find-ns 'isaac.http.test-comm)]
      (remove-ns (ns-name ns-obj))
      (let [loaded-libs (var-get #'clojure.core/*loaded-libs*)]
        (dosync (alter loaded-libs disj 'isaac.http.test-comm)))
      (remove-method comm-factory/create :test-comm))
    (when-let [create-store (try (requiring-resolve 'isaac.session.store.memory/create-store)
                                 (catch Throwable _ nil))]
      (store/register-store! (create-store abs-dir)))))

(defn- parse-config-value [value]
  (cond
    (re-matches #"-?\d+" value) (parse-long value)
    (= "true" (str/lower-case value)) true
    (= "false" (str/lower-case value)) false
    (or (str/starts-with? value "[")
        (str/starts-with? value "{")
        (str/starts-with? value ":")
        (str/starts-with? value "\"")
        (str/starts-with? value "#"))
    (try
      (edn/read-string value)
      (catch RuntimeException _
        value))
    (= "bind-server-port" value) false
    :else value))

(defn- resolved-config-value [value]
  (if-let [[_ env-name] (re-matches #"\$\{([^}]+)\}" (str value))]
    (or (loader/env env-name) value)
    value))

(defn- config-path [path]
  (mapv keyword (str/split path #"\.")))

(defn- delete-sentinel? [value]
  (= "#delete" (str/trim (str value))))

(defn- skip-row? [value]
  (str/blank? (str value)))

(defn- dissoc-in [m path]
  (cond
    (empty? path)      m
    (= 1 (count path)) (dissoc m (first path))
    :else              (let [parent-path (vec (butlast path))
                             leaf        (last path)
                             parent      (get-in m parent-path)]
                         (if (map? parent)
                           (assoc-in m parent-path (dissoc parent leaf))
                           m))))

(defn- server-fs []
  (or (g/get :mem-fs)
      (fs/real-fs)))

(defn- with-server-fs [f]
  (let [fs* (server-fs)]
    (nexus/-with-nested-nexus {:fs fs*}
      (f))))

(defn- sync-config-reload! [source]
  (when (app/running?)
    (let [{:keys [comm-registry host registries]} (server-runtime/running-state)
          root (g/get :runtime-root-dir)]
      (loop []
        (when-let [rel (runtime/poll! source 0)]
          (runtime/reload! {:root          root
                            :fs            (server-fs)
                            :old-config    (loader/snapshot "feature: reload old-config")
                            :comm-registry comm-registry
                            :registries    registries
                            :host          host
                            :path          rel})
          (recur))))))

(ffs/register-post-write-hook!
  (fn [path]
    (when-let [source (g/get :config-change-source)]
      (runtime/notify-path! source path)
      (sync-config-reload! source))))

(defn- notify-config-change! [path]
  (g/dissoc! :feature-config)
  (when-let [source (g/get :config-change-source)]
    (runtime/notify-path! source path)
    (sync-config-reload! source)))

(defn- isaac-root-path []
  (g/get :root))

(defn- runtime-root-dir []
  (or (g/get :runtime-root-dir)
      (g/get :root)))

(defn- config-path? [path]
  (str/starts-with? path "config/"))

(defn- isaac-file-path [path]
  (cond
    (str/starts-with? path "/") path
    (= path "isaac.edn")         (str (isaac-root-path) "/config/isaac.edn")
    (config-path? path)          (str (isaac-root-path) "/" path)
    :else                        (str (runtime-root-dir) "/" path)))

(defn- parse-isaac-value [file-path path value]
  (cond
    (re-matches #"-?\d+" value) (parse-long value)
    (= "true" (str/lower-case value)) true
    (= "false" (str/lower-case value)) false
    (= path "tools.allow")
    (->> (str/split value #",")
         (map str/trim)
         (remove str/blank?)
         (mapv keyword))

    (or (str/starts-with? value "[")
        (str/starts-with? value "{")
        (str/starts-with? value ":")
        (str/starts-with? value "\"")
        (str/starts-with? value "#"))
    (edn/read-string value)

    (or (contains? #{"defaults.crew" "defaults.model"} path)
        (and (= path "model") (re-find #"/config/crew/" file-path))
        (and (= path "crew") (re-find #"/config/cron/" file-path))
    (and (= path "api") (re-find #"/config/providers/" file-path)))
    (keyword value)

    :else value))

(defn- isaac-file-data [path]
  (let [path (isaac-file-path path)
        fs*  (server-fs)]
    (when (fs/exists? fs* path)
      (edn/read-string (fs/slurp fs* path)))))

(defn- copy-state-tree! [source-fs source-path target-fs target-path]
  (when (fs/exists? source-fs source-path)
    (if (fs/file? source-fs source-path)
      (do
        (fs/mkdirs target-fs (fs/parent target-path))
        (fs/spit target-fs target-path (fs/slurp source-fs source-path)))
      (do
        (fs/mkdirs target-fs target-path)
        (doseq [child (or (fs/children source-fs source-path) [])]
          (copy-state-tree! source-fs
                            (str source-path "/" child)
                            target-fs
                            (str target-path "/" child)))))))



(defn- get-path [data path]
  (reduce (fn [current segment]
            (cond
              (nil? current) nil
              (map? current) (or (get current (keyword segment))
                                 (get current segment))
              :else nil))
          data
          (str/split path #"\.")))

(defn- config-file-path []
  (str (g/get :root) "/config/isaac.edn"))

(defn- load-server-config-result [root fs*]
  (let [load!       #(loader/load-config-result {:root root :fs fs*})
        entity-dir? #(with-server-fs
                       (fn []
                         (seq (fs/children fs* (str root "/config/" %)))))
        result      (load!)
        cfg         (:config result)]
    (if (and (or (entity-dir? "crew") (entity-dir? "models") (entity-dir? "providers"))
             (empty? (or (:crew cfg) {}))
             (empty? (or (:models cfg) {}))
             (empty? (or (:providers cfg) {})))
      (load!)
      result)))

(defn- load-server-config [root fs*]
  (:config (load-server-config-result root fs*)))

;; region ----- Setup -----

(defn- deep-merge [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))

(defn stop-server! []
  (app/stop!)
  (lifecycle/reset-hello!))

(g/after-scenario stop-server!)

(defn server-config-applied
  "Server harness overlay: bind-server-port, in-memory :server-config, and
   file-backed log.output. Persists dotted keys via the foundation helper."
  [table]
  (doseq [[k v] (fconfig/config-rows table)]
    (when-not (or (str/blank? (str k)) (= "key" k))
      (cond
        (= "log.output" k)
        (case v
          "memory" (do (log/set-output! :memory)
                       (log/clear-entries!)
                       (log-file/clear-sink-config!))
          (do (log/set-log-file! v)
              (log/set-output! :file)))

        (= "bind-server-port" k)
        (g/assoc! :bind-server-port? (parse-config-value v))

        :else
        (do
          (g/update! :server-config #(if (delete-sentinel? v)
                                       (dissoc-in (or % {}) (config-path k))
                                       (assoc-in (or % {}) (config-path k)
                                                  (parse-config-value (resolved-config-value v)))))
          (fconfig/persist-config-entry! k v))))))

;; isaac-edn-file-exists ("the isaac EDN file X exists with:") and
;; isaac-file-exists-with-content ("the isaac file X exists with:") moved to
;; isaac.foundation.fs-steps (write closure duplicated there).

(defn isaac-edn-file-contains-content [path content]
  (with-server-fs
    (fn []
      (let [file-path (isaac-file-path path)
            fs*       (server-fs)]
        (fs/mkdirs fs* (fs/parent file-path))
        (fs/spit   fs* file-path (str/trim content))
        (notify-config-change! file-path)))))

(defn isaac-config-path-is [path value]
  (with-server-fs
    (fn []
      (when-not (skip-row? value)
        (let [file-path (isaac-file-path "isaac.edn")
              data      (or (isaac-file-data "isaac.edn") {})
              fs*       (server-fs)]
          (fs/mkdirs fs* (fs/parent file-path))
          (fs/spit   fs* file-path
                          (pr-str (assoc-in data
                                            (mapv keyword (str/split path #"\."))
                                            (parse-isaac-value file-path path value))))
          (notify-config-change! file-path))))))

(defn- seeded-log-ts [n]
  (let [base (or (g/get :current-time) (java.time.Instant/parse "2026-05-12T00:00:00Z"))]
    (str (.plusSeconds base n))))

(defn isaac-file-with-log-entries [path n]
  (let [n     (parse-long n)
        lines (->> (range 1 (inc n))
                   (map #(format "{:ts \"%s\" :level :info :event :e%02d}"
                                 (seeded-log-ts %) %))
                   (str/join "\n"))]
    (with-server-fs
      (fn []
        (let [file-path (isaac-file-path path)
              fs*       (server-fs)]
          (fs/mkdirs fs* (fs/parent file-path))
          (fs/spit   fs* file-path lines))))))

(defn- clean-real-dir! [path]
  (let [dir (java.io.File. path)]
    (when (.exists dir)
      (doseq [f (-> dir file-seq reverse)]
        (.delete f)))))

(defn- default-server-home []
  (str (System/getProperty "user.dir") "/target/test-state/server-default-home"))

(g/before-scenario
  (fn []
    (when-not (g/get :root)
      (let [home (default-server-home)]
        (clean-real-dir! home)
        (.mkdirs (java.io.File. (str home "/config")))
        (g/assoc! :root home)))))

(defn server-running []
  (app/stop!)
  (let [explicit-home? (or (g/get :root) (g/get :root))
        virtual-home   (or explicit-home?
                           (default-server-home))
        mem            (g/get :mem-fs)
        ;; All server reads/writes flow through (nexus/get :fs). When the test
        ;; uses a mem-fs, app/start! installs it in the global nexus runtime so
        ;; HTTP handler threads see the same fs.
        home           (if mem
                         (do (g/assoc! :root virtual-home) virtual-home)
                         (do
                           (when-not explicit-home?
                             (clean-real-dir! virtual-home)
                             (g/assoc! :root virtual-home))
                           virtual-home))
        runtime-state  home
        load-result    (with-server-fs #(load-server-config-result home (server-fs)))
        cfg-map        (let [fs*     (server-fs)
                             base    (:config load-result)
                             merged  (deep-merge base
                                                 (merge (or (g/get :server-config) {})
                                                        (when-let [providers (g/get :provider-configs)]
                                                          {:providers providers})))
                             disc    (nexus/-with-nested-nexus {:fs fs*}
                                       (module-loader/discover! merged {:root runtime-state
                                                                        :cwd       (System/getProperty "user.dir")}))]
                               (assoc merged :module-index (merge (:index disc)
                                                                  (:inject-module-index merged))))
        cfg            (srv-config/server-config cfg-map)
        ;; For synthetic default homes, feature steps notify config changes
        ;; explicitly, so a memory-backed source is deterministic and cheap.
        ;; Real root scenarios keep the real watcher path when hot reload
        ;; is enabled; no watcher is needed for pure startup-only scenarios.
        config-source  (when (:hot-reload cfg)
                         (if (or mem (not explicit-home?))
                           (runtime/memory-source home)
                           (runtime/watch-service-source home)))
        _              (g/assoc! :config-change-source config-source)
        run-server?    (not (false? (g/get :bind-server-port?)))
        start-opts     {:config               cfg-map
                         :config-errors        (:errors load-result)
                         :module-index          (:module-index cfg-map)
                         ;; Acceptance requests inspect queued attention before
                         ;; delivery. Do not let the background worker race the
                         ;; pending-file assertions.
                         :start-background-services? false
                         :config-change-source config-source
                         ;; Feature harness reloads synchronously via sync-config-reload!;
                         ;; skip the async poll loop so it does not race on the source.
                         :start-config-reloader? false
                         :dev                  (= "true" (loader/env "ISAAC_DEV"))
                         :fs                   (server-fs)
                         :host                 (:host cfg)
                         ;; Explicit :server :port in the scenario's config is honored;
                         ;; otherwise bind ephemerally so suites never collide with a
                         ;; live isaac on the default port.
                         :port                 (if run-server?
                                                 (or (get-in cfg-map [:server :port]) 0)
                                                 0)
                         :root            runtime-state
                        :start-http-server?   run-server?}]
    (g/assoc! :runtime-root-dir runtime-state)
    (g/assoc! :server-handler-opts {:cfg-fn    (fn [] (or (loader/snapshot "feature: handler config") cfg-map))
                                    :root runtime-state
                                    :home      home})
    (let [start! (fn []
                   (server-logging/configure! runtime-state cfg-map)
                   (lifecycle/reset-hello!)
                   (lifecycle/emit-hello! runtime-state (:dev start-opts))
                   (app/start! start-opts)
                   (when run-server?
                     (g/assoc! :server-port
                               (some-> (isaac.component.registry/instance-for :http)
                                       isaac.component.protocol/bound-port))))]
      (if-let [ct (g/get :current-time)]
        (binding [log-file/*now* ct] (start!))
        (start!)))))

;; endregion ^^^^^ Setup ^^^^^

;; region ----- Server Commands -----

(defn- feature-server-config []
  (let [root (g/get :root)]
    (if root
      (deep-merge (with-server-fs #(load-server-config root (server-fs)))
                  (or (g/get :server-config) {}))
      (or (g/get :server-config) {}))))

(defn- feature-root
  "The scenario root, or the same per-scenario default home the in-process
   boot step uses. A feature-run server boot must never resolve to the real
   home root (isaac-stao)."
  []
  (or (g/get :root)
      (let [home (default-server-home)]
        (when-not (g/get :mem-fs) (clean-real-dir! home))
        (g/assoc! :root home)
        home)))

(defn- argv-with-feature-root [argv]
  (if (some #{"--root"} argv)
    argv
    (into ["--root" (feature-root)] argv)))

(defn- run-cli-with-stubbed-config!
  "Runs `argv` through isaac.main with loader/load-config-result stubbed to
   the feature root's on-disk config merged with :server-config, block!
   no-op'd, and httpkit server binding stubbed so startup/logging scenarios do
   not claim real ports. Always passes --root (the scenario root, else the
   per-scenario default home under target/) so logs and config never land in
   the real home root."
  [argv]
  (let [cfg   (feature-server-config)
        argv* (argv-with-feature-root argv)]
    (log-file/clear-sink-config!)
    (with-redefs [runner-cli/block!         (fn [] nil)
                  server/block!             (fn [] nil)
                  loader/load-config-result (fn [& _] {:config cfg})
                  httpkit/run-server        (fn [_handler opts] (atom (:port opts)))
                  httpkit/server-port       (fn [s] (or @s 0))
                  httpkit/server-stop!      (fn [_s] nil)]
      (with-out-str
        (app/stop!)
        (try
          (main/run argv*)
          (finally
            (app/stop!)))))))

(defn server-command-run [port]
  (run-cli-with-stubbed-config! ["server" "--port" (str port)]))

(defn server-command-run-no-port []
  (run-cli-with-stubbed-config! ["server"]))

(defn server-command-run-with-args [args]
  (let [arg-parts (remove str/blank? (str/split args #"\s+" 2))]
    (run-cli-with-stubbed-config! (into ["server"] arg-parts))))



;; endregion ^^^^^ Server Commands ^^^^^

;; region ----- Request / Response -----

(defn- extract-headers [rows]
  (into {} (keep (fn [[k v]]
                   (when (str/starts-with? k "header.")
                     [(subs k 7) v]))
                 rows)))

(defn- direct-headers [headers]
  (into {} (map (fn [[k v]] [(str/lower-case k) v])) headers))

(defn- extract-body [rows]
  (some (fn [[k v]] (when (= "body" k) v)) rows))

(defn- request-base-url []
  (let [port (g/get :server-port)
        host (or (get-in (g/get :server-config) [:server :host]) "localhost")
        host (if (= "::1" host) "[::1]" "localhost")]
    (str "http://" host ":" port)))

(defn- table->kv-rows [table]
  (let [rows (cond-> (:rows table)
               (seq (:headers table)) (conj (:headers table)))]
    (mapv (fn [row] (mapv identity row)) rows)))

(defn- current-server-config []
  (let [home     (or (g/get :root) (g/get :root))
        fs*      (server-fs)
        base     (with-server-fs #(load-server-config home fs*))
        merged   (deep-merge base
                             (merge (or (g/get :server-config) {})
                                    (when-let [providers (g/get :provider-configs)]
                                      {:providers providers})))
        runtime  (runtime-root-dir)
        disc     (nexus/-with-nested-nexus {:fs fs*}
                   (module-loader/discover! merged {:root runtime
                                                    :cwd       (System/getProperty "user.dir")}))]
    (assoc merged
           :module-index (:index disc)
           :root runtime)))

(defn- current-handler-opts []
  (or (g/get :server-handler-opts)
      (let [home (or (g/get :root) (g/get :root))]
        {:cfg-fn    current-server-config
         :root (runtime-root-dir)
         :home      home})))

(defn- register-direct-routes! [cfg]
  (reset! routes/*registry* (routes/fresh-registry))
  (let [module-index (merge (module-loader/foundation-index) (:module-index cfg))]
    (module-loader/process-manifest-berths! module-index)))

(defn- direct-response [request]
  (let [handler-opts (current-handler-opts)
        cfg          ((:cfg-fn handler-opts))
        fs*          (server-fs)]
    (register-direct-routes! cfg)
    (nexus/-with-nested-nexus {:fs fs* :root (:root handler-opts)}
      ((server-http/create-handler handler-opts) request))))

(defn- use-direct-http? []
  (let [port (g/get :server-port)
        host (or (get-in (current-server-config) [:server :host]) "127.0.0.1")]
    (or (not (pos? (long (or port 0))))
        (some? (g/get :current-time))
        (contains? #{"::1" "0:0:0:0:0:0:0:1"} host))))

(defn- with-fixed-clock [f]
  (if-let [ct (g/get :current-time)]
    (binding [log-file/*now* ct] (f))
    (f)))

(defn- send-http [method path headers]
  (let [method-kw (keyword (str/lower-case (name method)))
        headers   (or headers {})]
    (with-fixed-clock
      (fn []
        (let [resp (if (use-direct-http?)
                     (direct-response {:request-method method-kw
                                       :uri            path
                                       :headers        (direct-headers headers)
                                       :remote-addr    "127.0.0.1"})
                     (let [opts {:headers headers}]
                       @(case method-kw
                          :get  (http/get (str (request-base-url) path) opts)
                          :post (http/post (str (request-base-url) path) opts)
                          (http/request (assoc opts :method method-kw :url (str (request-base-url) path))))))]
          (g/assoc! :http-response resp))))))

(defn get-request [path]
  (send-http :get path {}))

(defn get-request-with-headers [path table]
  (let [rows    (table->kv-rows table)
        headers (extract-headers rows)]
    (send-http :get path headers)))

(defn get-request-with-header [path header]
  (let [[name value] (str/split header #":\s*" 2)]
    (send-http :get path {name value})))

(defn- as-count [n]
  (if (number? n) (long n) (parse-long (str n))))

(defn client-sends-n-times [method path n]
  (dotimes [_ (as-count n)]
    (send-http method path {})))

(defn client-sends-with-header-n-times [method path header n]
  (let [[name value] (str/split header #":\s*" 2)]
    (dotimes [_ (as-count n)]
      (send-http method path {name value}))))

(defn client-sends-n-times-with-headers [method path n table]
  (let [rows    (table->kv-rows table)
        headers (into {} rows)]
    (dotimes [_ (as-count n)]
      (send-http method path headers))))

(defn response-body-empty []
  (let [body (:body (g/get :http-response))]
    (g/should (or (nil? body) (str/blank? (str body))))))

(defn response-has-no-header [header]
  (let [resp   (g/get :http-response)
        actual (some (fn [[k v]]
                       (when (= (str/lower-case header) (str/lower-case (name k)))
                         v))
                     (:headers resp))]
    (g/should-be-nil actual)))

(defn- parse-header-line [header]
  (let [[name value] (str/split header #":\s*" 2)]
    {name value}))

(defn post-request-with-body [path body]
  (let [port (g/get :server-port)
        resp (if (pos? (long (or port 0)))
               @(http/post (str (request-base-url) path)
                           {:headers {"Content-Type" "application/json"}
                            :as      :text
                            :body    body})
               (direct-response {:request-method :post
                                 :uri            path
                                 :headers        {"content-type" "application/json"}
                                 :body           body}))]
    (g/assoc! :http-response resp)))

(defn post-request-with-header-and-body [path header body]
  (let [port    (g/get :server-port)
        headers (parse-header-line header)
        resp    (if (pos? (long (or port 0)))
                  @(http/post (str (request-base-url) path)
                              {:headers (assoc headers "Content-Type" "application/json")
                               :as      :text
                               :body    body})
                  (direct-response {:request-method :post
                                    :uri            path
                                    :headers        (direct-headers (assoc headers "Content-Type" "application/json"))
                                    :body           body}))]
    (g/assoc! :http-response resp)))

(defn- tools-from-csv [tools-str]
  (->> (str/split (or tools-str "") #",")
       (map str/trim)
       (remove str/blank?)
       (mapv (fn [token]
               (let [wire (or (names/wire-name token) token)]
                 {:name        wire
                  :description wire
                  :parameters  {:type "object"}})))))

(defn- fixture-tool-fn [_name arguments]
  (let [exec    (requiring-resolve 'isaac.tool.exec/exec-tool)
        present (requiring-resolve 'isaac.tool.registry/present-result)]
    (present (exec arguments))))

(defn turn-registered-with-tools [turn-id session-key tools-str]
  (let [register! (requiring-resolve 'isaac.mcp.turns/register!)]
    (register! turn-id {:session-key session-key
                        :tool-fn     fixture-tool-fn
                        :tools       (tools-from-csv tools-str)})))

(defn- body-value-at [body path]
  (match/get-path body path))

(defn post-request [path table]
  (let [port     (g/get :server-port)
        rows     (table->kv-rows table)
        headers  (extract-headers rows)
        body     (extract-body rows)
        headers  (if (and body (not (contains? headers "Content-Type")))
                   (assoc headers "Content-Type" "application/json")
                   headers)
        resp     (if (pos? (long (or port 0)))
                   @(http/post (str (request-base-url) path)
                               (cond-> {:headers headers :as :text}
                                 body (assoc :body body)))
                   (direct-response {:request-method :post
                                     :uri            path
                                     :headers        (direct-headers headers)
                                     :body           body}))]
    (g/assoc! :http-response resp)
    ;; Store hook turn future so session-transcript-matching can await it
    (when-let [hook-ns (find-ns 'isaac.hooks)]
      (when-let [fut-fn (ns-resolve hook-ns 'last-turn-future)]
        (when-let [fut (fut-fn)]
          (g/assoc! :turn-future fut))))))

(defn response-status [code]
  (let [resp   (g/get :http-response)
        status (:status resp)]
    (g/should= code status)))

(defn response-header-matches [header pattern]
  (let [resp   (g/get :http-response)
        actual (some (fn [[k v]]
                       (when (= (str/lower-case header) (str/lower-case (name k)))
                         v))
                     (:headers resp))]
    (g/should (some? actual))
    (g/should (re-find (re-pattern pattern) (str actual)))))

(defn server-failed-to-start []
  (g/should-not (app/running?))
  (g/should-not (g/get :server-port)))

(defn response-body-key-equals [key value]
  (let [resp   (g/get :http-response)
        body   (json/parse-string (:body resp) true)
        actual (body-value-at body key)]
    (g/should= value (str actual))))

(defn response-body-has-key [key]
  (let [resp (g/get :http-response)
        body (json/parse-string (:body resp) true)
        k    (keyword key)]
    (g/should-not-be-nil (get body k))))

;; isaac-file-edn-contains ("the isaac file X EDN contains:") and
;; edn-isaac-file-does-not-exist ("the isaac file X does not exist") moved to
;; isaac.foundation.fs-steps.

(defn isaac-edn-file-removed [path]
  (with-server-fs
    (fn []
      (let [file-path (isaac-file-path path)
            fs*       (server-fs)]
        (when (fs/exists? fs* file-path)
          (fs/delete fs* file-path))
        (notify-config-change! file-path)))))

(defn isaac-file-removed [path]
  (with-server-fs
    (fn []
      (let [file-path (isaac-file-path path)
            fs*       (server-fs)]
        (when (fs/exists? fs* file-path)
          (fs/delete fs* file-path))
        (notify-config-change! file-path)))))

;; endregion ^^^^^ Request / Response ^^^^^

;; region ----- Log Assertions -----
;; "the log has entries matching:" / "no entries matching:" moved to
;; isaac.foundation.log-steps (foundation-grade; logger/step-tables only).

(defn config-reloaded []
  (helper/await-condition
    #(some (fn [entry] (= :config/reloaded (:event entry))) (log/get-entries))
    2000)
  (g/should (some (fn [entry] (= :config/reloaded (:event entry))) (log/get-entries))))

;; endregion ^^^^^ Log Assertions ^^^^^

;; region ----- Routing -----

(defgiven "server config:" isaac.http.server-steps/server-config-applied
  "Applies server harness settings from a key/value table (log.output,
   server.* keys, bind-server-port, in-memory :server-config).")

(defwhen "the isaac EDN file {path:string} is removed" isaac.http.server-steps/isaac-edn-file-removed
  "Deletes the EDN file at <root>/.isaac/<path> and fires a config-change
   notification so a running server's hot-reload processes the removal.")

(defwhen "the isaac file {path:string} is removed" isaac.http.server-steps/isaac-file-removed
  "Deletes any file at <root>/.isaac/<path> and fires a config-change
   notification so a running server's hot-reload processes the removal.")

(defgiven #"the isaac config path \"([^\"]+)\" is \"([^\"]*)\"" isaac.http.server-steps/isaac-config-path-is)

(defgiven #"the isaac EDN file \"([^\"]+)\" contains:" isaac.http.server-steps/isaac-edn-file-contains-content
  "Writes heredoc EDN content to <root>/.isaac/<path> and notifies the
   running config change source when present. Useful for replacing a whole
   config file instead of patching it with a table.")

(defwhen #"the isaac EDN file \"([^\"]+)\" changes to:" isaac.http.server-steps/isaac-edn-file-contains-content
  "Alias for the heredoc EDN writer used after startup to trigger hot reload
   with a full-file replacement.")

(defgiven #"the isaac file \"([^\"]+)\" exists with (\d+) log entries" isaac.http.server-steps/isaac-file-with-log-entries
  "Writes N EDN log lines to <root>/.isaac/<path>. Each line has a
   distinct two-digit-padded :event keyword (:e01..:eNN) so substring
   assertions don't collide across IDs.")

(defgiven "the Isaac server is started" isaac.http.server-steps/server-running
  "Stops any prior server, then starts one against :root / :root.
   Merges in-memory :server-config and :provider-configs over whatever
   loader/load-config-result returns from disk. When mem-fs is active,
   wires a synchronous memory change-source so hot-reload scenarios fire
   deterministically from test writes. Disables the async config reloader
   (:start-config-reloader? false) so sync-config-reload! is the sole consumer.")

(defwhen "the Isaac process is started" isaac.http.server-steps/server-running
  "Alias for 'the Isaac server is started' as a When step. Starts the full
   Isaac process (including comm activation) against the configured state dir.")

(defwhen "the Isaac server is stopped" isaac.http.server-steps/stop-server!)

(defwhen "the server command is run on port {port:int}" isaac.http.server-steps/server-command-run
  "Runs 'isaac server --port N' with server/block! stubbed to no-op and
   loader/load-config-result stubbed to {:config <feature server-config>}.
   Immediately stops the server after the run returns — use for testing
   startup flags/logging only.")

(defwhen "the server command is run without a port flag" isaac.http.server-steps/server-command-run-no-port)

(defwhen "the server command is run with args {args:string}" isaac.http.server-steps/server-command-run-with-args)

(defwhen "the isaac config is reloaded" isaac.http.server-steps/config-reloaded)



(defwhen #"a GET request is made to \"([^\"]+)\"$" isaac.http.server-steps/get-request)

(defwhen #"the client sends GET \"([^\"]+)\"$" isaac.http.server-steps/get-request)

(defwhen #"a GET request is made to \"([^\"]+)\":" isaac.http.server-steps/get-request-with-headers)

(defwhen #"the client sends GET \"([^\"]+)\" with header \"([^\"]+)\"$" isaac.http.server-steps/get-request-with-header)

(defwhen #"the client sends (\w+) \"([^\"]+)\" with header \"([^\"]+)\" (\d+) times"
  isaac.http.server-steps/client-sends-with-header-n-times
  "Repeats an HTTP request N times with one header. Last response is what
   'the response status is' inspects. Binds the foundation clock when fixed.")

(defwhen #"the client sends (\w+) \"([^\"]+)\" (\d+) times$"
  isaac.http.server-steps/client-sends-n-times
  "Repeats an HTTP request N times with no extra headers.")

(defwhen "the client sends {method:string} {path:string} {n:int} times with headers:"
  isaac.http.server-steps/client-sends-n-times-with-headers
  "Repeats an HTTP request N times with a header table (name | value).")

(defwhen #"a POST request is made to \"([^\"]+)\":" isaac.http.server-steps/post-request)

(defwhen "the client sends POST {path:string} with body:"
  isaac.http.server-steps/post-request-with-body
  "POST with a JSON docstring body and no extra headers.")

(defwhen "the client sends POST {path:string} with header {header:string} and body:"
  isaac.http.server-steps/post-request-with-header-and-body
  "POST with one Authorization-style header and a JSON docstring body.")

(defgiven "a turn {turn-id:string} is registered for session {session-key:string} with tools {tools:string}"
  isaac.http.server-steps/turn-registered-with-tools
  "Writes a registry entry so the MCP route can serve tools/list for this turn.")

(defthen "the response status is {code:int}" isaac.http.server-steps/response-status)

(defthen "the response body is empty" isaac.http.server-steps/response-body-empty)

(defthen "the response has no header {header:string}" isaac.http.server-steps/response-has-no-header)

(defthen #"the response header \"([^\"]+)\" matches \"([^\"]+)\"" isaac.http.server-steps/response-header-matches)

(defthen "the server failed to start" isaac.http.server-steps/server-failed-to-start)

(defthen "the response body has {key:string} equal to {value:string}" isaac.http.server-steps/response-body-key-equals)

(defthen "the response body has a {key:string} key" isaac.http.server-steps/response-body-has-key)

;; endregion ^^^^^ Routing ^^^^^
