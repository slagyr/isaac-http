@wip
Feature: HTTP bind logging
  The HTTP listener logs `:http/listening` with the bound host and
  port. Process start does not.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: HTTP bind logs :http/listening with host and port
    Given config:
      | http.host | 127.0.0.1 |
      | http.port | 9876      |
    And the Isaac server is started
    Then the log has entries matching:
      | level | event           | host      | port |
      | :info | :http/listening | 127.0.0.1 | 9876 |

  # Port 6674 = first four digits of Newton's gravitational constant
  # G = 6.6743 × 10⁻¹¹ N·m²/kg²
  Scenario: Default port 6674 is on :http/listening
    Given config:
      | log.output | memory |
      | hot-reload | false  |
    When the server command is run without a port flag
    Then the log has entries matching:
      | level | event           | host      | port |
      | :info | :http/listening | 127.0.0.1 | 6674 |
    And the log has no entries matching:
      | event           | port |
      | :server/started | 6674 |
