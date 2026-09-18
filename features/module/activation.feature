Feature: Module activation
  Modules in the discovered index are loaded and activated during server
  boot in dependency order. Comms and services still instantiate on first
  configured slot use after boot.

  Scenario: Comm slot starts when configured at boot
    Given an empty Isaac root at "/tmp/isaac"
    And server config:
      | key              | value  |
      | bind-server-port | false  |
      | log.output       | memory |
    And the isaac file "isaac.edn" exists with:
      """
      {:log     {:output :memory}
       :hot-reload false
       :modules {:isaac.http.test-comm {:local/root "spec-support"}}
       :comms   {:bert {:type :test-comm :loft "rooftop"}}}
      """
    When the Isaac server is started
    Then the log has entries matching:
      | level | event             | module           |
      | :info | :module/activated | isaac.http.test-comm |
    And the log has entries matching:
      | level | event              | path       | impl  |
      | :info | :lifecycle/started | comms.bert | test-comm |

  Scenario: Declared module is activated during server boot even without a slot
    Given an empty Isaac root at "/tmp/isaac"
    And server config:
      | key              | value  |
      | bind-server-port | false  |
      | log.output       | memory |
    And the isaac file "isaac.edn" exists with:
      """
      {:log     {:output :memory}
       :hot-reload false
       :modules {:isaac.http.test-comm {:local/root "spec-support"}}}
      """
    When the Isaac server is started
    Then the log has entries matching:
      | level | event             | module           |
      | :info | :module/activated | isaac.http.test-comm |

  Scenario: Module activation failure surfaces a structured error
    Given an empty Isaac root at "/tmp/isaac"
    And server config:
      | key              | value  |
      | bind-server-port | false  |
      | log.output       | memory |
    And environment variable "ISAAC_TEST_COMM_FAIL_ON_LOAD" is "true"
    And the isaac file "isaac.edn" exists with:
      """
      {:log     {:output :memory}
       :hot-reload false
       :modules {:isaac.http.test-comm {:local/root "spec-support"}}
       :comms   {:bert {:type :test-comm :loft "rooftop"}}}
      """
    When the Isaac server is started
    Then the log has entries matching:
      | level  | event                     | module           |
      | :error | :module/activation-failed | isaac.http.test-comm |