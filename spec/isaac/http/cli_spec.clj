(ns isaac.http.cli-spec
  (:require
    [isaac.cli.registry :as registry]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.log.file :as lfile]
    [isaac.log-viewer :as viewer]
    [isaac.logger :as log]
    [isaac.main :as main]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [isaac.http.app :as app]
    [isaac.http.cli :as sut]
    [isaac.http.runtime :as runtime]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "isaac-http-cli-spec" (make-array java.nio.file.attribute.FileAttribute 0))))

(def config-stub (atom {}))
(def started (atom {}))

(describe "Server command"

  (helper/with-captured-logs)

  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (example)))

  (describe "command registration"

    (it "registers the server command"
      (module-loader/process-manifest-berths! (module-loader/builtin-index))
      (should-not-be-nil (registry/get-command "server"))))

  (describe "run"

    (before (reset! config-stub {})
            (reset! started false))
    (redefs-around [sut/block! (fn [] nil)
                    app/start! (fn [opts] (reset! started opts) {:port (:port opts) :host (:host opts)})
                    loader/load-config-result (fn [& _] @config-stub)])

    (describe "start-log-tail!"

      (it "returns nil when log-path is nil"
        (should-be-nil (#'sut/start-log-tail! nil "/tmp/state" {})))

      (it "resolves a relative log path under root creates the file and forwards tail options"
        (let [base     (temp-dir)
              events   (promise)
              log-path "logs/server.log"
              resolved (str (.getAbsolutePath base) "/" log-path)]
          (with-redefs [viewer/tail! (fn [path opts]
                                       (deliver events [path opts])
                                       nil)]
            (should= resolved
                     (#'sut/start-log-tail! log-path (.getAbsolutePath base) {:zebra true}))
            (should= [resolved {:color?  true
                                :zebra?  true
                                :follow? true
                                :limit   10}]
                     (deref events 1000 ::timeout))
            (should (.exists (java.io.File. resolved))))))

      (it "preserves an absolute path and disables color when requested"
        (let [base     (temp-dir)
              abs-path (str (.getAbsolutePath base) "/server.log")
              events   (promise)]
          (with-redefs [viewer/tail! (fn [path opts]
                                       (deliver events [path opts])
                                       nil)]
            (should= abs-path
                     (#'sut/start-log-tail! abs-path "/ignored" {:no-color true :zebra true}))
            (should= [abs-path {:color?  false
                                :zebra?  true
                                :follow? true
                                :limit   10}]
                     (deref events 1000 ::timeout)))))

      )

    (it "starts the server on the given port"
      (with-out-str (sut/run {:port "4000"}))
      (should= 4000 (:port @started)))

    (it "loads config and passes it to app start as :cfg"
      (swap! config-stub assoc-in [:config :server :auth :token] "s3cr3t")
      (with-out-str (sut/run {}))
      (should= "s3cr3t" (get-in @started [:cfg :server :auth :token])))

    (it "passes generic loader errors to app startup"
      (swap! config-stub assoc :errors [{:key "comms.bigbird"
                                         :value "unknown :type \"unknown-type\""}])
      (with-out-str (sut/run {}))
      (should= (:errors @config-stub) (:config-errors @started)))

    (it "prints the host and port on startup"
      (let [output (with-out-str (sut/run {:port "5000"}))]
        (should (re-find #"5000" output))))

    (it "logs hello before server/started"
      (with-out-str (sut/run {:port "7000" :host "0.0.0.0"}))
      (let [events (filter #(#{:server/hello :server/started} (:event %))
                           @log/captured-logs)]
        (should= 2 (count events))
        (should= :server/hello (:event (first events)))
        (should= :server/started (:event (second events)))))

    (it "logs hello with runtime, root, dev?, and pid"
      (with-out-str (sut/run {:port "7000"}))
      (let [hello (first (filter #(= :server/hello (:event %)) @log/captured-logs))]
        (should-not-be-nil hello)
        (should (string? (:version hello)))
        (should= (runtime/runtime-name) (:runtime hello))
        (should (string? (:root hello)))
        (should= false (:dev hello))
        (should (number? (:pid hello)))))

    (it "logs server/started with host and port"
      (with-out-str (sut/run {:port "7000" :host "0.0.0.0"}))
      (let [started (first (filter #(= :server/started (:event %)) @log/captured-logs))]
        (should-not-be-nil started)
        (should= 7000 (:port started))
        (should= "0.0.0.0" (:host started))))

    (it "enables dev mode from the ISAAC_DEV env var"
      (config/set-env-override! "ISAAC_DEV" "true")
      (try
        (with-out-str (sut/run {}))
        (finally (config/clear-env-overrides!)))
      (should= true (:dev @started)))

    (it "CLI --dev enables dev mode"
      (with-out-str (sut/run {:dev true}))
      (should= true (:dev @started)))

    (it "CLI --dev false overrides the ISAAC_DEV env var"
      (config/set-env-override! "ISAAC_DEV" "true")
      (try
        (with-out-str (sut/run {:dev false}))
        (finally (config/clear-env-overrides!)))
      (should= false (:dev @started)))

    (it "derives root from home before starting the app"
      (with-out-str (sut/run {:home "/tmp/server-home"}))
      (should= "/tmp/server-home/.isaac" (:root @started))
      (should-not (contains? @started :home)))

    (it "tails the durable server log when --logs is requested"
      (let [tailed-path (atom nil)]
        (with-redefs [sut/start-log-tail! (fn [path root opts]
                                            (reset! tailed-path [path root opts])
                                            path)]
          (with-out-str (sut/run {:home "/tmp/server-home" :logs true :zebra true})))
        (should= ["/tmp/server-home/.isaac/logs/server.log"
                  "/tmp/server-home/.isaac"
                  {:home "/tmp/server-home" :logs true :zebra true}]
                 @tailed-path)
        (should= "/tmp/server-home/.isaac" (:root @started))))

    )

  (describe "run-fn"

    (it "prints command help and returns 0 when --help is requested"
      (with-redefs [sut/parse-option-map  (fn [_] {:options {:help true} :errors []})
                    registry/get-command  (fn [_] {:name "server"})
                    registry/command-help (fn [_] "server help")]
        (let [output (with-out-str (should= 0 (sut/run-fn {:_raw-args ["--help"]})))]
          (should (re-find #"server help" output)))))

    (it "prints parse errors and returns 1"
      (with-redefs [sut/parse-option-map (fn [_] {:options {} :errors ["bad arg"]})]
        (let [output (with-out-str (should= 1 (sut/run-fn {:_raw-args ["--bogus"]})))]
          (should (re-find #"bad arg" output)))))

    (it "delegates to run with parsed options merged into opts"
      (let [captured (atom nil)]
        (with-redefs [sut/parse-option-map (fn [_] {:options {:port "4000" :host "127.0.0.1"} :errors []})
                      sut/run              (fn [opts]
                                             (reset! captured opts)
                                             0)]
          (should= 0 (sut/run-fn {:_raw-args ["--port" "4000"] :home "/tmp/server-home"}))
          (should= {:home "/tmp/server-home" :port "4000" :host "127.0.0.1"} @captured)))))

  )
