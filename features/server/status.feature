Feature: Server status endpoint
  The HTTP server exposes a /status endpoint for health monitoring.

  Scenario: GET /status returns 200 with JSON services
    Given config:
      | key               | value |
      | hot-reload | false |
      | http.port       | 0     |
    And the Isaac server is started
    When a GET request is made to "/status"
    Then the response status is 200
    And the response body has "status" equal to "ok"
    And the response body has a "services" key
