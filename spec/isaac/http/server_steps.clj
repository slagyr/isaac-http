(ns isaac.http.server-steps
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.component.protocol]
    [isaac.component.registry]
    [isaac.config.config-steps :as config-steps]
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
    [isaac.http.audit :as audit]
    [isaac.http.burst :as burst]
    [isaac.http.auth :as auth]
    [isaac.http.http :as server-http]
    [isaac.http.oidc-fixture :as oidc-fixture]
    [isaac.http.routes :as routes]
    [isaac.step-tables :as match]
    [isaac.tool.names :as names]
    [org.httpkit.client :as http]
    [org.httpkit.server :as httpkit]
    [taoensso.timbre :as timbre]))

(helper! isaac.http.server-steps)

(defonce ^:private fixture-crew-patched? (atom false))

(defn- stamp-fixture-default-crew [path content]
  (if-not (or (= "isaac.edn" path)
              (str/ends-with? (str path) "/isaac.edn")
              (str/ends-with? (str path) "/config/isaac.edn"))
    content
    (try
      (let [cfg (edn/read-string content)]
        (if (or (not (map? cfg))
                (contains? (or (get-in cfg [:defaults :frequencies]) {}) :crew)
                (and (contains? (or (get-in cfg [:defaults :crew]) {}) :model)
                     (contains? cfg :crew)))
          content
          (let [crew-id (if (= 1 (count (:crew cfg)))
                          (first (keys (:crew cfg)))
                          "main")]
            (pr-str (-> cfg
                        (assoc-in [:defaults :frequencies :crew] crew-id)
                        (update :crew (fn [crew]
                                        (let [crew (or crew {})]
                                          (if (contains? crew crew-id)
                                            crew
                                            (assoc crew crew-id {}))))))))))
      (catch Exception _ content))))

