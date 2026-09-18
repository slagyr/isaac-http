Feature: Per-principal scoped auth (isaac-bzgw, epic isaac-gym1)
  `:server :auth :principals` names each client, stores only a SHA-256 hash of
  its bearer secret, and lists the scopes it may use. Routes declare the scope
  they require on the :isaac.http/route berth entry; a route with no :scope
  requires admin (`:*`). The legacy single `:server :auth :token` keeps working
  as principal `admin` with every scope. Principals hot-reload through the same
  seam as the legacy token (isaac-s9e3): no restart.

  /status is a built-in route registered here with the scope the scenario
  needs; the fixture route "/fixture/scoped" is registered by a step with an
  explicit scope, and "/fixture/unscoped" with none.

  Background:
    Given an Isaac root at "target/principals-state"
    And config:
      | server.host       | 0.0.0.0 |
      | server.hot-reload | true    |

  Scenario: a principal holding the route's scope reaches the handler and is named in the request log
    Given principal "ci" is configured with secret "ci-secret" and scopes "hail/send"
    And a fixture route GET "/fixture/scoped" requires scope "hail/send"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret"
    Then the response status is 200
    And the log has entries matching:
      | event         | principal | uri             |
      | :http/request | ci        | /fixture/scoped |

  Scenario: a known principal without the route's scope is refused with 403
    Given principal "ci" is configured with secret "ci-secret" and scopes "hail/send"
    And a fixture route GET "/fixture/scoped" requires scope "cli"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret"
    Then the response status is 403
    And the log has entries matching:
      | event         | principal | reason |
      | :auth/refused | ci        | :scope |

  Scenario: an unknown bearer is refused with 401 and never logged
    Given principal "ci" is configured with secret "ci-secret" and scopes "hail/send"
    And a fixture route GET "/fixture/scoped" requires scope "hail/send"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer not-a-secret"
    Then the response status is 401
    And the response header "WWW-Authenticate" matches "Bearer.*"
    And the log has entries matching:
      | event         | reason   |
      | :auth/refused | :unknown |
    And the log has no entries matching:
      | message           |
      | #".*not-a-secret.*" |

  Scenario: a route without a declared scope requires admin
    Given principal "ci" is configured with secret "ci-secret" and scopes "hail/send"
    And principal "admin" is configured with secret "root-secret" and scopes "*"
    And a fixture route GET "/fixture/unscoped" declares no scope
    And the Isaac server is started
    When the client sends GET "/fixture/unscoped" with header "Authorization: Bearer ci-secret"
    Then the response status is 403
    When the client sends GET "/fixture/unscoped" with header "Authorization: Bearer root-secret"
    Then the response status is 200

  Scenario: an expired principal is refused with 401
    Given principal "ci" is configured with secret "ci-secret" and scopes "hail/send" expiring "2020-01-01"
    And a fixture route GET "/fixture/scoped" requires scope "hail/send"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret"
    Then the response status is 401
    And the log has entries matching:
      | event         | principal | reason   |
      | :auth/refused | ci        | :expired |

  Scenario: the config holds a hash, never the secret
    Given principal "ci" is configured with secret "ci-secret" and scopes "hail/send"
    Then the config file "config/isaac.edn" does not contain "ci-secret"
    And the isaac config path "server.auth.principals.ci.hash" matches "sha256:[0-9a-f]{64}"

  Scenario: the legacy :server :auth :token still authenticates as admin and warns once
    Given config:
      | server.auth.token | s3cr3t |
    And a fixture route GET "/fixture/unscoped" declares no scope
    And the Isaac server is started
    When the client sends GET "/fixture/unscoped" with header "Authorization: Bearer s3cr3t"
    Then the response status is 200
    And the log has entries matching:
      | level | event              | principal |
      | :info | :http/request      | admin     |
    And the log has entries matching:
      | level | event               |
      | :warn | :auth/legacy-token  |
    When the client sends GET "/fixture/unscoped" with header "Authorization: Bearer s3cr3t"
    Then the log has exactly 1 entries matching:
      | event              |
      | :auth/legacy-token |

  Scenario: a principal added to config takes effect on the next request without a restart
    Given principal "admin" is configured with secret "root-secret" and scopes "*"
    And a fixture route GET "/fixture/scoped" requires scope "hail/send"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer late-secret"
    Then the response status is 401
    When principal "late" is configured with secret "late-secret" and scopes "hail/send"
    And the isaac config is reloaded
    And the client sends GET "/fixture/scoped" with header "Authorization: Bearer late-secret"
    Then the response status is 200

  Scenario: a revoked principal is refused on the next request without a restart
    Given principal "ci" is configured with secret "ci-secret" and scopes "hail/send"
    And a fixture route GET "/fixture/scoped" requires scope "hail/send"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret"
    Then the response status is 200
    When principal "ci" is removed from config
    And the isaac config is reloaded
    And the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret"
    Then the response status is 401

  Scenario: a handler can require a finer scope than its route
    The route admits :hail/send; the handler demands :hail/prompt-override for
    the dangerous field via isaac.http.auth/require-scope!.
    Given principal "ci" is configured with secret "ci-secret" and scopes "hail/send"
    And principal "ops" is configured with secret "ops-secret" and scopes "hail/send,hail/prompt-override"
    And a fixture route GET "/fixture/scoped" requires scope "hail/send" and its handler requires "hail/prompt-override"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret"
    Then the response status is 403
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer ops-secret"
    Then the response status is 200

  Scenario: 401 and 403 both count toward burst control
    Given principal "ci" is configured with secret "ci-secret" and scopes "hail/send"
    And a fixture route GET "/fixture/scoped" requires scope "cli"
    And config:
      | server.burst.threshold   | 3      |
      | server.burst.window-ms   | 60000  |
      | server.burst.cooldown-ms | 600000 |
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret" 2 times
    And the client sends GET "/fixture/scoped" with header "Authorization: Bearer nope" 1 times
    Then the log has entries matching:
      | level | event                  | count |
      | :warn | :server/burst-detected | 3     |
