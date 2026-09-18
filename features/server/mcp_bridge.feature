Feature: The mcp-bridge command
  Provider modules expose their turn routes behind the server-wide bearer-token
  middleware. `isaac mcp-bridge --turn <id> --server <route-url>` is the stdio MCP server
  Claude Code spawns from its --mcp-config: it answers initialize locally,
  drops notifications, and forwards every other line as a POST, writing
  the response line back. Unknown turns come back as JSON-RPC errors so
  the CLI reports a failed tool call rather than hanging (isaac-zocg).

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | http.host       | 127.0.0.1 |
      | http.auth.token | s3cr3t    |
    And the Isaac server is started

  Scenario: mcp-bridge auth failure on tools/list is a JSON-RPC error, never plain text (isaac-o2fh)
    Given stdin is:
      """
      {"jsonrpc":"2.0","id":2,"method":"tools/list"}
      """
    When isaac is run with "mcp-bridge --turn t-ghost --server http://127.0.0.1:${server.port}/claude/turns --token wrong-token"
    Then the exit code is 0
    And the stdout matches:
      | pattern |
      | "id":2.*"error".*"code":-32001.*"unauthorized" |
    And the log has entries matching:
      | event                      |
      | :mcp-bridge/unauthorized   |
