(ns isaac.config.configurator-spec
  (:require
    [isaac.config.berths :as berths]
    [isaac.config.schema.root :as schema]
    [isaac.config.configurator :as sut]
    [isaac.config.validation-lexicon :as vlex]
    [isaac.logger :as log]
    [isaac.fs :as fs]
    [isaac.module.loader :as module-loader]
    [isaac.http.component.runtime :as server-runtime]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "configurator"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (example)))

  (describe "component reconciliation"

    (defn- recording-component []
      (let [events (atom [])]
        [events (reify sut/Reconfigurable
                  (on-load [_ slice] (swap! events conj [:loaded slice]))
                  (on-unload [_ slice] (swap! events conj [:unloaded slice]))
                  (on-config-change! [_ old new] (swap! events conj [:changed old new])))]))

    (it "starts a component when its slice appears"
      (let [[events instance] (recording-component)
            registry {:kind :component :path [:cron] :impl "cron" :factory (fn [_] instance)}]
        (log/capture-logs
          (sut/reconcile! {} nil {:cron {:nightly {:expr "0 0 * * *"}}} registry)
          (should= instance (nexus/get-in [:cron]))
          (should= [[:loaded {:nightly {:expr "0 0 * * *"}}]] @events)
          (should (some #(= :lifecycle/started (:event %)) @log/captured-logs)))))

    (it "delivers on-config-change! when the slice changes"
      (let [[events instance] (recording-component)
            registry {:kind :component :path [:cron] :impl "cron" :factory (fn [_] instance)}]
        (sut/reconcile! {} nil {:cron {:a {}}} registry)
        (sut/reconcile! {} {:cron {:a {}}} {:cron {:a {} :b {}}} registry)
        (should= [:changed {:a {}} {:a {} :b {}}] (last @events))))

    (it "stops a component when its slice is removed"
      (let [[events instance] (recording-component)
            registry {:kind :component :path [:cron] :impl "cron" :factory (fn [_] instance)}]
        (sut/reconcile! {} nil {:cron {:a {}}} registry)
        (sut/reconcile! {} {:cron {:a {}}} {} registry)
        (should-be-nil (nexus/get-in [:cron]))
        (should= [:unloaded {:a {}}] (last @events))))

    (it "ignores non-component registries — slot trees belong to the berth engine"
      (let [registry {:kind :slot-tree :path [:comms]}]
        (sut/reconcile! {} nil {:comms {:bert {:type :telly}}} registry)
        (should-be-nil (nexus/get-in [:comms :bert])))))

  (describe "schema ownership"

    (defn- owned-paths []
      ;; isaac-mdj2: isaac-agent now contributes a :crew-exists? existence
      ;; ref (its :comms.crew field validates against it); berths/config-paths
      ;; meta-validates every module's schema against apron's lexicon, so the
      ;; ref must be registered first — schema-compose does this internally
      ;; on its own path, but this spec walks berths/config-paths directly.
      (let [module-index (module-loader/builtin-index)]
        (vlex/register-contributed-existence-refs! module-index)
        (into (->> (server-runtime/-registries) (map :path) set)
              (berths/config-paths module-index))))

    (defn- entity-collection-entry? [[_ entry]]
      (and (= :map (:type entry))
           (:key-spec entry)
           (:value-spec entry)
           (let [value-spec (:value-spec entry)]
             (and (= :map (:type value-spec))
                  (or (:name value-spec)
                      (seq (:schema value-spec)))))))

    ;; isaac-mdj2: :modules (foundation) and :resource-pools (isaac-agent)
    ;; both now shape up as entity collections under this check's definition,
    ;; but neither is owned the way this spec means "owned" — :modules is
    ;; resolved at classpath-composition time, before any component
    ;; reconciles, and :resource-pools' lifecycle lives inside isaac-agent's
    ;; own leasing code, not an :isaac.config/component this repo's
    ;; -registries would see. Neither is isaac-http's to mark
    ;; :snapshot-only? — that flag lives on the owning module's own schema
    ;; entry. Exempt them here rather than papering over the gap upstream.
    (def ^:private known-unowned-by-design #{:modules :resource-pools})

    (it "every config-driven entity collection has a lifecycle owner or is marked snapshot-only"
      (let [owned-paths (owned-paths)
            unowned     (for [[key entry] (filter entity-collection-entry? (:schema schema/root))
                              :when (not (or (contains? owned-paths [key])
                                             (:snapshot-only? entry)
                                             (contains? known-unowned-by-design key)))]
                          (str "key `" key "` has no owner — register a Reconfigurable for `["
                               key "]` or add `:snapshot-only? true` to its schema entry"))]
        (should= [] (vec unowned))))

    (it "marks crew models and providers as snapshot-only"
      (should= true (get-in schema/root [:schema :crew :snapshot-only?]))
      (should= true (get-in schema/root [:schema :models :snapshot-only?]))
      (should= true (get-in schema/root [:schema :providers :snapshot-only?])))))