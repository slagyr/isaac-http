Feature: Unauthenticated burst control
  A vulnerability scanner sent ~600 requests through Tailscale Funnel on
  2026-09-11; every one got a 401 in about a millisecond and nobody was told.
  wrap-burst counts unauthenticated (401/403) responses per client
  (:client — first X-Forwarded-For hop, else the socket peer) in a sliding
  window. Over the threshold: one :server/burst-detected and ONE attention
  post per burst, whatever its length; when the client goes quiet for the
  cooldown, :server/burst-ended with the total. Optionally (throttle? true)
  a flagged client is answered with a bare 429 before the auth
  check for the cooldown — never for loopback or tailnet clients. Config is
  the :http :burst group; on by default, off with :enabled false (isaac-xc08).

  Background:
    Given an Isaac root at "target/burst-state"
    And config:
      | key                      | value       |
      | log.output               | memory      |
      | hot-reload        | false       |
      | http.port              | 0           |
      | http.auth.token        | s3cr3t      |
      | http.burst.threshold   | 30          |
      | http.burst.window-ms   | 60000       |
      | http.burst.cooldown-ms | 600000      |
      | http.burst.throttle?   | false       |
      | attention.notify.comm    | discord     |
      | attention.notify.target  | boiler-room |
    And the Isaac server is started

  Scenario: thirty unauthenticated requests from one client raise one attention post
    When the client sends GET "/wp-json/wc/v3/payment_gateways" with header "X-Forwarded-For: 203.0.113.9" 29 times
    Then the log has no entries matching:
      | event                  |
      | :server/burst-detected |
    When the client sends GET "/wp-content/debug.log" with header "X-Forwarded-For: 203.0.113.9" 1 times
    Then the log has entries matching:
      | level | event                  | client      | count | window-ms |
      | :warn | :server/burst-detected | 203.0.113.9 | 30    | 60000     |
    And the directory "comm/delivery/pending" has exactly 1 file
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                                                                          |
      | comm    | :discord                                                                       |
      | target  | boiler-room                                                                    |
      | content | contains "203.0.113.9" and "30 requests" and "/wp-json/wc/v3/payment_gateways" |

  Scenario: a burst that keeps going posts nothing more
    When the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 30 times
    And the client sends GET "/xmlrpc.php" with header "X-Forwarded-For: 203.0.113.9" 60 times
    Then the log has entries matching:
      | level | event                  | client      | count |
      | :warn | :server/burst-detected | 203.0.113.9 | 30    |
    And the directory "comm/delivery/pending" has exactly 1 file

  Scenario: the window is per client
    When the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 29 times
    And the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.10" 29 times
    Then the log has no entries matching:
      | event                  |
      | :server/burst-detected |
    And the directory "comm/delivery/pending" has exactly 0 files

  Scenario: a quiet cooldown ends the burst with a total
    Given the clock is fixed at "2026-03-01T10:00:00Z"
    When the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 45 times
    And the clock is fixed at "2026-03-01T10:10:01Z"
    And the client sends GET "/status" with header "Authorization: Bearer s3cr3t" 1 times
    Then the log has entries matching:
      | level | event               | client      | total |
      | :info | :server/burst-ended | 203.0.113.9 | 45    |
    And the directory "comm/delivery/pending" has exactly 2 files

  Scenario: throttle answers a flagged client with a bare 429 before auth, others unaffected
    Given config:
      | key                    | value |
      | http.burst.throttle? | true  |
    And the Isaac server is started
    When the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 30 times
    And the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 1 times
    Then the response status is 429
    And the response body is empty
    And the response has no header "WWW-Authenticate"
    When the client sends GET "/status" 1 times with headers:
      | Authorization   | Bearer s3cr3t |
      | X-Forwarded-For | 203.0.113.10  |
    Then the response status is 200
    And the log has entries matching:
      | level | event                   | client      |
      | :info | :server/burst-throttled | 203.0.113.9 |

  @wip
  Scenario: the ended post counts throttled requests, not just the ones that reached auth
    Given config:
      | key                  | value |
      | http.burst.throttle? | true  |
    And the Isaac server is started
    And the clock is fixed at "2026-03-01T10:00:00Z"
    When the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 30 times
    And the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 20 times
    And the clock is fixed at "2026-03-01T10:10:01Z"
    And the client sends GET "/status" with header "Authorization: Bearer s3cr3t" 1 times
    Then the log has entries matching:
      | level | event               | client      | total | throttled |
      | :info | :server/burst-ended | 203.0.113.9 | 30    | 20        |
    And the newest file in "comm/delivery/pending" EDN contains:
      | path    | value                                        |
      | content | contains "30 refused" and "20 throttled"     |

  @wip
  Scenario: throttled traffic keeps the burst alive
    Given config:
      | key                  | value |
      | http.burst.throttle? | true  |
    And the Isaac server is started
    And the clock is fixed at "2026-03-01T10:00:00Z"
    When the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 30 times
    And the clock is fixed at "2026-03-01T10:09:00Z"
    And the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 5 times
    And the clock is fixed at "2026-03-01T10:10:01Z"
    And the client sends GET "/status" with header "Authorization: Bearer s3cr3t" 1 times
    Then the log has no entries matching:
      | event               |
      | :server/burst-ended |
    When the clock is fixed at "2026-03-01T10:19:01Z"
    And the client sends GET "/status" with header "Authorization: Bearer s3cr3t" 1 times
    Then the log has entries matching:
      | level | event               | client      |
      | :info | :server/burst-ended | 203.0.113.9 |

  @wip
  Scenario: the ended post reports the burst's own span, not the wait for the sweep
    Given the clock is fixed at "2026-03-01T10:00:00Z"
    When the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 30 times
    And the clock is fixed at "2026-03-01T10:00:30Z"
    And the client sends GET "/.env" with header "X-Forwarded-For: 203.0.113.9" 15 times
    And the clock is fixed at "2026-03-01T10:10:31Z"
    And the client sends GET "/status" with header "Authorization: Bearer s3cr3t" 1 times
    Then the newest file in "comm/delivery/pending" EDN contains:
      | path    | value                 |
      | content | contains "30000ms"    |
    And the log has entries matching:
      | level | event               | client      | duration-ms |
      | :info | :server/burst-ended | 203.0.113.9 | 30000       |

  Scenario: loopback is never throttled
    Given config:
      | key                    | value |
      | http.burst.throttle? | true  |
    And the Isaac server is started
    When the client sends GET "/.env" 31 times
    Then the response status is 401
    And the log has no entries matching:
      | event                   |
      | :server/burst-throttled |

  Scenario: config schema lists the burst knobs
    When isaac is run with "config schema http.burst"
    Then the stdout matches:
      | pattern      |
      | :threshold   |
      | :window-ms   |
      | :cooldown-ms |
      | :notify\?    |
      | :throttle\?  |
      | :enabled     |
