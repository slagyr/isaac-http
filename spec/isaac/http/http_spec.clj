(ns isaac.http.http-spec
  (:require
    [isaac.http.auth]
    [isaac.http.http :as sut]
    [isaac.logger :as log]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all]))

(describe "HTTP handler"

  (describe "auth middleware"

    (it "rejects a non-loopback request with no bearer token when server auth is configured"
      (let [handler  (sut/create-handler {:cfg {:http {:host "0.0.0.0"
                                                         :auth {:token "s3cr3t"}}}})
            response (handler {:request-method :get :uri "/status" :headers {}})]
        (should= 401 (:status response))
        (should= "Bearer" (get-in response [:headers "WWW-Authenticate"]))))

    (it "allows a matching bearer token on a non-loopback request"
      (let [handler  (sut/create-handler {:cfg {:http {:host "0.0.0.0"
                                                         :auth {:token "s3cr3t"}}}})
            response (handler {:request-method :get
                               :uri            "/status"
                               :headers        {"authorization" "Bearer s3cr3t"}})]
        (should= 200 (:status response))))

    (it "enforces a configured token even on a loopback bind"
      (let [handler  (sut/create-handler {:cfg {:http {:host "127.0.0.1"
                                                         :auth {:token "s3cr3t"}}}})
            response (handler {:request-method :get :uri "/status" :headers {}})]
        (should= 401 (:status response))))

    (helper/with-captured-logs)

    (it "warns once for a legacy token until auth config changes"
      (let [config* (atom {:http {:auth {:token "marigold"}}})
            handler (sut/create-handler {:cfg-fn #(identity @config*)})
            request {:request-method :get :uri "/status"
                     :headers {"authorization" "Bearer marigold"}}]
        (handler request)
        (handler request)
        (should= 1 (count (filter #(= :auth/legacy-token (:event %)) @log/captured-logs)))
        (reset! config* {:http {:auth {:token "skybeam"}}})
        (handler (assoc-in request [:headers "authorization"] "Bearer skybeam"))
        (should= 2 (count (filter #(= :auth/legacy-token (:event %)) @log/captured-logs)))))

    (it "accepts a principal returned by a request identity verifier"
      (with-redefs [isaac.http.auth/identity-verifiers
                    (constantly [(fn [request]
                                   (when (= "signed" (get-in request [:headers "x-identity"]))
                                     {:name :google :scopes #{:*}}))])]
        (let [handler  (sut/create-handler)
              response (handler {:request-method :get :uri "/status"
                                 :headers {"x-identity" "signed"}})]
          (should= 200 (:status response)))))

    (it "maps a handler scope refusal to 403"
      (let [handler  (sut/create-handler
                       {:cfg {:http {:auth {:principals
                                             {:ci {:hash (isaac.http.auth/sha256 "marigold")
                                                   :scopes #{:hail/send}}}}}}
                        :handler (fn [request]
                                   (isaac.http.auth/require-scope! request :hail/prompt-override))})
            response (handler {:request-method :get :uri "/status"
                               :headers {"authorization" "Bearer marigold"}})]
        (should= 403 (:status response)))))

  (it "creates a handler function"
    (let [handler (sut/create-handler)]
      (should (fn? handler))))

  (it "handler responds to GET /status"
    (let [handler  (sut/create-handler)
          response (handler {:request-method :get :uri "/status"})]
      (should= 200 (:status response))))

  (it "handler returns 404 for unknown routes"
    (let [handler  (sut/create-handler)
          response (handler {:request-method :get :uri "/nope"})]
      (should= 404 (:status response))))

  (describe "request logging"

    (helper/with-captured-logs)

    (it "logs request received"
      (let [handler (sut/create-handler)]
        (handler {:request-method :get :uri "/status"})
        (should (some #(= :server/request-received (:event %)) @log/captured-logs))))

    (it "logs response sent with status and latency"
      (let [handler (sut/create-handler)]
        (handler {:request-method :get :uri "/status"})
        (let [sent (first (filter #(= :server/response-sent (:event %)) @log/captured-logs))]
          (should-not-be-nil sent)
          (should= 200 (:status sent))
          (should-not-be-nil (:ms sent)))))

    (it "logs the request method and uri"
      (let [handler (sut/create-handler)]
        (handler {:request-method :get :uri "/status"})
        (let [received (first (filter #(= :server/request-received (:event %)) @log/captured-logs))]
          (should= :get (:method received))
          (should= "/status" (:uri received)))))

    (it "logs the socket peer as :client when no proxy header is present"
      (let [handler (sut/create-handler)]
        (handler {:request-method :get :uri "/status" :remote-addr "10.1.2.3"})
        (let [received (first (filter #(= :server/request-received (:event %)) @log/captured-logs))
              sent     (first (filter #(= :server/response-sent (:event %)) @log/captured-logs))]
          (should= "10.1.2.3" (:client received))
          (should= "10.1.2.3" (:client sent)))))

    (it "logs the first X-Forwarded-For hop as :client behind a proxy"
      (let [handler (sut/create-handler)]
        (handler {:request-method :get :uri "/status" :remote-addr "127.0.0.1"
                  :headers {"x-forwarded-for" "203.0.113.9, 10.0.0.1"}})
        (let [received (first (filter #(= :server/request-received (:event %)) @log/captured-logs))]
          (should= "203.0.113.9" (:client received)))))

    (it "logs request-failed with ex-class and error-message on exception"
      (let [handler (sut/create-handler (fn [_] (throw (Exception. "handler exploded"))))]
        (handler {:request-method :get :uri "/boom"})
        (let [err (first (filter #(= :error (:level %)) @log/captured-logs))]
          (should-not-be-nil err)
          (should= :server/request-failed (:event err))
          (should= 500 (:status err))
          (should-not-be-nil (:ex-class err))
          (should= "handler exploded" (:error-message err)))))

    (it "returns 500 response on handler exception"
      (let [handler (sut/create-handler (fn [_] (throw (Exception. "oops"))))]
        (let [response (handler {:request-method :get :uri "/boom"})]
          (should= 500 (:status response)))))

    )

  )
