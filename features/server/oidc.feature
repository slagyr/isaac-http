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
