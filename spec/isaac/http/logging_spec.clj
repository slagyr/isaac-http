(ns isaac.http.logging-spec
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.log.file :as lfile]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.http.logging :as sut]
    [speclj.core :refer :all]))

(describe "Server logging"

  (around [it]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (it)))

  (before (lfile/clear-sink-config!)
          (log/set-output! :stderr)
          (log/clear-entries!))

  (after (lfile/clear-sink-config!)
         (log/set-output! :stderr))

  (it "configure! routes server logs to <root>/logs/server.log with default :file output"
    (let [root "/isaac-root"]
      (sut/configure! root {:tz "UTC"})
      (should= :file (log/output))
      (log/info :server/test-boot)
      (should (fs/exists? (fs/instance) (lfile/server-log-path root)))))

  (it "configure! streams to stdout without a server log file when :logging.output is :stdout"
    (let [root "/isaac-root"]
      (sut/configure! root {:logging {:output :stdout}})
      (should= :stdout (log/output))
      (should-not (lfile/server-sink?))
      (let [sw (java.io.StringWriter.)]
        (binding [*out* sw]
          (log/info :server/stdout-boot))
        (should (str/includes? (.toString sw) ":server/stdout-boot"))
        (should-not (fs/exists? (fs/instance) (lfile/server-log-path root)))))))