(ns isaac.http.server-steps-spec
  (:require
    [gherclj.core :as g]
    [isaac.config.runtime :as runtime]
    [isaac.foundation.fs-steps :as ffs]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.nexus :as nexus]
    [isaac.http.app :as app]
    [isaac.http.server-steps :as sut]
    [speclj.core :refer :all])
  (:import
    (java.net InetAddress InetSocketAddress ServerSocket)))

(def test-root "/target/test-state")

(describe "server feature steps"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (g/reset!)
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (it))
    (g/reset!))

  ;; Loading isaac.http.server-steps (the sut require above) registers the
  ;; foundation post-write hook, so a foundation isaac-file write notifies the
  ;; running config-change source — the server side of the notify seam.
  (it "fires the config change source when an isaac file is written"
    (let [source (runtime/memory-source "/target/test-state")]
      (runtime/start! source)
      (g/assoc! :mem-fs (nexus/get :fs))
      (g/assoc! :config-change-source source)
      (ffs/file-exists-with (str "/target/test-state/config/crew/" marigold/captain ".edn") "{:model :llama}")
      (should= (str "crew/" marigold/captain ".edn") (runtime/poll! source 0))
      (runtime/stop! source)))

  (it "loads config from the in-memory fs at the virtual home"
    (let [started      (atom nil)
          virtual-home "/target/test-state"
          cfg          {:http {:port 7788}}]
      (g/assoc! :mem-fs (nexus/get :fs))
      (g/assoc! :root virtual-home)
      (fs/mkdirs (nexus/get :fs) (str virtual-home "/config"))
      (fs/spit (nexus/get :fs) (str virtual-home "/config/isaac.edn") (pr-str cfg))
      (with-redefs [app/start! (fn [opts]
                                 (reset! started opts)
                                 {:port 7788 :host "0.0.0.0"})
                    app/stop!  (fn [] nil)]
        (sut/server-running))
      (should= 7788 (get-in (:config @started) [:http :port]))
      (should= virtual-home (:root @started))))

  (it "can skip binding a real port for reload-only scenarios"
    (let [started      (atom nil)
          virtual-home "/target/test-state"
          cfg          {:http {:port 7788}}]
      (g/assoc! :mem-fs (nexus/get :fs))
      (g/assoc! :root virtual-home)
      (g/assoc! :bind-server-port? false)
      (fs/mkdirs (nexus/get :fs) (str virtual-home "/config"))
      (fs/spit (nexus/get :fs) (str virtual-home "/config/isaac.edn") (pr-str cfg))
      (with-redefs [app/start! (fn [opts]
                                 (reset! started opts)
                                 {:port 0 :host "0.0.0.0"})
                    app/stop!  (fn [] nil)]
        (sut/server-running))
      (should= 0 (:port @started))
      (should= virtual-home (:root @started))))

  (it "disables the async config reloader so sync-config-reload! is sole consumer"
    (let [started (atom nil)
          cfg     {:http {:hot-reload true :port 7788}}]
      (g/assoc! :mem-fs (nexus/get :fs))
      (g/assoc! :root "/target/test-state")
      (fs/mkdirs (nexus/get :fs) "/target/test-state/config")
      (fs/spit (nexus/get :fs) "/target/test-state/config/isaac.edn" (pr-str cfg))
      (with-redefs [app/start! (fn [opts]
                                 (reset! started opts)
                                 {:port 7788 :host "0.0.0.0"})
                    app/stop!  (fn [] nil)]
        (sut/server-running))
      (should= false (:start-config-reloader? @started))))

  (it "uses an isolated default home when no root or isaac-home is set"
    (let [started (atom nil)]
      (with-redefs [app/start! (fn [opts]
                                 (reset! started opts)
                                 {:port 7788 :host "0.0.0.0"})
                    app/stop!  (fn [] nil)]
        (sut/server-running))
      (should= (str (System/getProperty "user.dir") "/target/test-state/server-default-home")
               (:root @started))
      (should= (str (System/getProperty "user.dir") "/target/test-state/server-default-home")
               (g/get :root))))

  (it "runs the server command step without binding the real port"
    (g/assoc! :server-config {:log {:output :memory}
                              :http {:hot-reload false}})
    (let [socket (ServerSocket.)]
      (.bind socket (InetSocketAddress. (InetAddress/getByName "127.0.0.1") 0))
      (let [port (.getLocalPort socket)]
        (try
          (should-not-throw (sut/server-command-run port))
          (finally
            (.close socket))))))

  (it "deletes config keys with #delete"
    ;; | key | value | headers are not data rows (foundation config-rows).
    (g/assoc! :mem-fs (nexus/get :fs))
    (g/assoc! :root test-root)
    (g/assoc! :server-config {:comms {(keyword marigold/longwave) {:token "shh" :name marigold/captain}}})
    (fs/mkdirs (nexus/get :fs) (str test-root "/config"))
    (fs/spit (nexus/get :fs) (str test-root "/config/isaac.edn")
             (pr-str {:comms {(keyword marigold/longwave) {:token "shh" :name marigold/captain}}}))
    (sut/server-config-applied {:headers ["key" "value"]
                                :rows    [[(str "comms." marigold/longwave ".token") "#delete"]]})
    (should= {:comms {(keyword marigold/longwave) {:name marigold/captain}}}
             (g/get :server-config))
    (should= {:comms {(keyword marigold/longwave) {:name marigold/captain}}}
             (read-string (fs/slurp (nexus/get :fs) (str test-root "/config/isaac.edn")))))

  )
