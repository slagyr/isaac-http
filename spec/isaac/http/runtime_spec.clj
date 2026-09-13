(ns isaac.http.runtime-spec
  (:require
    [isaac.config.loader :as loader]
    [isaac.module.loader :as module-loader]
    [isaac.http.runtime :as sut]
    [speclj.core :refer :all]))

(describe "server runtime dispatch"

  (describe "runtime-name"

    (it "is bb under babashka"
      (with-redefs [sut/babashka? (constantly true)]
        (should= "bb" (sut/runtime-name))))

    (it "is jvm on the JVM"
      (with-redefs [sut/babashka? (constantly false)]
        (should= "jvm" (sut/runtime-name)))))

  (describe "runtime-version"

    (it "reads babashka.version under babashka"
      (let [prop "babashka.version"]
        (try
          (System/setProperty prop "1.3.190")
          (with-redefs [sut/babashka? (constantly true)]
            (should= "1.3.190" (sut/runtime-version)))
          (finally (System/clearProperty prop)))))

    (it "reads java.version on the JVM"
      (with-redefs [sut/babashka? (constantly false)]
        (should= (System/getProperty "java.version") (sut/runtime-version)))))

  (describe "babashka?"

    (it "is true when babashka.version is set"
      (let [prop "babashka.version"]
        (try
          (System/setProperty prop "1.3.190")
          (should= true (sut/babashka?))
          (finally (System/clearProperty prop)))))

    (it "is false when babashka.version is absent"
      (let [prop "babashka.version"]
        (try
          (System/clearProperty prop)
          (should= false (sut/babashka?))
          (finally (System/clearProperty prop))))))

  (describe "trampoline?"

    (it "is true for jvm runtime under babashka"
      (with-redefs [sut/babashka? (constantly true)]
        (should= true (sut/trampoline? :jvm))))

    (it "is false for bb runtime under babashka"
      (with-redefs [sut/babashka? (constantly true)]
        (should= false (sut/trampoline? :bb))))

    (it "is false for jvm runtime when already on the JVM"
      (with-redefs [sut/babashka? (constantly false)]
        (should= false (sut/trampoline? :jvm)))))

  (describe "strip-runtime-flag"

    (it "removes --runtime and its value from server args"
      (should= ["--port" "4000"]
               (sut/strip-runtime-flag ["--runtime" "jvm" "--port" "4000"])))

    (it "removes --runtime=<value> form"
      (should= ["--port" "4000"]
               (sut/strip-runtime-flag ["--runtime=jvm" "--port" "4000"]))))

  (describe "trampoline-argv"

    (it "builds clojure -Sdeps ... -M -m isaac.main --root <root> server <args>"
      (let [launch-deps {:paths ["/seed/src"] :deps {'marigold.app/marigold.app {:local/root "modules/marigold.app"}}}]
        (with-redefs [loader/load-config-result (fn [& _] {:config {:modules {}}})
                      module-loader/config->launch-deps (fn [& _] launch-deps)]
          (should= ["clojure" "-Sdeps" (pr-str launch-deps) "-M" "-m" "isaac.main"
                    "--root" "/tmp/root" "server" "--port" "4000"]
                   (sut/trampoline-argv {:root "/tmp/root"} ["--runtime" "jvm" "--port" "4000"]))))))

  (describe "maybe-trampoline!"

    (it "returns nil for default bb runtime so the server runs in-process"
      (with-redefs [sut/babashka? (constantly true)]
        (should-be-nil (sut/maybe-trampoline! {:runtime "bb"} []))))

    (it "returns nil for jvm runtime when already on the JVM"
      (with-redefs [sut/babashka? (constantly false)]
        (should-be-nil (sut/maybe-trampoline! {:runtime "jvm"} []))))

    (it "errors to stderr when clojure is missing"
      (with-redefs [sut/babashka? (constantly true)
                    sut/cmd-available? (constantly false)]
        (let [err (java.io.StringWriter.)]
          (binding [*err* err]
            (should= 1 (sut/maybe-trampoline! {:runtime "jvm"} [])))
          (should (re-find #"requires the clojure CLI" (str err))))))

    (it "exec-replaces into clojure when jvm is requested from babashka"
      (let [exec-args (atom nil)]
        (with-redefs [sut/babashka? (constantly true)
                      sut/cmd-available? (constantly true)
                      loader/load-config-result (fn [& _] {:config {}})
                      module-loader/config->launch-deps (fn [& _] {:deps {}})
                      sut/exec-trampoline! (fn [argv] (reset! exec-args argv))]
          (sut/maybe-trampoline! {:root "/tmp/root" :runtime "jvm"} ["--runtime" "jvm" "--port" "4000"])
          (should= ["clojure" "-Sdeps" "{:deps {}}" "-M" "-m" "isaac.main"
                    "--root" "/tmp/root" "server" "--port" "4000"]
                   @exec-args))))))