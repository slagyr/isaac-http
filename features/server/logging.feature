Feature: Server request logging
  The HTTP server logs request lifecycle events as structured EDN entries.

  Background:
    Given config:
      | key               | value  |
      | log.output        | memory |
      | hot-reload | false  |
      | http.port       | 0      |
    And the Isaac server is started

  Scenario: Successful request lifecycle is logged at debug
    When a GET request is made to "/status"
    Then the log has entries matching:
      | level  | event                    | uri     |
      | :debug | :server/request-received | /status |
      | :debug | :server/response-sent    | /status |

  Scenario: Failed request is logged at error with context
    When a GET request is made to "/error"
    Then the log has entries matching:
      | level  | event                   | uri    | status |
      | :error | :server/request-failed  | /error | 500    |

  Scenario: the originating client is logged, first X-Forwarded-For hop behind a proxy
    When the client sends GET "/status" with header "X-Forwarded-For: 203.0.113.9, 10.0.0.1"
    Then the log has entries matching:
      | level  | event                    | uri     | client      |
      | :debug | :server/request-received | /status | 203.0.113.9 |
      | :debug | :server/response-sent    | /status | 203.0.113.9 |
