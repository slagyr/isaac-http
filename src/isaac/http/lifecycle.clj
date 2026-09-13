(ns isaac.http.lifecycle
  (:require
    [isaac.foundation.version :as version]
    [isaac.logger :as log]
    [isaac.http.runtime :as runtime])
  (:import
    (java.lang ProcessHandle)))

(defonce ^:private hello-emitted? (atom false))

(defn reset-hello! []
  (reset! hello-emitted? false))

(defn hello-context [root dev?]
  {:version         (version/version-string)
   :runtime         (runtime/runtime-name)
   :runtime-version (runtime/runtime-version)
   :root            root
   :dev             dev?
   :pid             (.pid (ProcessHandle/current))})

(defn- print-hello-banner! [{:keys [version runtime runtime-version root dev? pid]}]
  (println (str version " — hello"))
  (println (str "  runtime   "
                runtime " ("
                (if (= "bb" runtime)
                  (str "babashka " runtime-version)
                  (str "java " runtime-version))
                ")"))
  (println (str "  root      " root))
  (println (str "  dev mode  " (if dev? "on" "off")))
  (println (str "  pid       " pid)))

(defn emit-hello!
  "Log and print the server hello bookend once per process boot attempt."
  [root dev?]
  (when (compare-and-set! hello-emitted? false true)
    (let [ctx (hello-context root dev?)]
      (log/info :server/hello
                :version         (:version ctx)
                :runtime         (:runtime ctx)
                :runtime-version (:runtime-version ctx)
                :root            (:root ctx)
                :dev             (:dev ctx)
                :pid             (:pid ctx))
      (print-hello-banner! ctx))))