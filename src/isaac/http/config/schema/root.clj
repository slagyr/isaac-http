(ns isaac.http.config.schema.root
  "Views over :isaac.config/schema contributions gathered from every
   builtin manifest on the classpath."
  (:require
    [c3kit.apron.schema :as schema]
    [c3kit.apron.schema.path :as path]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.foundation.config.schema-base :as schema-base]))

(def ->id schema-base/->id)
(def schema-fields schema-base/schema-fields)
(def strip-validation-annotations schema-base/strip-validation-annotations)

(defn- classpath-manifests []
  (->> (enumeration-seq (.getResources (.getContextClassLoader (Thread/currentThread))
                                       "isaac-manifest.edn"))
       (map (fn [url] (edn/read-string (slurp url))))))

(def contributions
  "Merged :isaac.config/schema contributions from every builtin manifest."
  (apply merge (keep :isaac.config/schema (classpath-manifests))))

(defn- table [config-key]
  (get-in contributions [config-key :schema]))

;; region ----- Entity Schemas (manifest views) -----

(def defaults (table :defaults))
(def server (table :server))
(def sessions (table :sessions))
(def hail (table :hail))
(def hooks (table :hooks))
(def slash-commands (table :slash-commands))

(def field-comms (table :comms))
(def field-crew (table :crew))
(def field-models (table :models))
(def field-providers (table :providers))
(def field-cron (table :cron))
(def field-tools (table :tools))
(def field-tz (table :tz))
(def field-prompt-dir-names (table :prompt-dir-names))
(def field-prompt-paths (table :prompt-paths))
(def field-prefer-entity-files (table :prefer-entity-files))
(def field-command-paths (table :command-paths))
(def field-skill-paths (table :skill-paths))
(def field-skill-menu-threshold (table :skill-menu-threshold))

(def crew (:value-spec field-crew))
(def model (:value-spec field-models))
(def provider (:value-spec field-providers))
(def comm-instance (:value-spec field-comms))
(def hail-band (:value-spec hail))
(def slash-command (:value-spec slash-commands))
(def cron-job (:value-spec field-cron))
(def hook (:value-spec hooks))
(def hook-auth (get-in hooks [:schema :auth]))
(def tools (get-in crew [:schema :tools]))

(def root
  (assoc schema-base/base-root :schema
         (merge (schema-fields schema-base/base-root)
                (update-vals contributions :schema))))

;; endregion ^^^^^ Entity Schemas ^^^^^

;; region ----- Schema Registry -----

(defn clear-schemas!
  "No-op retained for tests that reset ambient config schema state."
  []
  nil)

(def ^:private entity-collections #{:crew :hail :models :providers})

(defn- normalize-template-path [path-str]
  (let [segments (path/parse path-str)]
    (when (seq segments)
      (path/unparse
        (map (fn [segment]
               (if (and (= :key (first segment)) (= :value (second segment)))
                 [:key :value]
                 segment))
             segments)))))

(defn- normalize-data-path [path-str]
  (let [segments (path/parse path-str)]
    (when (seq segments)
      (path/unparse
        (map-indexed (fn [idx segment]
                       (if (and (= 1 idx)
                                (contains? entity-collections (second (first segments)))
                                (#{:key :str} (first segment)))
                         [:key :value]
                         segment))
                     segments)))))

(defn- parent-path-and-key-suffix [path-str]
  (let [suffix ".key"]
    (when (and path-str (str/ends-with? path-str suffix) (> (count path-str) (count suffix)))
      (subs path-str 0 (- (count path-str) (count suffix))))))

(defn schema-for-path [path-str]
  (cond
    (or (nil? path-str) (str/blank? path-str))
    root

    :else
    (try
      (or (path/schema-at root path-str)
          (when-let [normalized (normalize-template-path path-str)]
            (path/schema-at root normalized))
          (when-let [parent-path (parent-path-and-key-suffix path-str)]
            (:key-spec (schema-for-path parent-path))))
      (catch Exception _ nil))))

(defn schema-for-data-path [path-str]
  (try
    (or (schema-for-path path-str)
        (when-let [normalized (normalize-data-path path-str)]
          (path/schema-at root normalized)))
    (catch Exception _ nil)))

;; endregion ^^^^^ Schema Registry ^^^^^