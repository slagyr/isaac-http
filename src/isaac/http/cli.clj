;; mutation-tested: 2026-05-06
(ns isaac.http.cli
  (:require
    [isaac.cli.api :as cli-api]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.common :as cli-common]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.log-viewer :as viewer]
    [isaac.log.file :as log-file]
    [isaac.logger :as log]
    [isaac.http.auth-cli :as auth-cli]
    [isaac.http.logging :as server-logging]
    [isaac.nexus :as nexus]
    [isaac.http.app :as app]
    [isaac.http.audit :as audit]
    [isaac.http.auth :as auth]
    [isaac.http.lifecycle :as lifecycle]
    [isaac.http.runtime :as runtime]
    [isaac.runner.cli :as runner-cli]
    ))

(defonce ^:private shutdown-hook-registered? (atom false))

(defn- register-shutdown-hook! []
  (when (compare-and-set! shutdown-hook-registered? false true)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(app/stop!) "isaac-http-shutdown"))))

(defn block!
  "Block the current thread until interrupted."
  []
  @(promise))

(def ^:private server-log-prelude-limit 10)

(defn- start-log-tail! [log-path root {:keys [no-color zebra]}]
  (let [color? (not no-color)
        zebra? (boolean zebra)
        path   (cond
                 (nil? log-path)                         nil
                 (str/starts-with? log-path "/")         log-path
                 (and root (seq root))         (str root "/" log-path)
                 :else                                   log-path)]
    (when path
      (let [f (java.io.File. path)]
        (.mkdirs (or (.getParentFile f) (java.io.File. ".")))
        (when-not (.exists f) (.createNewFile f)))
      (future (viewer/tail! path {:color?  color?
                                  :zebra?  zebra?
                                  :follow? true
                                  :limit   server-log-prelude-limit}))
      path)))

(defn run [{:keys [port host logs] :as opts}]
  (let [root-dir      (root/default-root opts)
        fs*           (or (:fs opts) (nexus/get :fs) (fs/real-fs))
        ;; CLIs load config at their entry point (never reload — that's a
        ;; server-only concern); app/start! resolves port/host and commits it.
        load-result   (loader/load-config-result {:root root-dir :fs fs*})
        loaded-config (:config load-result)
        ;; dev mode is an environment/launch concern, not config: --dev overrides,
        ;; otherwise the ISAAC_DEV env var.
        dev?          (if (contains? opts :dev)
                        (boolean (:dev opts))
                        (= "true" (loader/env "ISAAC_DEV")))]
    (server-logging/configure! root-dir loaded-config)
    (when logs
      (start-log-tail! (log-file/server-log-path root-dir) root-dir opts))
    (nexus/-with-nested-nexus {:fs fs*}
      (nexus/init! {:fs fs*})
      (lifecycle/reset-hello!)
      (lifecycle/emit-hello! root-dir dev?)
      (if-let [{started-port :port started-host :host}
               (app/start! {:cfg           loaded-config
                            :config-errors (:errors load-result)
                            :root          root-dir
                            :dev           dev?
                            :port          (when port (parse-long (str port)))
                            :host          host})]
        (do
          (when dev?
            (log/info :server/dev-mode-enabled :host started-host :port started-port))
          (println (str "Isaac server running on " started-host ":" started-port))
          (register-shutdown-hook!)
          (block!))
        (do
          (println "Failed to start: invalid configuration (see logs)")
          1)))))

(def option-spec
  [["-p" "--port N" "Port to listen on (default: 6674)"]
   ["-H" "--host H" "Host to bind to (default: 127.0.0.1)"]
   ["-d" "--dev" "Enable development reload mode"]
   [nil  "--runtime RUNTIME" "Server runtime: bb (default) or jvm"
    :default "bb"]
   [nil  "--logs" "Tail and print the log file while the server runs"]
   [nil  "--no-color" "Disable color output for --logs"]
   [nil  "--zebra" "Enable zebra striping for --logs"]
   ["-h" "--help" "Show help"]])

