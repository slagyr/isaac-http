(ns isaac.http.runtime
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [isaac.shell :as shell]))

(def missing-clojure-msg
  "--runtime jvm requires the clojure CLI (brew install clojure)")

(defn babashka? []
  (some? (System/getProperty "babashka.version")))

(defn runtime-name []
  (if (babashka?) "bb" "jvm"))

(defn runtime-version []
  (if (babashka?)
    (System/getProperty "babashka.version")
    (System/getProperty "java.version")))

(defn normalize-runtime [runtime]
  (if (= "jvm" runtime) :jvm :bb))

(defn trampoline? [runtime-kw]
  (and (= :jvm runtime-kw) (babashka?)))

(defn strip-runtime-flag
  "Remove --runtime / --runtime=<value> from server subcommand args."
  [args]
  (loop [args (vec args) acc []]
    (if (empty? args)
      acc
      (let [a (first args)]
        (cond
          (= a "--runtime") (recur (vec (drop 2 args)) acc)
          (str/starts-with? a "--runtime=") (recur (vec (rest args)) acc)
          :else (recur (vec (rest args)) (conj acc a)))))))

(defn- launch-deps-for [opts]
  (let [root-dir (root/default-root opts)
        fs*      (or (:fs opts) (nexus/get :fs) (fs/real-fs))
        config   (:config (loader/load-config-result {:root root-dir :fs fs*}))
        cwd      (System/getProperty "user.dir")]
    (module-loader/config->launch-deps config cwd)))

(defn trampoline-argv
  "Argv vector for `clojure` exec-replace when bb requests a JVM server."
  [opts raw-args]
  (let [root-dir    (root/default-root opts)
        launch-deps (launch-deps-for opts)
        server-args (strip-runtime-flag raw-args)]
    (into ["clojure" "-Sdeps" (pr-str launch-deps) "-M" "-m" "isaac.main"]
          (concat (when (and root-dir (seq (str root-dir)))
                    ["--root" root-dir])
                  ["server"]
                  server-args))))

(defn cmd-available? [cmd]
  (shell/cmd-available? cmd))

(defn exec-trampoline! [argv]
  (apply shell/exec! argv))

(defn maybe-trampoline!
  "When `--runtime jvm` is requested from Babashka, exec-replace into clojure.
   Returns an exit code if trampoline or error handling ran; nil to continue
   in-process."
  [opts raw-args]
  (when (trampoline? (normalize-runtime (:runtime opts)))
    (if-not (cmd-available? "clojure")
      (binding [*out* *err*]
        (println missing-clojure-msg)
        1)
      (do (exec-trampoline! (trampoline-argv opts raw-args))
          0))))