Feature: Burst control is on by default (isaac-udnm follow-up)
  Unauthenticated burst control (isaac-udnm) shipped opt-in: no :http :burst
  group meant no detection and no throttle. A public server should never run
  without it. It is now ON by default — threshold 10 unauthenticated (401/403)
  responses from one client in a 60 s window, 10 min cooldown, throttle on,
  attention on — and is turned off explicitly with :http :burst :enabled false.
  Explicit knobs override the defaults one at a time; hot-reload applies.

  Background:
    Given an Isaac root at "target/burst-default-state"
    And config:
      | key                     | value       |
      | log.output              | memory      |
      | http.host               | 0.0.0.0     |
      | http.port               | 0           |
      | http.auth.token         | s3cr3t      |
      | attention.notify.comm   | discord     |
      | attention.notify.target | boiler-room |

  @wip
  Scenario: with no burst config at all, ten unauthenticated requests trip a burst and the client is throttled
    Given the Isaac server is started
    When the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 10 times
    Then the log has entries matching:
      | level | event                  | client      | count | window-ms |
      | :warn | :server/burst-detected | 203.0.113.9 | 10    | 60000     |
    And the directory "comm/delivery/pending" has exactly 1 file
    When the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 1 times
    Then the response status is 429

  @wip
  Scenario: burst control can be turned off explicitly
    Given config:
      | key                 | value |
      | http.burst.enabled  | false |
    And the Isaac server is started
    When the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 45 times
    Then the response status is 401
    And the log has no entries matching:
      | event                  |
      | :server/burst-detected |
    And the directory "comm/delivery/pending" has exactly 0 files

  @wip
  Scenario: an explicit knob overrides its default and the others keep theirs
    Given config:
      | key                  | value |
      | http.burst.threshold | 3     |
    And the Isaac server is started
    When the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 3 times
    Then the log has entries matching:
      | level | event                  | client      | count | window-ms |
      | :warn | :server/burst-detected | 203.0.113.9 | 3     | 60000     |
    When the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 1 times
    Then the response status is 429

  @wip
  Scenario: turning burst control off on hot reload releases a throttled client
    Given config:
      | key             | value |
      | http.hot-reload | true  |
    And the Isaac server is started
    When the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 11 times
    Then the response status is 429
    When config is updated:
      | path               | value |
      | http.burst.enabled | false |
    And the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 1 times
    Then the response status is 401

  @wip
  Scenario: the effective burst config is visible with its defaults filled in
    When isaac is run with "config get http.burst"
    Then the stdout EDN contains:
      | path        | value  |
      | enabled     | true   |
      | threshold   | 10     |
      | window-ms   | 60000  |
      | cooldown-ms | 600000 |
      | throttle?   | true   |
      | notify?     | true   |

  @wip
  Scenario: a route that refuses on its own counts toward the burst
    The counter observes the RESPONSE status (401/403) in wrap-burst, not
    wrap-auth's refusal branch — so a route doing its own verification (the
    Google push door checking an OIDC token, say) is covered without knowing
    burst control exists.
    Given a fixture route POST "/fixture/self-auth" declares no scope and refuses every request with 401
    And principal "door" is configured with secret "door-secret" and scopes "*"
    And the Isaac server is started
    When the client sends POST "/fixture/self-auth" with header "X-Forwarded-For: 203.0.113.9" 10 times
    Then the log has entries matching:
      | level | event                  | client      | count |
      | :warn | :server/burst-detected | 203.0.113.9 | 10    |
    When the client sends POST "/fixture/self-auth" with header "X-Forwarded-For: 203.0.113.9" 1 times
    Then the response status is 429