(defn- parse-option-map [raw-args]
  (let [raw-args (vec (take-while #(not= "auth" %) raw-args))
        {:keys [options errors]} (tools-cli/parse-opts raw-args option-spec)]
    {:options (->> options
                   (remove (comp nil? val))
                   (into {}))
     :errors  errors}))

(def auth-subcommands
  [{:name "auth mint"   :summary "Mint a principal secret (printed once)"}
   {:name "auth rotate" :summary "Replace a principal secret"}
   {:name "auth revoke" :summary "Remove a principal"}
   {:name "auth list"   :summary "List principals (never secrets)"}])

(def ^:private auth-mint-spec
  [[nil "--scopes SCOPES" "Comma-separated scopes (required)"]
   [nil "--expires DATE" "ISO date YYYY-MM-DD"]
   ["-h" "--help" "Show help"]])

(def ^:private auth-rotate-spec
  [[nil "--overlap WINDOW" "Keep the previous secret valid (e.g. 24h)"]
   ["-h" "--help" "Show help"]])

(defn- auth-root [opts]
  (or (:root opts) (root/default-root opts)))

(defn- print-secret-or-error! [{:keys [exit secret error]}]
  (if (zero? (or exit 1))
    (do (when (seq secret)
          (print secret)
          (flush)
          (println))
        0)
    (do (binding [*out* *err*] (println error)) (or exit 1))))

(defn- run-auth-mint [opts args]
  (let [{:keys [arguments options errors]} (tools-cli/parse-opts args auth-mint-spec)]
    (cond
      (:help options) (do (println "Usage: isaac server auth mint <name> --scopes a,b[,…] [--expires YYYY-MM-DD]\nThe secret is printed once on stdout and never written to config.") 0)
      (seq errors)    (do (doseq [e errors] (binding [*out* *err*] (println e))) 1)
      (empty? arguments) (do (binding [*out* *err*] (println "missing principal name")) 1)
      :else (print-secret-or-error! (auth-cli/mint! (auth-root opts) (first arguments) options)))))

(defn- run-auth-rotate [opts args]
  (let [{:keys [arguments options errors]} (tools-cli/parse-opts args auth-rotate-spec)]
    (cond
      (:help options) (do (println "Usage: isaac server auth rotate <name> [--overlap 24h]") 0)
      (seq errors)    (do (doseq [e errors] (binding [*out* *err*] (println e))) 1)
      (empty? arguments) (do (binding [*out* *err*] (println "missing principal name")) 1)
      :else (print-secret-or-error! (auth-cli/rotate! (auth-root opts) (first arguments) options)))))

(defn- run-auth-revoke [opts args]
  (let [name (first args)]
    (if (str/blank? name)
      (do (binding [*out* *err*] (println "missing principal name")) 1)
      (print-secret-or-error! (auth-cli/revoke! (auth-root opts) name)))))

(defn- format-scopes [scopes]
  (->> scopes
       (map #(if (keyword? %) (subs (str %) 1) (str %)))
       (str/join ",")))

(defn- format-last-used [last-used name]
  (or (get last-used name)
      (get last-used (keyword name))
      "-"))

(defn auth-list!
  "Print principals and last-used timestamps."
  [root]
  (let [root      (or root (nexus/get :root) (root/default-root {}))
        fs*       (or (nexus/get :fs) (fs/instance) (fs/real-fs))
        cfg       (or (:config (loader/load-config-result {:root root :fs fs*})) {})
        last-used (audit/read-last-used root)]
    (doseq [[name principal] (sort-by (comp str first) (auth/principals cfg))]
      (let [n (if (keyword? name) (clojure.core/name name) (str name))]
        (println (format "%s  %s  -  %s"
                         n
                         (format-scopes (:scopes principal))
                         (format-last-used last-used n)))))))

(defn- run-auth-list [opts]
  (let [root (auth-root opts)
        rows (auth-cli/list-rows (loader/load-config-result {:root root}))]
    (doseq [{:keys [name scopes expires last-used]} rows]
      (println (format "%s  %s  %s  %s" name scopes expires last-used)))
    (when (empty? rows)
      (auth-list! root)))
  0)

(defn- auth-help []
  (str "Usage: isaac server auth <mint|rotate|revoke|list> [options]\n"
       "Mint, rotate, revoke, or list HTTP auth principals.\n"
       "The secret is printed once on stdout (mint/rotate) and never written to config.\n"
       "Subcommands:\n"
       "  mint <name> --scopes a,b[,…] [--expires YYYY-MM-DD]  Mint a principal secret (printed once)\n"
       "  rotate <name> [--overlap 24h]                        Replace a principal secret\n"
       "  revoke <name>                                        Remove a principal\n"
       "  list                                                 List principals (never secrets)"))

(defn- run-auth [opts args]
  (let [cmd (first args)
        rest-args (rest args)]
    (case cmd
      "mint"   (run-auth-mint opts rest-args)
      "rotate" (run-auth-rotate opts rest-args)
      "revoke" (run-auth-revoke opts rest-args)
      "list"   (run-auth-list opts)
      (nil "--help" "-h") (do (println (auth-help)) 0)
      (do (binding [*out* *err*] (println (str "Unknown auth subcommand: " cmd))) 1))))

(defn- dispatch-run [opts raw-args]
  (cond
    (= "auth" (first raw-args))
    (run-auth opts (rest raw-args))

    :else
    (if-let [exit (runtime/maybe-trampoline! opts raw-args)]
      exit
      (run opts))))

(defn run-fn [opts]
  (let [raw-args (or (:_raw-args opts) [])]
    (if (= "auth" (first raw-args))
      (run-auth opts (rest raw-args))
      (cli-common/standard-run-fn "server" parse-option-map
        (fn [merged] (dispatch-run merged raw-args))
        opts))))

(defonce ^:private wrap-runner-auth-list!
  (do
    (alter-var-root #'runner-cli/run-fn
                    (fn [original]
                      (fn [opts]
                        (let [raw-args (or (:_raw-args opts) [])]
                          (if (= "auth" (first raw-args))
                            (run-fn opts)
                            (original opts))))))
    true))

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :server [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :server [_id]
  option-spec)

(defmethod cli-api/subcommands :server [_id]
  auth-subcommands)
