(ns isaac.configurator-steps
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.comm.registry :as comm-registry]
    [isaac.config.berths :as berths]
    [isaac.config.loader :as loader]
    [isaac.config.runtime :as runtime]
    [isaac.http.component.runtime :as server-runtime]
    [isaac.reconfigurable :as reconfigurable]
    [isaac.logger :as log]
    [isaac.foundation.root-steps :as froot]
    [isaac.fs :as fs]
    [isaac.module.loader :as module-loader]
    [isaac.http.app :as app]
    [isaac.spec-helper :as helper]
    [isaac.nexus :as nexus]))

(helper! isaac.configurator-steps)

(def ^:private test-comm-module-id :isaac.http.test-comm)
(def ^:private test-comm-module-coord {:local/root "spec-support"})

(defn- ->slot-key [name]
  (keyword name))

(defn- live-instance [slot-name]
  (nexus/get-in [:comms (->slot-key slot-name)]))

(defn- parse-state-value [value]
  (cond
    (re-matches #"-?\d+" value)         (parse-long value)
    (= "true" (str/lower-case value))   true
    (= "false" (str/lower-case value))  false
    (str/starts-with? value "[")        (edn/read-string value)
    (str/starts-with? value "{")        (edn/read-string value)
    (str/starts-with? value ":")        (edn/read-string value)
    (str/starts-with? value "\"")       (edn/read-string value)
    :else                                value))

(defn- read-state [instance]
  (let [test-comm? (requiring-resolve 'isaac.http.test-comm/test-comm?)
        state      (requiring-resolve 'isaac.http.test-comm/state)]
    (cond
      (test-comm? instance)        (state instance)
      (some-> (:state* instance))  @(:state* instance)
      (map? instance)              instance
      :else                        {})))

(defn- get-by-dotted-path [m path]
  (let [keys (mapv keyword (str/split path #"\."))]
    (get-in m keys)))

(defn- isaac-edn-path []
  (str (or (g/get :runtime-root-dir) (g/get :root)) "/config/isaac.edn"))

(defn- server-fs []
  (or (g/get :mem-fs) (nexus/get :fs)))

(defn- with-server-fs [f]
  (if-let [mem (g/get :mem-fs)]
    (nexus/-with-nested-nexus {:fs mem} (f))
    (f)))

(defn- persist-test-comm-module! []
  (with-server-fs
    (fn []
      (let [path    (isaac-edn-path)
            fs*     (server-fs)
            current (if (fs/exists? fs* path) (edn/read-string (fs/slurp fs* path)) {})]
        (fs/mkdirs fs* (fs/parent path))
        (fs/spit fs* path (pr-str (assoc-in current [:modules test-comm-module-id] test-comm-module-coord)))))))

(defn comm-is-registered [impl]
  (let [ns-sym       'isaac.http.test-comm
        _            (require ns-sym)
        make-factory (requiring-resolve 'isaac.http.test-comm/make)]
    (g/update! :server-config #(assoc-in (or % {}) [:modules test-comm-module-id] test-comm-module-coord))
    (persist-test-comm-module!)
    (comm-registry/register-factory! impl make-factory))
  (g/should (comm-registry/registered? impl)))

(defn- expectations-met? [name table]
  (when-let [instance (live-instance name)]
    (let [state (read-state instance)]
      (every? (fn [row]
                (let [row-map  (zipmap (:headers table) row)
                      path     (get row-map "path")
                      expected (parse-state-value (get row-map "value"))]
                  (= expected (get-by-dotted-path state path))))
              (:rows table)))))

(defn comm-exists-with-state [name table]
  (helper/await-condition #(expectations-met? name table) 5000)
  (let [instance (live-instance name)]
    (g/should-not-be-nil instance)
    (let [state (read-state instance)]
      (doseq [row (:rows table)]
        (let [row-map  (zipmap (:headers table) row)
              path     (get row-map "path")
              expected (parse-state-value (get row-map "value"))
              actual   (get-by-dotted-path state path)]
          (g/should= expected actual))))))

(defn comm-does-not-exist [name]
  (helper/await-condition #(nil? (live-instance name)) 5000)
  (g/should-be-nil (live-instance name)))

(defn- write-grover-defaults! [root]
  (let [cfg-root (str root "/config")
        fs*      (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))]
    (fs/mkdirs fs* cfg-root)
    (fs/mkdirs fs* (str cfg-root "/models"))
    (fs/mkdirs fs* (str cfg-root "/providers"))
    (fs/mkdirs fs* (str cfg-root "/crew"))
    (fs/spit   fs* (str cfg-root "/isaac.edn")
                    (pr-str {:defaults {:frequencies {:crew "main"} :crew {:model "grover"}}}))
    (fs/spit   fs* (str cfg-root "/models/grover.edn")
                    (pr-str {:model "echo" :provider :grover :context-window 32768}))
    (fs/spit   fs* (str cfg-root "/providers/grover.edn") (pr-str {}))
    (fs/spit   fs* (str cfg-root "/crew/main.edn")
                    (pr-str {:model :grover :soul "You are Atticus."}))))

(defn default-grover-setup []
  ;; Grover is a config-driven test provider (write-grover-defaults! below
  ;; writes models/grover.edn :provider :grover); isaac.llm.api.grover is not on
  ;; isaac-http's test classpath, and install-test-fixture! was removed
  ;; upstream anyway. These server tests exercise comm/reconcile, not the LLM.
  (froot/initialize-root! "target/test-state" true)
  (write-grover-defaults! (g/get :root))
  (g/update! :server-config #(merge (or % {}) {:hot-reload true})))

(defn- deep-merge [a b]
  (cond
    (and (map? a) (map? b)) (merge-with deep-merge a b)
    (some? b)                b
    :else                    a))

(defn- read-current-cfg []
  (let [path    (isaac-edn-path)
        fs*     (server-fs)
        on-disk (when (fs/exists? fs* path)
                  (try (edn/read-string (fs/slurp fs* path))
                       (catch Exception _ nil)))
        in-mem  (g/get :server-config)]
    (deep-merge (or on-disk {}) (or in-mem {}))))

(defn- key-variants [k]
  (let [kw (cond
             (keyword? k) k
             (string? k)  (keyword k)
             :else        (keyword k))]
    [kw (name kw)]))

(defn- dissoc-keys [m k]
  (reduce dissoc m (key-variants k)))

(defn- dissoc-in [m path]
  (cond
    (empty? path) m
    (= 1 (count path)) (dissoc-keys m (first path))
    :else
    (let [parent-path (vec (butlast path))
          leaf        (last path)
          parent      (get-in m parent-path)]
      (if (map? parent)
        (assoc-in m parent-path (dissoc-keys parent leaf))
        m))))

(defn- apply-update [cfg path-str value-str]
  (let [keys (mapv keyword (str/split path-str #"\."))]
    (if (= "#delete" (str/trim (str value-str)))
      (dissoc-in cfg keys)
      (assoc-in cfg keys
                (cond
                  (re-matches #"-?\d+" value-str) (parse-long value-str)
                  (= "true" (str/lower-case value-str)) true
                  (= "false" (str/lower-case value-str)) false
                  (or (str/starts-with? value-str "[")
                      (str/starts-with? value-str "{")
                      (str/starts-with? value-str ":")
                      (str/starts-with? value-str "\""))
                  (edn/read-string value-str)
                  :else value-str)))))

(defn- notify-change! [path]
  (when-let [source (g/get :config-change-source)]
    (runtime/notify-path! source path)))

(defn- norm-slot-ids [comms-map]
  (set (map (comp keyword name) (keys (or comms-map {})))))

(defn- evict-removed-comms! [old-config new-cfg]
  (let [removed (clojure.set/difference (norm-slot-ids (:comms old-config))
                                          (norm-slot-ids (:comms new-cfg)))]

    (doseq [slot removed
            :let [path      [:comms slot]
                  inst      (nexus/get-in path)
                  old-slice (or (get-in old-config [:comms slot])
                                (get-in old-config [:comms (name slot)]))
                  impl      (or (:type old-slice) (get old-slice "type") slot)]]
      (when inst
        (when (satisfies? reconfigurable/Reconfigurable inst)
          (reconfigurable/on-unload inst old-slice))
        (nexus/deregister! path)
        (log/info :lifecycle/stopped :path (str "comms." (name slot)) :impl (name impl))))))

(defn- reload-running-server! [path old-config]
  (when (app/running?)
    (when-let [{:keys [comm-registry host registries]} (server-runtime/running-state)]
      (let [root (or (g/get :runtime-root-dir) (g/get :root))
            fs*  (server-fs)
            rel  (runtime/reload! {:root           root
                                   :fs             fs*
                                   :old-config     old-config
                                   :comm-registry  comm-registry
                                   :registries     registries
                                   :host           host
                                   :path           path})]
        (g/should (some? rel))))))

(defn config-updated [table]
  (let [path       (isaac-edn-path)
        snapshot   (when (app/running?)
                     (loader/snapshot "configurator-steps reload: prior config"))
        old-config (when snapshot (edn/read-string (pr-str snapshot)))
        base-cfg   (if snapshot
                     (edn/read-string (pr-str snapshot))
                     (read-current-cfg))
        cfg        (reduce (fn [acc row]
                             (let [row-map (zipmap (:headers table) row)
                                   p       (get row-map "path")
                                   v       (get row-map "value")]
                               (apply-update acc p v)))
                           base-cfg
                           (:rows table))
        fs*        (server-fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit   fs* path (pr-str cfg))
    (g/update! :server-config (constantly cfg))
    (if (app/running?)
      (reload-running-server! path old-config)
      (notify-change! path))))

(defn server-not-running []
  (g/should-not (app/running?)))

(defgiven "default Grover setup" isaac.configurator-steps/default-grover-setup
  "In-memory Isaac root at target/test-state with hot-reload enabled for
   comm lifecycle scenarios.")

(defgiven "the {impl:string} comm is registered" isaac.configurator-steps/comm-is-registered
  "Loads the comm impl namespace and registers its factory in the comm registry.")

(defthen "the comm {name:string} exists with state:" isaac.configurator-steps/comm-exists-with-state)

(defthen "the comm {name:string} does not exist" isaac.configurator-steps/comm-does-not-exist)

(defwhen "config is updated:" isaac.configurator-steps/config-updated
  "Delta-merges path/value rows into config/isaac.edn and notifies the
   bound config change source.")

(defthen "the Isaac server is not running" isaac.configurator-steps/server-not-running)