;; mutation-tested: 2026-05-06
(ns isaac.http.routes
  (:refer-clojure :exclude [error-handler])
  (:require
    [c3kit.apron.util :as util]
    [clout.core :as clout]))

;; The registry holds two collections. Exact routes — keyed by
;; [method uri] — stay an O(1) hash lookup on the hot path. Pattern
;; routes — clout-compiled — fall back as a linear scan when the
;; exact map misses.
(def ^:dynamic *registry* (atom {:exact {} :patterns []}))

(defn fresh-registry [] {:exact {} :patterns []})

(defn- pattern-string? [uri]
  (or (re-find #":[A-Za-z_][A-Za-z0-9_-]*" uri)
      (.contains ^String uri "*")))

(defn register-route!
  "Register a handler at `[method uri]`. `uri` may be a literal path
   (lands in the exact-match map) or a clout pattern with `:name`
   params and/or `*` wildcards (lands in the pattern list). `method`
   may be a keyword (`:get`, `:post`, …) or `:*` for any-method.
   Returns the registration key for chaining."
  [method uri handler]
  (if (pattern-string? uri)
    (let [pattern (clout/route-compile uri)
          entry   {:method  method
                   :uri     uri
                   :pattern pattern
                   :handler handler}]
      (swap! *registry*
             (fn [reg]
               (update reg :patterns
                       (fn [patterns]
                         (-> (remove #(and (= method (:method %))
                                           (= uri (:uri %)))
                                     patterns)
                              (concat [entry])
                              vec)))))
      [method uri])
    (do
      (swap! *registry* assoc-in [:exact [method uri]] {:handler handler})
      [method uri])))

(defn- maybe-resolve [sym]
  (when (symbol? sym) (util/resolve-var sym)))

(defn register-route-entry!
  "Per-entry factory for the :isaac.http/route berth. Each entry is
   `{:method :get :path \"/x\" :handler isaac.foo/handler}`. Both
   literal paths and clout patterns (`/foo/:bar`, `/hooks/*`) flow
   through register-route!. `:method` may be `:*` for any-method."
  [{:keys [method path handler]}]
  (register-route! method path (maybe-resolve handler)))

(defn route-registered? [method uri]
  (let [{:keys [exact patterns]} @*registry*]
    (or (contains? exact [method uri])
        (boolean (some #(and (= method (:method %)) (= uri (:uri %))) patterns)))))

(def ^:private not-found
  {:status 404 :headers {"Content-Type" "text/plain"} :body "Not found"})

(defn error-handler [_request]
  (throw (ex-info "Intentional error" {:route "/error"})))

(def ^:private built-in-routes
  {[:get "/status"] {:handler 'isaac.http.status/handle}
   [:get "/error"]  {:handler 'isaac.http.routes/error-handler}})

(defn- resolve-handler [handler-ref]
  (cond
    (symbol? handler-ref) @(util/resolve-var handler-ref)
    (var? handler-ref)    @handler-ref
    :else                 handler-ref))

(defn- invoke-route [{:keys [handler]} request]
  (let [handler (resolve-handler handler)]
    (handler request)))

(defn- method-matches? [route-method request-method]
  (or (= :* route-method) (= route-method request-method)))

(defn- dispatch-exact [table request]
  (some-> (get table [(:request-method request) (:uri request)])
          (invoke-route request)))

(defn- dispatch-pattern [patterns request]
  (some (fn [{:keys [pattern method] :as entry}]
          (when (method-matches? method (:request-method request))
            (when-let [params (clout/route-matches pattern request)]
              (invoke-route entry (assoc request :route-params params)))))
        patterns))

(defn- dispatch-request [request]
  (let [{:keys [exact patterns]} @*registry*]
    (or (dispatch-exact exact request)
        (dispatch-exact built-in-routes request)
        (dispatch-pattern patterns request)
        not-found)))

(defn handler
  ([request]
   (dispatch-request request))
  ([opts request]
    ;; Thread the server's current config into the request as a value. We do NOT
    ;; write it to the global snapshot here — install! sets the snapshot at boot
    ;; and on every reload, so per-request mutation is both redundant and racy.
    (let [cfg (or (when-let [cfg-fn (:cfg-fn opts)] (cfg-fn))
                  (:cfg opts))]
      (dispatch-request (cond-> request cfg (assoc :config cfg))))))
