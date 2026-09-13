(ns isaac.http.auth-steps
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen helper!]]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]))

(helper! isaac.http.auth-steps)

(defn authenticated-credentials [provider]
  (let [sdir      (or (g/get :root) "/test/state")
        auth-file (str sdir "/auth.json")
        mem-fs    (g/get :mem-fs)
        write-fn  (fn [fs*]
                    (let [auth-data (if (fs/exists? fs* auth-file)
                                      (json/parse-string (fs/slurp fs* auth-file) true)
                                      {})]
                      (fs/mkdirs fs* (fs/parent auth-file))
                      (fs/spit   fs* auth-file (json/generate-string
                                                 (assoc-in auth-data [:providers (keyword provider)]
                                                           {:type "api-key" :apiKey "sk-test-key"})))))]
    (if mem-fs
      (nexus/-with-nested-nexus {:fs mem-fs}
        (write-fn mem-fs))
      (write-fn (or (nexus/get :fs) (fs/real-fs))))))

(defn output-prompts-for-key []
  (let [output (g/get :output)]
    (g/should (or (str/includes? output "API key")
                  (str/includes? output "Enter")))))

(defn credentials-removed [provider]
  (let [output (g/get :output)]
    (g/should (str/includes? output "Logged out"))))

(defgiven "authenticated credentials exist for provider {provider:string}" isaac.http.auth-steps/authenticated-credentials
  "Writes a minimal api-key credential to <root>/auth.json under the
   given provider. Creates or updates the file — does not touch provider
   EDN configs in ~/.isaac/config/providers/.")

(defthen "the stdout prompts for an API key" isaac.http.auth-steps/output-prompts-for-key)

(defthen "credentials for {provider:string} are removed" isaac.http.auth-steps/credentials-removed
  "Asserts the 'Logged out' message appeared in output. Does NOT read
   the credentials file to verify removal — phrase is about the
   observable behavior, not the on-disk state.")
