Feature: Server hot-reload logging
  The server logs watcher startup and reload lifecycle so operators can
  tell whether config changes were observed and processed.

  Scenario: server start logs the hot-reload watcher it activated
    Given an Isaac root at "target/longwave-state"
    And config:
      | key               | value  |
      | log.output        | memory |
      | hot-reload | true   |
      | http.port       | 0      |
    And the Isaac server is started
    Then the log has entries matching:
      | level | event                 | root                  | impl          |
      | :info | :config.watch/started | /target/longwave-state | #*            |

  Scenario: a config edit logs detection begin and successful reload
    Given an Isaac root at "target/longwave-state"
    And config:
      | key               | value  |
      | log.output        | memory |
      | hot-reload | true   |
      | http.port       | 0      |
    And the Isaac server is started
    When the isaac EDN file "config/isaac.edn" exists with:
      | path        | value     |
      | http.host | 127.0.0.1 |
    And the isaac config is reloaded
    Then the log has entries matching:
      | level  | event                         | path      |
      | :debug | :config.watch/change-detected | isaac.edn |
      | :info  | :config.reload/begin          | isaac.edn |
      | :info  | :config/reloaded              | isaac.edn |

  Scenario: an invalid config edit logs detection begin and reload failure
    Given an Isaac root at "target/longwave-state"
    And config:
      | key               | value  |
      | log.output        | memory |
      | hot-reload | true   |
      | http.port       | 0      |
    And the Isaac server is started
    When the isaac EDN file "config/isaac.edn" exists with:
      | path        | value |
      | http.port | abc   |
    Then the log has entries matching:
      | level  | event                         | path      |
      | :debug | :config.watch/change-detected | isaac.edn |
      | :info  | :config.reload/begin          | isaac.edn |
      | :error | :config/reload-failed         | isaac.edn |
