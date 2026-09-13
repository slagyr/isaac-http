(ns isaac.http.logging
  (:require
    [isaac.log.output :as log-output]))

(defn configure!
  "Apply :logging.output from config. Default :file activates the rotating
   server log at <root>/logs/server.log; :stdout/:stderr/:none stream without it."
  [root config]
  (log-output/apply-server! root config))