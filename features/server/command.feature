Feature: Server startup command
  Isaac can be started as an HTTP server via the server command.

  Background:
    Given server config:
      | key               | value  |
      | log.output        | memory |
      | hot-reload | false  |

  Scenario: server command logs hello before startup
    When the server command is run on port 9876
    Then the log has entries matching:
      | level | event           | runtime |
      | :info | :server/hello   | #*      |
      | :info | :server/started |         |
