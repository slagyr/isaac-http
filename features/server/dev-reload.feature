Feature: Server Dev Reload
  In development mode, the server refreshes source code on every
  request so developers don't need to restart for every edit.

  Background:
    Given an Isaac root at "target/test-state"
    And server config:
      | key               | value  |
      | log.output        | memory |
      | hot-reload | false  |

  Scenario: Dev mode wraps the root handler with refresh
    Given environment variable "ISAAC_DEV" is "true"
    And server config:
      | key         | value |
      | http.port | 0     |
    And the Isaac server is started
    When a GET request is made to "/status"
    Then the log has entries matching:
      | level  | event                    |
      | :debug | :server/dev-reload-scan  |

  Scenario: Non-dev mode does not reload
    Given environment variable "ISAAC_DEV" is "false"
    And server config:
      | key         | value |
      | http.port | 0     |
    And the Isaac server is started
    When a GET request is made to "/status"
    Then the log has no entries matching:
      | event                   |
      | :server/dev-reload-scan |

  Scenario: --dev CLI flag enables dev mode, overriding the env
    Given environment variable "ISAAC_DEV" is "false"
    And server config:
      | key         | value |
      | http.port | 0     |
    When the server command is run with args "--dev"
    Then the log has entries matching:
      | level | event                    |
      | :info | :server/dev-mode-enabled |
    And the log has entries matching:
      | level | event           |
      | :info | :http/listening |
