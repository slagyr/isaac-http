Feature: OIDC/JWT identity (isaac-4sqh)
  Modules contribute data-shaped :isaac.http/identity trust rules. isaac-http
  owns the crypto: compact JWS against a JWKS document, iss/aud/exp/nbf, claim
  match. A JWT bearer is tried against matching issuers before bearer-hash
  principals. Rejections are 401 and count toward burst control.

  Background:
    Given an Isaac root at "target/oidc-state"
    And config:
      | http.host | 0.0.0.0 |
    And an OIDC trust rule for google-pubsub is registered
    And a fixture route GET "/fixture/scoped" requires scope "google/push"

  Scenario: a bearer JWT signed by the stubbed issuer key is accepted as google-pubsub
    Given the JWKS stub serves the issuer key
    And a signed JWT bearer of kind "valid"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with the signed JWT
    Then the response status is 200
    And the log has entries matching:
      | event         | principal     | uri             |
      | :http/request | google-pubsub | /fixture/scoped |

  Scenario: a JWT signed by a different key is refused with 401 :signature
    Given the JWKS stub serves the issuer key
    And a signed JWT bearer of kind "foreign-key"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with the signed JWT
    Then the response status is 401
    And the log has entries matching:
      | event         | reason     |
      | :auth/refused | :signature |

  Scenario: an expired JWT is refused with 401 :expired
    Given the JWKS stub serves the issuer key
    And a signed JWT bearer of kind "expired"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with the signed JWT
    Then the response status is 401
    And the log has entries matching:
      | event         | reason   |
      | :auth/refused | :expired |

  Scenario: a not-yet-valid JWT is refused with 401 :nbf
    Given the JWKS stub serves the issuer key
    And a signed JWT bearer of kind "nbf"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with the signed JWT
    Then the response status is 401
    And the log has entries matching:
      | event         | reason |
      | :auth/refused | :nbf   |

  Scenario: a JWT with the wrong audience is refused with 401 :audience
    Given the JWKS stub serves the issuer key
    And a signed JWT bearer of kind "wrong-aud"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with the signed JWT
    Then the response status is 401
    And the log has entries matching:
      | event         | reason    |
      | :auth/refused | :audience |

  Scenario: a JWT with the wrong issuer falls through to bearer-hash and is unknown
    Given the JWKS stub serves the issuer key
    And a signed JWT bearer of kind "wrong-iss"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with the signed JWT
    Then the response status is 401
    And the log has entries matching:
      | event         | reason   |
      | :auth/refused | :unknown |

  Scenario: an unknown kid triggers one JWKS refresh then accepts
    Given the JWKS stub misses the kid then serves it
    And a signed JWT bearer of kind "valid"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with the signed JWT
    Then the response status is 200
    And the JWKS stub was fetched 2 times

  Scenario: JWKS unreachable is refused 401 :jwks-unavailable and posts attention once
    Given config:
      | attention.notify.comm   | discord     |
      | attention.notify.target | boiler-room |
    And the JWKS stub is unreachable
    And a signed JWT bearer of kind "valid"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with the signed JWT
    Then the response status is 401
    And the log has entries matching:
      | event         | reason            |
      | :auth/refused | :jwks-unavailable |
    And the directory "comm/delivery/pending" has exactly 1 file

  Scenario: auth list shows the OIDC principal with issuer and audience and no hash
    When isaac is run with "http auth list"
    Then the exit code is 0
    And the stdout matches:
      | pattern                                      |
      | google-pubsub \(oidc\)                       |
      | accounts.lantern.test                        |
      | projects/harbor/topics/push                  |
    And the stdout does not contain "sha256"

  Scenario: a trust rule's audience and claims can point at config, so the manifest never carries deployment values
    Given config:
      | http.host                   | 0.0.0.0                     |
      | lantern.push.endpoint        | projects/harbor/topics/push |
      | lantern.push.service-account | pubsub@harbor.test          |
    And an OIDC trust rule for google-pubsub is registered with config refs
    And the JWKS stub serves the issuer key
    And a signed JWT bearer of kind "valid"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with the signed JWT
    Then the response status is 200
    When isaac is run with "http auth list"
    Then the stdout matches:
      | pattern                     |
      | projects/harbor/topics/push |

  Scenario: a trust rule whose config ref is unset is inert and the JWT falls through as unknown
    Given an OIDC trust rule for google-pubsub is registered with config refs
    And the JWKS stub serves the issuer key
    And a signed JWT bearer of kind "valid"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with the signed JWT
    Then the response status is 401
    And the log has entries matching:
      | event         | reason   |
      | :auth/refused | :unknown |

  # --- isaac-q1iu: trust rules from config (http.auth.identity) ----------------
  # Trusting an issuer is configuration, not a module change. The same
  # data-shaped rule a manifest may contribute can be declared under
  # http.auth.identity and hot-reloads like the principals beside it.

  Scenario: a trust rule declared in config is accepted as its principal (isaac-q1iu)
    Given config:
      | http.auth.identity.lantern-ci.issuer                 | https://accounts.lantern.test       |
      | http.auth.identity.lantern-ci.jwks                   | https://accounts.lantern.test/certs |
      | http.auth.identity.lantern-ci.audience               | projects/harbor/topics/push         |
      | http.auth.identity.lantern-ci.claims.email           | pubsub@harbor.test                  |
      | http.auth.identity.lantern-ci.claims.email_verified  | true                                |
      | http.auth.identity.lantern-ci.principal.name         | lantern-ci                          |
      | http.auth.identity.lantern-ci.principal.scopes       | #{:google/push}                     |
    And no OIDC trust rule is registered by any module
    And the JWKS stub serves the issuer key
    And a signed JWT bearer of kind "valid"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with the signed JWT
    Then the response status is 200
    And the log has entries matching:
      | event         | principal  | uri             |
      | :http/request | lantern-ci | /fixture/scoped |
    When isaac is run with "http auth list"
    Then the stdout matches:
      | pattern                     |
      | lantern-ci \(oidc\)         |
      | accounts.lantern.test       |
      | projects/harbor/topics/push |
    And the stdout does not contain "sha256"

  Scenario: a trust rule added to config takes effect on the next request without a restart (isaac-q1iu)
    Given no OIDC trust rule is registered by any module
    And the JWKS stub serves the issuer key
    And a signed JWT bearer of kind "valid"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with the signed JWT
    Then the response status is 401
    And the log has entries matching:
      | event         | reason   |
      | :auth/refused | :unknown |
    When config changes to:
      | http.auth.identity.lantern-ci.issuer                 | https://accounts.lantern.test       |
      | http.auth.identity.lantern-ci.jwks                   | https://accounts.lantern.test/certs |
      | http.auth.identity.lantern-ci.audience               | projects/harbor/topics/push         |
      | http.auth.identity.lantern-ci.claims.email           | pubsub@harbor.test                  |
      | http.auth.identity.lantern-ci.claims.email_verified  | true                                |
      | http.auth.identity.lantern-ci.principal.name         | lantern-ci                          |
      | http.auth.identity.lantern-ci.principal.scopes       | #{:google/push}                     |
    And the isaac config is reloaded
    And the client sends GET "/fixture/scoped" with the signed JWT
    Then the response status is 200

  Scenario: a config rule with a registered rule's id overrides it — the operator wins (isaac-q1iu)
    Given an OIDC trust rule for google-pubsub is registered
    And config:
      | http.auth.identity.google-pubsub.issuer                | https://accounts.lantern.test       |
      | http.auth.identity.google-pubsub.jwks                  | https://accounts.lantern.test/certs |
      | http.auth.identity.google-pubsub.audience              | projects/harbor/topics/push         |
      | http.auth.identity.google-pubsub.claims.email          | pubsub@harbor.test                  |
      | http.auth.identity.google-pubsub.claims.email_verified | true                                |
      | http.auth.identity.google-pubsub.principal.name        | harbor-door                         |
      | http.auth.identity.google-pubsub.principal.scopes      | #{:google/push}                     |
    And the JWKS stub serves the issuer key
    And a signed JWT bearer of kind "valid"
    And the Isaac server is started
    When the client sends GET "/fixture/scoped" with the signed JWT
    Then the response status is 200
    And the log has entries matching:
      | event         | principal   | uri             |
      | :http/request | harbor-door | /fixture/scoped |

  Scenario: config validate refuses a trust rule that cannot verify anything (isaac-q1iu)
    A rule without issuer, jwks, audience or principal would accept nothing
    or everything; it is a config error, not a silent no-op.
    Given config:
      | http.auth.identity.lantern-ci.issuer         | https://accounts.lantern.test |
      | http.auth.identity.lantern-ci.principal.name | lantern-ci                    |
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                  |
      | http\.auth\.identity\.lantern-ci.*jwks   |
    And the exit code is 1
