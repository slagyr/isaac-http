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

  Scenario: retired server host points at http host
    Given config file "isaac.edn" containing:
      """
      {:server {:host "0.0.0.0"}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key         | value                      |
      | server.host | retired.*use :http :host.* |

  Scenario: retired server port points at http port
    Given config file "isaac.edn" containing:
      """
      {:server {:port 6674}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key         | value                      |
      | server.port | retired.*use :http :port.* |

  Scenario: retired server auth token points at http auth token
    Given config file "isaac.edn" containing:
      """
      {:server {:auth {:token "leftover"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key               | value                             |
      | server.auth.token | retired.*use :http :auth :token.* |

  Scenario: retired server burst points at http burst
    Given config file "isaac.edn" containing:
      """
      {:server {:burst {:threshold 30}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key          | value                       |
      | server.burst | retired.*use :http :burst.* |

  Scenario: retired server hot reload points at top-level hot reload
    Given config file "isaac.edn" containing:
      """
      {:server {:hot-reload true}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key               | value                      |
      | server.hot-reload | retired.*use :hot-reload.* |

  Scenario: retired server suspend timeout points at bridge suspend timeout
    Given config file "isaac.edn" containing:
      """
      {:server {:suspend-timeout-ms 15000}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                       | value                                     |
      | server.suspend-timeout-ms | retired.*use :bridge :suspend-timeout-ms.* |
