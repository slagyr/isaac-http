@wip
Feature: HTTP listener config
  Inbound HTTP bind, auth, and burst live under `:http`. The process
  command stays `isaac server`; `:server` in isaac.edn is retired.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: :http bind/auth config is valid
    Given config file "isaac.edn" containing:
      """
      {:http {:host "0.0.0.0" :port 6674 :auth {:token "marigold"}}}
      """
    When the config is loaded
    Then the config has no validation errors
    And the loaded config has:
      | key             | value     |
      | http.host       | 0.0.0.0   |
      | http.port       | 6674      |
      | http.auth.token | marigold  |

  Scenario Outline: Retired <key> fails pointing at the new slot
    Given config file "isaac.edn" containing:
      """
      <config>
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key   | value   |
      | <key> | <value> |

    Examples:
      | config                                      | key                       | value                                      |
      | {:server {:host "0.0.0.0"}}                 | server.host               | retired.*use :http :host.*                 |
      | {:server {:port 6674}}                      | server.port               | retired.*use :http :port.*                 |
      | {:server {:auth {:token "leftover"}}}       | server.auth.token         | retired.*use :http :auth :token.*          |
      | {:server {:burst {:threshold 30}}}          | server.burst.threshold    | retired.*use :http :burst.*                |
      | {:server {:hot-reload true}}                | server.hot-reload         | retired.*use :hot-reload.*                 |
      | {:server {:suspend-timeout-ms 15000}}       | server.suspend-timeout-ms | retired.*use :bridge :suspend-timeout-ms.* |
