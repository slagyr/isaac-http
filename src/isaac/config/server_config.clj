(ns isaac.config.server-config
  (:require
    [isaac.config.loader :as loader]))

(defn server-config
  "Resolve HTTP bind and process hot-reload settings from a loaded config."
  [config]
  (let [config (loader/normalize-config config)]
    {:port                (or (get-in config [:http :port]) 6674)
     :host                (or (get-in config [:http :host]) "127.0.0.1")
     :hot-reload          (let [hot-reload (:hot-reload config)]
                            (if (boolean? hot-reload) hot-reload true))
}))