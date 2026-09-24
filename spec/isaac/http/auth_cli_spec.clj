(ns isaac.http.auth-cli-spec
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.config.mutate :as mutate]
    [isaac.fs :as fs]
    [isaac.http.auth :as auth]
    [isaac.http.auth-cli :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def root "/test/auth-cli")

(describe "auth CLI secrets and principals"

  (around [it]
    (nexus/-with-nexus {:root root :fs (fs/mem-fs)}
      ;; A real host always has a default crew; foundation has required one
      ;; since isaac-bfwn, and minting validates the whole config.
      (fs/spit (fs/instance) (str root "/config/isaac.edn")
               (pr-str {:defaults {:frequencies {:crew "main"}}
                        :crew     {"main" {}}}))
      (it)))

  (it "mints a 32-byte base64url secret with no padding"
    (let [secret (sut/generate-secret)]
      (should (<= 32 (count secret)))
      (should (re-matches #"[A-Za-z0-9_-]+" secret))
      (should-not (str/includes? secret "="))))

  (it "writes only the hash when minting a new principal"
    (let [{:keys [secret exit]} (sut/mint! root "ci" {:scopes "hail/send"})]
      (should= 0 exit)
      (should (seq secret))
      (let [cfg (get-in (loader/load-config-result {:root root})
                        [:config :http :auth :principals :ci])]
        (should= (auth/sha256 secret) (:hash cfg))
        (should= #{:hail/send} (:scopes cfg))
        (should-not (str/includes? (pr-str cfg) secret)))))

  (it "refuses to mint over an existing name"
    (sut/mint! root "ci" {:scopes "hail/send"})
    (let [{:keys [exit error]} (sut/mint! root "ci" {:scopes "hail/send"})]
      (should= 1 exit)
      (should (str/includes? (str error) "already exists"))
      (should (str/includes? (str error) "rotate"))))

  (it "renders each set-config validation error as one <path>: <message> line, not a raw EDN vector (isaac-p4oj)"
    (with-redefs [mutate/set-config
                  (fn [& _]
                    {:status :invalid
                     :errors [{:key "http.auth.principals.ci.scopes" :value "must be a set"}
                              {:key "http.auth.principals.ci.hash" :value "is required"}]})]
      (let [{:keys [exit error]} (sut/mint! root "ci" {:scopes "hail/send"})]
        (should= 1 exit)
        (should= (str "http.auth.principals.ci.scopes: must be a set\n"
                     "http.auth.principals.ci.hash: is required")
                 error)
        (should-not (str/includes? error "{:key"))
        (should-not (str/includes? error "[{")))))

  (it "requires at least one scope to mint"
    (let [{:keys [exit error]} (sut/mint! root "ci" {})]
      (should= 1 exit)
      (should (str/includes? (str error) "--scopes"))))

  (it "records --expires on mint"
    (let [{:keys [exit]} (sut/mint! root "ci" {:scopes "hail/send" :expires "2027-01-31"})]
      (should= 0 exit)
      (should= "2027-01-31"
               (get-in (loader/load-config-result {:root root})
                       [:config :http :auth :principals :ci :expires]))))

  (it "rotate replaces the hash so the old secret no longer authenticates"
    (let [old "old-secret"]
      (mutate/set-config root "http.auth.principals.ci"
                         {:hash (auth/sha256 old) :scopes #{:hail/send}}
                         :skip-ref-validation? true
                         :skip-module-validation? true)
      (let [{:keys [secret exit]} (sut/rotate! root "ci" {})
            cfg (loader/load-config-result {:root root})
            principals (get-in cfg [:config :http :auth :principals])]
        (should= 0 exit)
        (should-not= (auth/sha256 old) (get-in principals [:ci :hash]))
        (should= (auth/sha256 secret) (get-in principals [:ci :hash]))
        (should-be-nil (auth/authenticate (:config cfg) old)))))

  (it "rotate --overlap keeps the old hash as name@prev until the window ends"
    (mutate/set-config root "http.auth.principals.ci"
                       {:hash (auth/sha256 "old-secret") :scopes #{:hail/send}}
                       :skip-ref-validation? true
                       :skip-module-validation? true)
    (let [{:keys [exit]} (sut/rotate! root "ci" {:overlap "24h"})
          cfg  (loader/load-config-result {:root root})
          twin (get-in cfg [:config :http :auth :principals :ci :previous])]
      (should= 0 exit)
      (should= (auth/sha256 "old-secret") (:hash twin))
      (should= #{:hail/send} (:scopes twin))
      (should (re-find #"20[0-9]{2}-[0-9]{2}-[0-9]{2}T" (str (:expires twin))))
      (should= (keyword "ci@prev") (:name (auth/authenticate (:config cfg) "old-secret")))))

  (it "revoke removes the principal and its overlap twin"
    (mutate/set-config root "http.auth.principals.ci"
                       {:hash (auth/sha256 "ci-secret")
                        :scopes #{:hail/send}
                        :previous {:hash (auth/sha256 "older") :scopes #{:hail/send} :expires "2099-01-01"}}
                       :skip-ref-validation? true
                       :skip-module-validation? true)
    (let [result (sut/revoke! root "ci")
          loaded (loader/load-config-result {:root root})]
      (should= 0 (:exit result))
      (should-be-nil (get-in loaded [:config :http :auth :principals :ci]))))

  (it "revoke of an unknown principal is an error"
    (let [{:keys [exit error]} (sut/revoke! root "ghost")]
      (should= 1 exit)
      (should (str/includes? (str error) "ghost"))))

  (it "list rows name scopes expiry and never, without hash or secret"
    (mutate/set-config root "http.auth.principals.ci"
                       {:hash (auth/sha256 "ci-secret") :scopes #{:hail/send} :expires "2027-01-31"}
                       :skip-ref-validation? true
                       :skip-module-validation? true)
    (mutate/set-config root "http.auth.principals.admin"
                       {:hash (auth/sha256 "root-secret") :scopes #{:*} }
                       :skip-ref-validation? true
                       :skip-module-validation? true)
    (let [rows (sut/list-rows (loader/load-config-result {:root root}))]
      (should= [{:name "admin" :scopes "*" :expires "-" :last-used "never"}
                {:name "ci" :scopes "hail/send" :expires "2027-01-31" :last-used "never"}]
               rows)))
  )
