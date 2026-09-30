Feature: Server-wide inbound HTTP auth
  Every inbound HTTP request goes through one shared bearer-token
  middleware. The token lives at `:http :auth :token`. Webhook hooks,
  ACP WebSocket upgrades, and direct routes all authenticate
  through the same gate.

  If `:http :auth :token` is configured, the token is enforced on
  every inbound request — bind host doesn't matter. Forwarding layers
  (Tailscale, ngrok, ssh -L, reverse proxies) can map remote traffic
  onto loopback, so "loopback bind" is not a safe proxy for "local
  process." Token presence == enforcement, no exceptions.

  A server with no auth configured still starts. Bound beyond loopback
  (anything `InetAddress/isLoopbackAddress` doesn't report), it logs a
  :server/auth-absent warning, since it is open to any request (isaac-x9y5).

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: A request with the configured Bearer token reaches the handler
    Given config:
      | http.host       | 0.0.0.0 |
      | http.auth.token | s3cr3t  |
    And the Isaac server is started
    When the client sends GET "/status" with header "Authorization: Bearer s3cr3t"
    Then the response status is 200

  Scenario: A request with no Authorization header is rejected
    Given config:
      | http.host       | 0.0.0.0 |
      | http.auth.token | s3cr3t  |
    And the Isaac server is started
    When the client sends GET "/status"
    Then the response status is 401
    And the response header "WWW-Authenticate" matches "Bearer.*"

  Scenario: A request with the wrong token is rejected
    Given config:
      | http.host       | 0.0.0.0 |
      | http.auth.token | s3cr3t  |
    And the Isaac server is started
    When the client sends GET "/status" with header "Authorization: Bearer wrong"
    Then the response status is 401

  Scenario: Loopback bind allows unauthenticated requests when no token is configured
    Given config:
      | http.host | 127.0.0.1 |
    And the Isaac server is started
    When the client sends GET "/status"
    Then the response status is 200

  Scenario: A configured token is enforced even on a loopback bind
    Given config:
      | http.host       | 127.0.0.1 |
      | http.auth.token | s3cr3t    |
    And the Isaac server is started
    When the client sends GET "/status"
    Then the response status is 401

  Scenario: IPv6 loopback bind is treated the same as 127.0.0.1
    Given config:
      | http.host | ::1 |
    And the Isaac server is started
    When the client sends GET "/status"
    Then the response status is 200

  Scenario: A non-loopback bind with no auth starts and warns
    Given config:
      | http.host | 0.0.0.0 |
    And the Isaac server is started
    When the client sends GET "/status"
    Then the response status is 200
    And the log has entries matching:
      | level | event               | host    | message                      |
      | warn  | :server/auth-absent | 0.0.0.0 | .*no auth configured.*open.* |

  Scenario: A non-loopback bind with only principals starts without the warning
    Given principal "ci" is configured with secret "ci-secret" and scopes "*"
    And config:
      | http.host | 0.0.0.0 |
    And the Isaac server is started
    When the client sends GET "/status" with header "Authorization: Bearer ci-secret"
    Then the response status is 200
    And the log has no entries matching:
      | event               |
      | :server/auth-absent |

  Scenario: Token supports ${ENV_VAR} substitution from the state dir env
    Given the env var "ISAAC_AUTH_TOKEN" is set to "envt0ken"
    And config:
      | http.host       | 0.0.0.0             |
      | http.auth.token | ${ISAAC_AUTH_TOKEN} |
    And the Isaac server is started
    When the client sends GET "/status" with header "Authorization: Bearer envt0ken"
    Then the response status is 200

  Scenario: Old bearer is rejected after a token reload
    Given config:
      | http.host       | 0.0.0.0  |
      | http.auth.token | marigold |
      | hot-reload | true     |
    And the Isaac server is started
    When config is updated:
      | path              | value   |
      | http.auth.token | skybeam |
    And the client sends GET "/status" with header "Authorization: Bearer marigold"
    Then the response status is 401

  Scenario: New bearer is accepted after a token reload
    Given config:
      | http.host       | 0.0.0.0  |
      | http.auth.token | marigold |
      | hot-reload | true     |
    And the Isaac server is started
    When config is updated:
      | path              | value   |
      | http.auth.token | skybeam |
    And the client sends GET "/status" with header "Authorization: Bearer skybeam"
    Then the response status is 200
