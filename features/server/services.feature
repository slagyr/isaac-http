Feature: HTTP component lifecycle
  The HTTP listener is an isaac-http contribution to foundation's :isaac/component berth.

  Background:
    Given default Grover setup

  Scenario: the HTTP listener is a component of isaac-http (isaac-vs6f)
    Given config:
      | key               | value |
      | http.auth.token | test  |
      | http.port       | 0     |
    When the Isaac server is started
    Then the log has entries matching:
      | level | event              | component | module       |
      | :info | :component/started | http      | isaac.http |
    When a GET request is made to "/status"
    Then the response status is 401
    When the Isaac server is stopped
    Then the log has entries matching:
      | level | event              | component |
      | :info | :component/stopped | http      |
