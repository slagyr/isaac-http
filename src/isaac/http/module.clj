(ns isaac.http.module
  (:require
    [isaac.http.cli]
    [isaac.module.protocol :as module]
    [isaac.module.loader :as module-loader]))

(defn create-module []
  (module/module))

(defn comm-kinds
  "Returns sorted user-configurable comm kind names from the given module index.
   Filters out entries where :configurable? is false. With no args, falls back
   to the builtin manifest index."
  ([] (comm-kinds (module-loader/builtin-index)))
  ([module-index]
   (->> (vals module-index)
        (mapcat #(get-in % [:manifest :isaac.http/comm]))
        (remove (fn [[_ v]] (false? (:configurable? v))))
        (map (fn [[k _]] (name k)))
        sort
        distinct
        vec)))