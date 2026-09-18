Feature: Managing principals from the CLI (isaac-auie, epic isaac-gym1)
  `isaac server auth mint|rotate|revoke|list` manages `:server :auth :principals`.
  The secret is a one-time event: mint/rotate print it exactly once, on stdout,
  alone; only its SHA-256 hash is written to config (through the config
  mutation API, so a running server picks it up on hot reload). The secret
  never appears in config, logs, or the startup cache.

  Background:
    Given an Isaac root at "target/auth-principals-state"

  @wip
  Scenario: mint prints the secret once and writes only its hash to config
    When isaac is run with "server auth mint ci --scopes hail/send"
    Then the exit code is 0
    And the stdout has exactly 1 line
    And the stdout line is a bearer secret of at least 32 characters
    And the config file "config/isaac.edn" does not contain the printed secret
    And the isaac config path "server.auth.principals.ci.hash" matches "sha256:[0-9a-f]{64}"
    And the isaac config path "server.auth.principals.ci.scopes" is "#{:hail/send}"
    And the log has no entries matching:
      | message                    |
      | #".*<the printed secret>.*" |

  @wip
  Scenario: the minted secret authenticates as that principal
    Given a fixture route GET "/fixture/scoped" requires scope "hail/send"
    When isaac is run with "server auth mint ci --scopes hail/send"
    And the Isaac server is started
    And the client sends GET "/fixture/scoped" with header "Authorization: Bearer <the printed secret>"
    Then the response status is 200
    And the log has entries matching:
      | event         | principal |
      | :http/request | ci        |

  @wip
  Scenario: mint with --expires records the expiry
    When isaac is run with "server auth mint ci --scopes hail/send --expires 2027-01-31"
    Then the exit code is 0
    And the isaac config path "server.auth.principals.ci.expires" is "2027-01-31"

  @wip
  Scenario: mint refuses an existing name
    Given principal "ci" is configured with secret "ci-secret" and scopes "hail/send"
    When isaac is run with "server auth mint ci --scopes hail/send"
    Then the exit code is 1
    And the stderr contains "already exists"
    And the stderr contains "rotate"
    And the stdout is empty

  @wip
  Scenario: mint requires at least one scope
    When isaac is run with "server auth mint ci"
    Then the exit code is 1
    And the stderr contains "--scopes"
    And the stdout is empty

  @wip
  Scenario: rotate replaces the hash and the old secret stops working
    Given principal "ci" is configured with secret "old-secret" and scopes "hail/send"
    And a fixture route GET "/fixture/scoped" requires scope "hail/send"
    When isaac is run with "server auth rotate ci"
    Then the exit code is 0
    And the stdout has exactly 1 line
    When the Isaac server is started
    And the client sends GET "/fixture/scoped" with header "Authorization: Bearer old-secret"
    Then the response status is 401
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer <the printed secret>"
    Then the response status is 200

  @wip
  Scenario: rotate with --overlap keeps the old secret valid until the window ends
    Given principal "ci" is configured with secret "old-secret" and scopes "hail/send"
    And a fixture route GET "/fixture/scoped" requires scope "hail/send"
    When isaac is run with "server auth rotate ci --overlap 24h"
    Then the exit code is 0
    And the isaac config path "server.auth.principals.ci@prev.expires" matches "20[0-9]{2}-[0-9]{2}-[0-9]{2}T.*"
    And the isaac config path "server.auth.principals.ci@prev.scopes" is "#{:hail/send}"
    When the Isaac server is started
    And the client sends GET "/fixture/scoped" with header "Authorization: Bearer old-secret"
    Then the response status is 200
    And the log has entries matching:
      | event         | principal |
      | :http/request | ci@prev   |

  @wip
  Scenario: revoke removes the principal and its overlap twin
    Given principal "ci" is configured with secret "ci-secret" and scopes "hail/send"
    And principal "ci@prev" is configured with secret "older-secret" and scopes "hail/send" expiring "2099-01-01"
    When isaac is run with "server auth revoke ci"
    Then the exit code is 0
    And the isaac config path "server.auth.principals.ci" is absent
    And the isaac config path "server.auth.principals.ci@prev" is absent

  @wip
  Scenario: revoke of an unknown principal is an error
    When isaac is run with "server auth revoke ghost"
    Then the exit code is 1
    And the stderr contains "ghost"

  @wip
  Scenario: list shows name, scopes and expiry, never a hash or secret
    Given principal "ci" is configured with secret "ci-secret" and scopes "hail/send" expiring "2027-01-31"
    And principal "admin" is configured with secret "root-secret" and scopes "*"
    When isaac is run with "server auth list"
    Then the exit code is 0
    And the stdout lines match:
      | #"admin\s+\*\s+-\s+never"                 |
      | #"ci\s+hail/send\s+2027-01-31\s+never"    |
    And the stdout does not contain "sha256"
    And the stdout does not contain "ci-secret"

  @wip
  Scenario: a running server honours a principal minted from the CLI without a restart
    Given config:
      | server.host       | 0.0.0.0 |
      | server.hot-reload | true    |
    And principal "admin" is configured with secret "root-secret" and scopes "*"
    And a fixture route GET "/fixture/scoped" requires scope "hail/send"
    And the Isaac server is started
    When isaac is run with "server auth mint late --scopes hail/send"
    And the isaac config is reloaded
    And the client sends GET "/fixture/scoped" with header "Authorization: Bearer <the printed secret>"
    Then the response status is 200