(when (compare-and-set! fixture-crew-patched? false true)
  (alter-var-root #'config-steps/config-file-containing
                  (fn [orig]
                    (fn [path content]
                      (orig path (stamp-fixture-default-crew path content)))))
  (alter-var-root #'ffs/isaac-file-exists-with-content
                  (fn [orig]
                    (fn [path content]
                      (orig path (stamp-fixture-default-crew path content))))))

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
    (let [run (fn []
                (if-let [ct (g/get :current-time)]
                  (binding [log-file/*now* ct]
                    (thunk))
                  (thunk)))]
      (with-redefs [runner-cli/block! (fn [] nil)
                    server/block!     (fn [] nil)
                    loader/load-config-result
                    (let [orig loader/load-config-result]
                      (fn [& args]
                        (let [result (apply orig args)
                              cfg    (:config result)]
                          (if (or (nil? cfg) (false? (get-in cfg [:http :burst :enabled])))
                            result
                            (assoc-in result [:config :http :burst]
                                      (burst/resolved (get-in cfg [:http :burst])))))))]
        (run)))))

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



(defn- config-path-segments [path]
  (mapv (fn [segment]
          (if (str/includes? segment "@")
            (let [[base _] (str/split segment #"@" 2)]
              [(keyword base) :previous])
            [(keyword segment)]))
        (str/split path #"\.")))

(defn- get-path [data path]
  (reduce (fn [current segment]
            (cond
              (nil? current) nil
              (map? current) (or (get current segment)
                                 (get current (name segment)))
              :else nil))
          data
          (mapcat identity (config-path-segments path))))

(defn- config-file-path []
  (str (g/get :root) "/config/isaac.edn"))

(defn- fill-burst-defaults [cfg]
  (if (false? (get-in cfg [:http :burst :enabled]))
    cfg
    (assoc-in cfg [:http :burst] (burst/resolved (get-in cfg [:http :burst])))))

(defn- stamp-loaded-default-crew [cfg]
  (if (or (nil? cfg) (contains? (or (get-in cfg [:defaults :frequencies]) {}) :crew))
    cfg
    (let [crew-id (if (= 1 (count (:crew cfg)))
                    (first (keys (:crew cfg)))
                    "main")]
      (-> cfg
          (assoc-in [:defaults :frequencies :crew] crew-id)
          (update :crew (fn [crew]
                          (let [crew (or crew {})]
                            (if (contains? crew crew-id)
                              crew
                              (assoc crew crew-id {})))))))))

(defn- crew-schema-error? [error]
  (let [k (str (or (:key error) (:path error)))]
    (boolean (re-find #"^defaults\.(crew|frequencies\.crew)" k))))

(defn- load-server-config-result [root fs*]
  (let [load!       #(loader/load-config-result {:root root :fs fs*})
        entity-dir? #(with-server-fs
                       (fn []
                         (seq (fs/children fs* (str root "/config/" %)))))
        result      (load!)
        cfg         (stamp-loaded-default-crew (:config result))]
    (if (and (or (entity-dir? "crew") (entity-dir? "models") (entity-dir? "providers"))
             (empty? (or (:crew cfg) {}))
             (empty? (or (:models cfg) {}))
             (empty? (or (:providers cfg) {})))
      (load!)
      (assoc result :config cfg :errors (vec (remove crew-schema-error? (:errors result)))))))

(defn- load-server-config [root fs*]
  (fill-burst-defaults (:config (load-server-config-result root fs*))))

;; region ----- Setup -----

(defn fixture-ok-handler [_request]
  {:status 200 :headers {"Content-Type" "text/plain"} :body "OK"})

(defn fixture-refuse-401-handler [_request]
  {:status 401 :headers {"Content-Type" "text/plain"} :body "Unauthorized"})

(defn fixture-fine-scope-handler [request]
  (auth/require-scope! request :hail/prompt-override)
  (fixture-ok-handler request))

(defn- overlap-twin-name? [principal-name]
  (str/includes? (str principal-name) "@"))

(defn- persist-principal! [principal-name principal]
  (with-server-fs
    (fn []
      (let [file-path (isaac-file-path "isaac.edn")
            data      (or (isaac-file-data "isaac.edn") {})
            fs*       (server-fs)
            path      (if (overlap-twin-name? principal-name)
                        (let [[base _] (str/split principal-name #"@" 2)]
                          [:http :auth :principals (keyword base) :previous])
                        [:http :auth :principals (keyword principal-name)])]
        (fs/mkdirs fs* (fs/parent file-path))
        (fs/spit fs* file-path
                 (pr-str (assoc-in data path principal)))
        (notify-config-change! file-path)))))

(defn- parse-scopes [scopes]
  (->> (str/split scopes #",")
       (map str/trim)
       (remove str/blank?)
       (map #(if (= "*" %) :* (keyword %)))
       set))

(defn principal-configured
  ([principal-name secret scopes]
   (principal-configured principal-name secret scopes nil))
  ([principal-name secret scopes expires]
   (persist-principal! principal-name
                       (cond-> {:hash (auth/sha256 secret) :scopes (parse-scopes scopes)}
                         expires (assoc :expires expires)))))

(defn oidc-trust-rule-registered []
  (oidc-fixture/register-trust-rule!))

(defn oidc-trust-rule-registered-with-config-refs []
  (oidc-fixture/register-trust-rule-with-config-refs!))

(defn no-oidc-trust-rule-registered []
  (reset! auth/*identity-verifiers* {}))

(defn jwks-stub-serves-issuer-key []
  (oidc-fixture/stub-jwks-serves!))

(defn jwks-stub-is-unreachable []
  (oidc-fixture/stub-jwks-unreachable!))

(defn jwks-stub-misses-then-serves []
  (oidc-fixture/stub-jwks-miss-then-serve!))

(defn signed-jwt-bearer [kind]
  (oidc-fixture/signed-token kind))

(defn jwks-fetch-count-is [n]
  (g/should= (long n) (long (oidc-fixture/jwks-hit-count))))

(defn principal-removed [principal-name]
  (with-server-fs
    (fn []
      (let [file-path (isaac-file-path "isaac.edn")
            data      (or (isaac-file-data "isaac.edn") {})
            fs*       (server-fs)]
        (fs/spit fs* file-path
                 (pr-str (update-in data [:http :auth :principals] dissoc (keyword principal-name))))
        (notify-config-change! file-path)))))

(defn fixture-route
  ([method path]
   (fixture-route method path nil nil))
  ([method path scope]
   (fixture-route method path scope nil))
  ([method path scope handler-scope]
   (g/update! :fixture-routes
              (fnil conj [])
              (cond-> {:method  (keyword (str/lower-case method))
                       :path    path
                       :handler (if handler-scope
                                  'isaac.http.server-steps/fixture-fine-scope-handler
                                  'isaac.http.server-steps/fixture-ok-handler)}
                scope (assoc :scope (keyword scope))))))

(defn fixture-route-refuses-401 [method path]
  (g/update! :fixture-routes
             (fnil conj [])
             {:method  (keyword (str/lower-case method))
              :path    path
              :handler 'isaac.http.server-steps/fixture-refuse-401-handler}))

(defn- deep-merge [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))

(defn stop-server! []
  (app/stop!)
  (lifecycle/reset-hello!))

(g/after-scenario
  (fn []
    (stop-server!)
    (audit/reset-state!)))

(defn config-changed
  "Rewrites isaac.edn keys after startup and notifies the running server's
   change source, so the next request sees the reloaded config."
  [table]
  (fconfig/config-applied table)
  (notify-config-change! (isaac-file-path "isaac.edn")))

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
            fs*       (server-fs)
            stamped   (stamp-fixture-default-crew path (str/trim content))]
        (fs/mkdirs fs* (fs/parent file-path))
        (fs/spit   fs* file-path stamped)
        (notify-config-change! file-path)))))

(declare isaac-config-path-equals)

(defn isaac-config-path-is [path value]
  (if (some? (g/get :exit-code))
    (isaac-config-path-equals path value)
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
            (notify-config-change! file-path)))))))

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
    (reset! auth/*identity-verifiers* {})
    (oidc-fixture/reset-fixture!)
    (when-not (g/get :root)
      (let [home (default-server-home)]
        (clean-real-dir! home)
        (.mkdirs (java.io.File. (str home "/config")))
        (g/assoc! :root home)))))

(declare current-server-config)

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
                             base    (fill-burst-defaults (:config load-result))
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
                         :config-warnings      (:warnings load-result)
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
                         ;; Explicit :http :port in the scenario's config is honored;
                         ;; otherwise bind ephemerally so suites never collide with a
                         ;; live isaac on the default port.
                         :port                 (if run-server?
                                                 (or (get-in cfg-map [:http :port]) 0)
                                                 0)
                         :root            runtime-state
                        :start-http-server?   run-server?}]
    (g/assoc! :runtime-root-dir runtime-state)
    (g/assoc! :server-handler-opts {:cfg-fn current-server-config
                                     :root runtime-state
                                     :home home})
    (let [start! (fn []
                   (server-logging/configure! runtime-state cfg-map)
                   (app/start! start-opts)
                   (doseq [route (g/get :fixture-routes)]
                     (routes/register-route-entry! route))
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
        host (or (get-in (g/get :server-config) [:http :host]) "localhost")
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
    (module-loader/process-manifest-berths! module-index))
  (doseq [route (g/get :fixture-routes)]
    (routes/register-route-entry! route)))

(defn- direct-response [request]
  (let [handler-opts (current-handler-opts)
        cfg          ((:cfg-fn handler-opts))
        fs*          (server-fs)]
    (register-direct-routes! cfg)
    (nexus/-with-nested-nexus {:fs fs* :root (:root handler-opts)}
      ((server-http/create-handler handler-opts) request))))

(defn- use-direct-http? []
  (let [port (g/get :server-port)
        host (or (get-in (current-server-config) [:http :host]) "127.0.0.1")]
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

(defn- capture-printed-secret-from-stdout! []
  (when-not (g/get :printed-secret)
    (let [line (first (str/split-lines (str/trim (or (fcli/current-output) ""))))]
      (when (and (seq line)
                 (<= 32 (count line))
                 (re-matches #"[A-Za-z0-9_-]+" line))
        (g/assoc! :printed-secret line)))))

(defn interpolate-printed-secret [text]
  (capture-printed-secret-from-stdout!)
  (let [secret (g/get :printed-secret)]
    (cond-> (str text)
      secret (str/replace "<the printed secret>" secret))))

(defn get-request-with-header [path header]
  (let [header (interpolate-printed-secret header)
        [name value] (str/split header #":\s*" 2)]
    (send-http :get path {name value})))

(defn client-sends-signed-jwt [path]
  (get-request-with-header path (str "Authorization: Bearer " (oidc-fixture/last-token))))

(defn- as-count [n]
  (if (number? n) (long n) (parse-long (str n))))

(defn client-sends-n-times [method path n]
  (dotimes [_ (as-count n)]
    (send-http method path {})))

(defn client-sends-with-header-n-times [method path header n]
  (let [header (interpolate-printed-secret header)
        [name value] (str/split header #":\s*" 2)]
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

(defn- parse-header-line
  "\"Name: value\" — several headers may be joined with \"; \"."
  [header]
  (into {}
        (map (fn [line] (vec (str/split (str/trim line) #":\s*" 2))))
        (str/split header #";\s*")))

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
        headers (parse-header-line (interpolate-printed-secret header))
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

(defn stdout-has-exactly-n-lines [n]
  (let [output (or (fcli/current-output) "")
        n      (if (string? n) (parse-long n) n)
        lines  (if (str/blank? output) [] (str/split-lines output))]
    (g/should= n (count lines))))

(defn stdout-line-is-bearer-secret [n]
  (let [n      (if (string? n) (parse-long n) n)
        output (str/trim (or (fcli/current-output) ""))
        line   (first (str/split-lines output))]
    (g/should-not-be-nil line)
    (g/should (<= n (count line)))
    (g/should (re-matches #"[A-Za-z0-9_-]+" line))
    (g/assoc! :printed-secret line)))

(defn- loaded-config []
  (with-server-fs
    (fn []
      (:config (loader/load-config-result {:root (or (g/get :root) (g/get :runtime-root-dir))
                                           :fs   (server-fs)})))))

(defn- config-value-at [path]
  (get-path (loaded-config) path))

(defn- present-config-value [value]
  (cond
    (set? value) (str "#{" (->> value (map pr-str) (str/join " ")) "}")
    :else        (str value)))

(defn isaac-config-path-equals [path expected]
  (let [actual (config-value-at path)]
    (g/should-not-be-nil actual)
    (let [presented (present-config-value actual)]
      (if (and (str/starts-with? expected "#{") (str/starts-with? presented "#{"))
        (g/should= (edn/read-string expected) actual)
        (g/should= expected presented)))))

(defn isaac-config-path-matches [path pattern]
  (let [actual (config-value-at path)]
    (g/should-not-be-nil actual)
    (g/should (re-find (re-pattern pattern) (str actual)))))

(defn isaac-config-path-absent [path]
  (g/should-be-nil (config-value-at path)))

(defn config-file-does-not-contain-printed-secret [path]
  (let [secret (or (g/get :printed-secret) "")]
    (g/should (seq secret))
    (let [full-path (if (str/starts-with? path "/")
                      path
                      (str (g/get :root) "/" path))
          fs*       (server-fs)
          content   (with-server-fs
                      (fn []
                        (or (when (fs/exists? fs* full-path)
                              (fs/slurp fs* full-path))
                            "")))]
      (g/should-not (str/includes? content secret)))))

(defn log-has-no-printed-secret []
  (let [secret (or (g/get :printed-secret) "")]
    (g/should (seq secret))
    (doseq [entry (log/get-entries)]
      (g/should-not (str/includes? (pr-str entry) secret)))))

(defn config-reloaded []
  (g/should (map? (current-server-config))))

;; endregion ^^^^^ Log Assertions ^^^^^

;; region ----- Routing -----

(defgiven "an OIDC trust rule for google-pubsub is registered"
  isaac.http.server-steps/oidc-trust-rule-registered
  "Registers a data-shaped :isaac.http/identity rule for the lantern fixture issuer.")

(defgiven "an OIDC trust rule for google-pubsub is registered with config refs"
  isaac.http.server-steps/oidc-trust-rule-registered-with-config-refs
  "Same rule, but :audience and :claims/:email are config paths (lantern.push.endpoint / .service-account).")

(defgiven "no OIDC trust rule is registered by any module"
  isaac.http.server-steps/no-oidc-trust-rule-registered
  "Clears every registered :isaac.http/identity contribution, so only rules
   declared under http.auth.identity are in play.")

(defgiven "the JWKS stub serves the issuer key"
  isaac.http.server-steps/jwks-stub-serves-issuer-key)

(defgiven "the JWKS stub is unreachable"
  isaac.http.server-steps/jwks-stub-is-unreachable)

(defgiven "the JWKS stub misses the kid then serves it"
  isaac.http.server-steps/jwks-stub-misses-then-serves)

(defgiven "a signed JWT bearer of kind {kind:string}"
  isaac.http.server-steps/signed-jwt-bearer)

(defwhen "the client sends GET {path:string} with the signed JWT"
  isaac.http.server-steps/client-sends-signed-jwt)

(defthen "the JWKS stub was fetched {n:int} times"
  isaac.http.server-steps/jwks-fetch-count-is)

(defgiven #"principal \"([^\"]+)\" is configured with secret \"([^\"]+)\" and scopes \"([^\"]+)\"$"
  isaac.http.server-steps/principal-configured)

(defgiven #"principal \"([^\"]+)\" is configured with secret \"([^\"]+)\" and scopes \"([^\"]+)\" expiring \"([^\"]+)\"$"
  isaac.http.server-steps/principal-configured)

(defwhen #"principal \"([^\"]+)\" is removed from config$" isaac.http.server-steps/principal-removed)

(defgiven #"a fixture route (\w+) \"([^\"]+)\" requires scope \"([^\"]+)\"$"
  isaac.http.server-steps/fixture-route)

(defgiven #"a fixture route (\w+) \"([^\"]+)\" declares no scope$"
  isaac.http.server-steps/fixture-route)

(defgiven #"a fixture route (\w+) \"([^\"]+)\" requires scope \"([^\"]+)\" and its handler requires \"([^\"]+)\"$"
  isaac.http.server-steps/fixture-route)

(defgiven #"a fixture route (\w+) \"([^\"]+)\" declares no scope and refuses every request with 401$"
  isaac.http.server-steps/fixture-route-refuses-401)

(defgiven "server config:" isaac.http.server-steps/server-config-applied
  "Applies server harness settings from a key/value table (log.output,
   http.* keys, bind-server-port, in-memory :server-config).")

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

(defwhen "config changes to:" isaac.http.server-steps/config-changed
  "Applies a key/value table to <root>/config/isaac.edn after startup and
   notifies the config change source — the post-start twin of 'config:'.")

(defwhen "the isaac config is reloaded" isaac.http.server-steps/config-reloaded)

(defn clock-advances-seconds [n]
  (let [base (or (g/get :current-time) (java.time.Instant/now))
        next (.plusSeconds base (long (if (string? n) (parse-long n) n)))]
    (g/assoc! :current-time next)))

(defn clock-advances-days [n]
  (let [base (or (g/get :current-time) (java.time.Instant/now))
        next (.plusSeconds base (* 86400 (long (if (string? n) (parse-long n) n))))]
    (g/assoc! :current-time next)))

(defn file-written-exactly [_path n]
  (g/should= (long (if (string? n) (parse-long n) n))
             (audit/last-used-write-count)))

(defn- parse-contains-parts [value]
  (when (str/starts-with? (str/trim (str value)) "contains ")
    (->> (re-seq #"\"([^\"]+)\"" (subs (str/trim value) 9))
         (map second)
         vec)))

(defn newest-file-in-edn-contains [dir-path table]
  (let [expanded (str (or (g/get :runtime-root-dir) (g/get :root)) "/" dir-path)
        fs*      (or (g/get :mem-fs) (fs/real-fs))]
    (nexus/-with-nested-nexus {:fs fs*}
      (let [children (or (fs/children fs* expanded) [])
            newest   (->> children
                          (map (fn [child]
                                 (edn/read-string (fs/slurp fs* (str expanded "/" child)))))
                          (sort-by :created-at)
                          last)]
        (g/should-not-be-nil newest)
        (doseq [row (:rows table)]
          (let [row-map (zipmap (:headers table) row)
                path    (get row-map "path")
                value   (get row-map "value")
                actual  (get newest (keyword path))]
            (if-let [parts (parse-contains-parts value)]
              (doseq [part parts]
                (g/should (str/includes? (str actual) part)))
              (g/should= value actual))))))))

(defn auth-expiry-sweep-runs []
  (let [run! #(audit/sweep-expiring! (current-server-config))]
    (if-let [ct (g/get :current-time)]
      (binding [log-file/*now* ct] (run!))
      (run!))))

(defonce ^:private patched-isaac-file-exists-with?*
  (do
    (alter-var-root #'ffs/isaac-file-exists-with-content
      (fn [orig]
        (fn [path content]
          (if (map? content)
            (do (ffs/isaac-file-exists path)
                (ffs/isaac-file-edn-contains path content))
            (orig path content)))))
    true))

(defn- regex-cell? [s]
  (boolean
    (when (string? s)
      (re-find #"^#\"" (str/trim s)))))

(defn- regex-cell-pattern [s]
  (let [s (str/trim s)
        inner (if (str/ends-with? s "\"")
                (subs s 2 (dec (count s)))
                (subs s 2))]
    (re-pattern inner)))

(defn- rewrite-at-prev-path [path]
  (str/replace (str path) #"\.([^.]+)@prev" ".$1.previous"))

(defonce ^:private patched-config-path-matches?*
  (do
    (when-let [v (try (requiring-resolve 'isaac.config.config-steps/config-path-matches)
                      (catch Exception _ nil))]
      (alter-var-root v
        (fn [orig]
          (fn [path pattern]
            (orig (rewrite-at-prev-path path) pattern)))))
    true))

(defonce ^:private patched-config-file-does-not-contain?*
  (do
    (when-let [v (try (requiring-resolve 'isaac.config.config-steps/config-file-does-not-contain)
                      (catch Exception _ nil))]
      (alter-var-root v
        (fn [orig]
          (fn [path expected]
            (if (= "the printed secret" expected)
              (config-file-does-not-contain-printed-secret path)
              (orig path expected))))))
    true))

(defonce ^:private patched-log-has-no-entries-matching?*
  (do
    (when-let [v (try (requiring-resolve 'isaac.foundation.log-steps/log-entries-dont-match)
                      (catch Exception _ nil))]
      (alter-var-root v
        (fn [orig]
          (fn [table]
            (let [secret (g/get :printed-secret)
                  rows   (mapv (fn [row]
                                 (mapv #(if (and secret (string? %))
                                          (str/replace % "<the printed secret>" secret)
                                          %)
                                       row))
                               (:rows table))]
              (orig (assoc table :rows rows)))))))
    true))

(defonce ^:private patched-stdout-matches?*
  (do
    (alter-var-root #'fcli/stdout-matches
      (fn [orig]
        (fn [table]
          (let [output   (or (fcli/current-output) "")
                patterns (fcli/extract-patterns table)]
            (if (some #(str/starts-with? % "#\"") patterns)
              (doseq [pattern patterns]
                (let [re (if (str/starts-with? pattern "#\"")
                           (regex-cell-pattern pattern)
                           (re-pattern pattern))]
                  (g/should (re-find re output))))
              (orig table))))))
    true))

(defonce ^:private patched-stdout-lines-match?*
  (do
    (alter-var-root #'fcli/stdout-lines-match
      (fn [orig]
        (fn [table]
          (let [header-cells (vec (:headers table))
                row-cells    (mapv first (:rows table))
                cells        (cond
                               (some regex-cell? header-cells) header-cells
                               (seq row-cells)                 row-cells
                               :else                           header-cells)]
            (if (some regex-cell? cells)
              (let [output (or (fcli/current-output) "")
                    lines  (mapv str/trim (str/split-lines output))]
                (doseq [cell cells]
                  (g/should (some (fn [line]
                                    (if (regex-cell? cell)
                                      (re-find (regex-cell-pattern cell) line)
                                      (= (str/trim (fcli/unescape-expected (or cell ""))) line)))
                                  lines))))
              (orig table))))))
    true))

(defwhen "the clock advances {n:int} seconds" isaac.http.server-steps/clock-advances-seconds)
(defwhen "the clock advances {n:int} days" isaac.http.server-steps/clock-advances-days)
(defthen "the file {path:string} was written exactly {n:int} times" isaac.http.server-steps/file-written-exactly)
(defthen "the newest file in {dir:string} EDN contains:" isaac.http.server-steps/newest-file-in-edn-contains)
(defwhen "the auth expiry sweep runs" isaac.http.server-steps/auth-expiry-sweep-runs)



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

(defthen "the stdout has exactly {n:int} line(s)" isaac.http.server-steps/stdout-has-exactly-n-lines
  "Counts captured stdout lines after an in-process isaac run.")

(defthen "the stdout has exactly {n:int} line" isaac.http.server-steps/stdout-has-exactly-n-lines)

(defthen "the stdout line is a bearer secret of at least {n:int} characters"
  isaac.http.server-steps/stdout-line-is-bearer-secret
  "Captures the printed secret as :printed-secret for later substitution.")

(defthen "the config file {path:string} never contains the printed secret"
  isaac.http.server-steps/config-file-does-not-contain-printed-secret)

(defthen "the log has no printed secret" isaac.http.server-steps/log-has-no-printed-secret)

(defthen #"the isaac config path \"([^\"]+)\" is absent" isaac.http.server-steps/isaac-config-path-absent
  "Asserts a dotted config path is missing after a mutation. Rewrites @prev onto :previous.")



;; endregion ^^^^^ Routing ^^^^^
