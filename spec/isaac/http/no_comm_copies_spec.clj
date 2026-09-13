(ns isaac.http.no-comm-copies-spec
  (:require
    [clojure.java.io :as io]
    [speclj.core :refer :all]))

(describe "server comm ownership"

  (it "contains no production comm namespaces"
    (should-not (.exists (io/file "src/isaac/comm"))))

  (it "does not shadow the Agent-owned session store protocol"
    (should-not (.exists (io/file "src/isaac/session/store/spi.clj"))))

  )
