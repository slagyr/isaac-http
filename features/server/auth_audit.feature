Feature: Auth audit and alerts (isaac-2a2x, epic isaac-gym1)
  Every request is attributed to a principal in the log; refusals say why.
  Last-use per principal is persisted (at most once a minute) for
  `isaac server auth list`. Notable auth events reach the attention comm the
  same way burst detection does (isaac-burst): first ever use of a principal,
  use of an expired or revoked secret, and a principal expiring within seven
  days. Thresholds live under `:server :auth :alerts` and hot-reload.

  Background:
    Given an Isaac root at "target/auth-audit-state"
    And config:
      | key                     | value       |
      | log.output              | memory      |
      | server.hot-reload       | true        |
      | server.port             | 0           |
      | server.host             | 0.0.0.0     |
      | attention.notify.comm   | discord     |
      | attention.notify.target | boiler-room |
    And principal "ci" is configured with secret "ci-secret" and scopes "hail/send"
    And a fixture route GET "/fixture/scoped" requires scope "hail/send"

  @wip
  Scenario: an authenticated request is attributed to its principal in the log
    Given the Isaac server is started
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret"
    Then the response status is 200
    And the log has entries matching:
      | level | event         | principal | method | uri             | status |
      | :info | :http/request | ci        | :get   | /fixture/scoped | 200    |

  @wip
  Scenario: a refused request logs the reason and the principal when known
    Given the Isaac server is started
    And a fixture route GET "/fixture/admin" declares no scope
    When the client sends GET "/fixture/admin" with header "Authorization: Bearer ci-secret"
    Then the response status is 403
    And the log has entries matching:
      | level | event         | principal | reason | uri            |
      | :warn | :auth/refused | ci        | :scope | /fixture/admin |
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer nope"
    Then the log has entries matching:
      | level | event         | principal | reason   |
      | :warn | :auth/refused |           | :unknown |

  @wip
  Scenario: the first ever use of a principal raises one attention post, later uses none
    Given the Isaac server is started
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret"
    Then the directory "comm/delivery/pending" has exactly 1 file
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                                        |
      | comm    | :discord                                     |
      | target  | boiler-room                                  |
      | content | contains "ci" and "first use"                |
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret" 3 times
    Then the directory "comm/delivery/pending" has exactly 1 file

  @wip
  Scenario: last-used is recorded for the principal and shown by auth list
    Given the clock is fixed at "2026-09-18T10:00:00Z"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret"
    Then the isaac file "state/auth/last-used.edn" exists with:
      | path | value                |
      | ci   | 2026-09-18T10:00:00Z |
    When isaac is run with "server auth list"
    Then the stdout lines match:
      | #"ci\s+hail/send\s+-\s+2026-09-18T10:00:00Z" |

  @wip
  Scenario: last-used is written at most once a minute per principal
    Given the clock is fixed at "2026-09-18T10:00:00Z"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret" 5 times
    Then the file "state/auth/last-used.edn" was written exactly 1 times
    When the clock advances 61 seconds
    And the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret"
    Then the file "state/auth/last-used.edn" was written exactly 2 times

  @wip
  Scenario: use of an expired secret raises an attention post naming the principal
    Given principal "stale" is configured with secret "stale-secret" and scopes "hail/send" expiring "2020-01-01"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer stale-secret"
    Then the response status is 401
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                              |
      | content | contains "stale" and "expired"     |

  @wip
  Scenario: use of a revoked secret raises an attention post naming the principal
    Given the Isaac server is started
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret"
    And principal "ci" is removed from config
    And the isaac config is reloaded
    And the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret"
    Then the response status is 401
    And the log has entries matching:
      | event         | principal | reason   |
      | :auth/refused | ci        | :revoked |
    And the directory "comm/delivery/pending" has exactly 2 file
    And the newest file in "comm/delivery/pending" EDN contains:
      | path    | value                          |
      | content | contains "ci" and "revoked"    |

  @wip
  Scenario: a principal expiring within seven days is flagged once a day
    Given the clock is fixed at "2026-09-18T10:00:00Z"
    And principal "soon" is configured with secret "soon-secret" and scopes "hail/send" expiring "2026-09-22"
    And the Isaac server is started
    When the auth expiry sweep runs
    Then the only file in "comm/delivery/pending" EDN contains:
      | path    | value                                   |
      | content | contains "soon" and "2026-09-22"        |
    When the auth expiry sweep runs
    Then the directory "comm/delivery/pending" has exactly 1 file
    When the clock advances 1 days
    And the auth expiry sweep runs
    Then the directory "comm/delivery/pending" has exactly 2 file

  @wip
  Scenario: alerts can be turned off per kind without a restart
    Given config:
      | server.auth.alerts.first-use | false |
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with header "Authorization: Bearer ci-secret"
    Then the response status is 200
    And the directory "comm/delivery/pending" has exactly 0 file
