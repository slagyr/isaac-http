Feature: Server lifecycle bookends
  The Marigold's console announces itself when it powers on and bids the
  crew farewell when it powers down.

  Background:
    Given default Grover setup

  Scenario: the console greets the crew on boot
    Given config:
      | key               | value |
      | http.auth.token | shh   |
    When the Isaac server is started
    Then the log has entries matching:
      | level | event         | runtime | version | root | dev   | pid |
      | :info | :server/hello | #*      | #*      | #*   | false | #*  |

  Scenario: the runner records shutdown
    Given config:
      | key               | value |
      | http.auth.token | shh   |
    When the Isaac server is started
    And the Isaac server is stopped
    Then the log has entries matching:
      | level | event           |
      | :info | :runner/stopped |