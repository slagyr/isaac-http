(ns isaac.http.test-store
  "Minimal in-memory SessionStore for server-only specs — no agent dep."
  (:require
    [isaac.session.store.spi :as store]))

(defn create-store [_root]
  (let [sessions* (atom {})]
    (reify store/SessionStore
      (open-session! [_ name opts]
        (swap! sessions* assoc name (merge {:name name} opts))
        name)
      (delete-session! [_ name]
        (swap! sessions* dissoc name))
      (list-sessions [_]
        (vals @sessions*))
      (list-sessions-by-agent [_ _agent]
        [])
      (most-recent-session [_]
        (first (vals @sessions*)))
      (get-session [_ name]
        (get @sessions* name))
      (get-transcript [_ _name]
        [])
      (active-transcript [_ _name]
        [])
      (update-session! [_ name updates]
        (swap! sessions* update name merge updates))
      (append-message! [_ _name _message]
        nil)
      (append-error! [_ _name _error]
        nil)
      (append-compaction! [_ _name _compaction]
        nil)
      (splice-compaction! [_ _name _compaction]
        nil)
      (truncate-after-compaction! [_ _name]
        nil))))

(store/register-factory! :jsonl-edn-sidecar create-store)
(store/register-factory! :memory create-store)