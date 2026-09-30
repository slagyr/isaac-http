<!--
Lint convention (isaac.http.handbook-chapter-spec, isaac-mdj2): a backtick
`config:<dotted.path>` reference (no angle-bracket placeholder inside the
path) is checked against the composed config schema, and the word right
after `isaac ` in `isaac <command>` is checked against the registered
top-level CLI commands. Keep both literal and real when you write one --
the lint fails the build once either drifts from what Isaac actually
exposes. `<placeholder>` shapes (e.g. `config:<dotted.path>` itself, or
`<module-id>#<slug>`) are intentionally skipped.
-->

# isaac.http — HTTP server, auth, and public exposure

You are a crew running inside Isaac. This chapter covers what **isaac-http**
owns: the `isaac server` process, its bind and dev-mode config, inbound
authentication (bearer principals and OIDC/JWT identities), unauthenticated
burst control, the routes other modules hang off this server, and the
built-in health endpoint. Foundation's own chapter (`handbook__read` topic
`isaac.foundation`) covers config mechanics, the vocabulary table, and hot
reload — read it first if you haven't. This chapter's own topic id is
`isaac.http`; **isaac-server** git-mirrors this module (same manifest, same
id), so everything here applies whichever repo name you see in a log line
or module listing.

isaac-http does not know what a route *does* — it owns the listener, the
dispatch table, and the auth gate every request passes through before a
route handler runs. A route's own behavior (what `/hail/send` accepts, what
`/hooks/*` does with a payload) belongs to the module that registered it;
this chapter names those doors once, in Routes and doors, below, and moves
on.

## The HTTP server process

**What it is.** `isaac server` starts one long-lived process hosting two
components: `server-runtime` (reconciles config-declared comms/hail-bands/
other module state at boot) and `http` (the actual listener, built on
http-kit). Both are `:isaac/component` entries, so they only run inside the
server process — never during a plain CLI command. The process prints and
logs a `:server/hello` bookend on boot (version, runtime, root, dev mode,
pid), then `:server/started` once components are up.

Inbound bind config lives under `:http`, not the old `:server` table —
`:server :host`, `:server :port`, `:server :auth`, `:server :burst`,
`:server :hot-reload`, and `:server :suspend-timeout-ms` are all retired and
rejected at config-validate time with a message naming their replacement.
`config:http.host` defaults to `127.0.0.1` (loopback-only); `config:http.port`
defaults to `6674`. The top-level `config:hot-reload` (owned by foundation,
on by default) governs whether an edit to `:http` config takes effect
without a restart, the same as any other config table.

**Dev mode** wraps the handler with code-reload on every request instead of
using the compiled handler — it is a launch-time choice (`--dev` on `isaac
server`, or the `ISAAC_DEV` environment variable when `--dev` is omitted),
never a config path. There is no `handbook__configure` route to it.

**How to change it.**

```
config set http.host 0.0.0.0
config set http.port 8080
config set hot-reload true
```

**How to verify.** A successful bind logs `:http/listening` with the actual
host and port (read it, not the config value, if you bound port `0` for an
ephemeral port). `handbook__read` topic `config:http.port` shows the live
value plus its schema and default. `GET /status` (see Health and server
logs, below) confirms the process is up and its subsystems are healthy.

### Troubleshooting

- **The server refuses to start, logging `:server/auth-required`.** A
  non-loopback `:http :host` (anything other than `localhost`, `::1`, or a
  `127.*` address) requires a legacy `:http :auth :token` to be set —
  configuring only `:http :auth :principals` does **not** satisfy this
  startup gate by itself; the check looks at `:token` specifically. Until
  that's resolved `[verify: intentional vs. gap — isaac-http should
  probably also accept a non-empty :principals map here]`, bind a
  non-loopback host with a `:http :auth :token` set (it still works
  alongside per-principal auth; see Inbound auth, below) or keep the host
  loopback and put a reverse proxy or tunnel in front.
- **The server refuses to start, logging `:auth/config-dropped`.** Some
  earlier config write left `:http :auth` malformed enough that schema
  merge dropped it as an unknown key rather than accepting a bad shape.
  Fix the `:http :auth` table shape, then restart; this check does not
  hot-reload out of because a wedged process should not un-wedge silently.
- **A change under `:http` doesn't seem to take effect.** Confirm
  `config:hot-reload` is on (foundation's chapter, Runtime → Troubleshooting)
  before assuming anything else is wrong.
- **You wrote `:server :host` (or `:port`/`:auth`/`:burst`/`:hot-reload`/
  `:suspend-timeout-ms`) and it was rejected.** That table is fully
  retired; the validation error names the `:http` (or top-level) path to
  use instead.

## Inbound auth: principals

**What it is.** Every request answers to one of two auth shapes, both
optional independently: named bearer **principals** under
`config:http.auth.principals`, and JWT **identity** rules (see OIDC/JWT
identity, below). A principal has a name, a scope set, and only a SHA-256
hash of its secret — the secret itself is never written to config, ever. A
legacy single `config:http.auth.token` still works, transparently, as
principal `admin` holding every scope (`#{:*}`); it logs `:auth/legacy-token`
once per config generation as a nudge to migrate to a named, scoped
principal.

**If no principal, no legacy token, no identity rule, and no bearer is ever
presented, the server runs open** — every route answers with no auth check
at all. The moment any of those exist (or a client sends *any* bearer, even
to an otherwise-unconfigured server), auth turns on for the whole process:
every route is then adjudicated, and a route with no declared `:scope`
requires `:*` (admin only). A route declares the scope it needs on its
`:isaac.http/route` berth entry; the built-in `/status` route declares none.

Scope matching has one relaxation: an **un-namespaced** required scope
(`:cli`) is satisfied by *any* scope in that namespace the principal holds
(`:cli/acp`, `:cli/logs`, …), but a **namespaced** required scope
(`:cli/acp`) needs that exact scope or the wildcard `:*` — a principal
holding only `:cli` is refused a route that requires `:cli/acp`. A handler
can also demand a finer scope than its route declared, mid-handler, via
`isaac.http.auth/require-scope!` — that failure is a 403, same as any other
scope refusal, distinct from the 401 an unknown/expired/revoked credential
gets.

**How to change it.** Minting is CLI-only, deliberately — the secret is
generated and hashed in one step and never has a config-settable plaintext
form:

```
isaac http auth mint ci --scopes hail/send,cli
isaac http auth mint ci --scopes hail/send --expires 2027-01-31
isaac http auth rotate ci --overlap 24h
isaac http auth revoke ci
isaac http auth list
```

`mint`/`rotate` print the secret exactly once, on stdout, and nowhere else —
copy it immediately; it is not recoverable afterward. `rotate --overlap`
keeps the previous secret valid (as principal `<name>@prev`) until the
window elapses, so a client mid-rollover isn't cut off. Everything these
commands write (hash, scopes, expiry) lands under
`config:http.auth.principals`, so it also hot-reloads and is inspectable
through `handbook__configure`/`config get` like any other config — you just
can't mint a *new* secret except through the CLI subcommand.

**How to verify.** `isaac http auth list` prints every principal's name,
scopes, and expiry — never a hash, never a secret. `handbook__read` topic
`config:http.auth.principals` shows the live table's schema. A `401` means
no credential matched (unknown, expired, or revoked); a `403` means a
credential matched but lacked the required scope — that distinction alone
usually tells you which side of the problem you're on.

### Troubleshooting

- **A minted secret doesn't authenticate.** Confirm hot reload picked up the
  write (`config get http.auth.principals.<name> --raw`); a legacy
  `:http :auth :token` and named principals coexist fine, so check you're
  sending the right one.
- **401 vs 403 look backwards.** 401 = the server couldn't place the
  credential at all (unknown/expired/revoked, or no credential when one was
  required); 403 = the credential is real but doesn't hold the scope the
  route (or the handler) demands.
- **A route requiring `:cli` and one requiring `:cli/acp` behave
  differently for the same principal.** That's the namespace-relaxation
  rule above, not a bug — an un-namespaced requirement accepts any scope in
  that namespace; a namespaced one is exact.
- **`:auth/legacy-token` keeps appearing in the log.** It logs once per
  distinct `:http :auth` config generation, as a standing nudge — migrate
  the shared token to one or more named, scoped principals via `auth mint`
  and drop `:http :auth :token` when ready; nothing forces the move.

## OIDC/JWT identity

**What it is.** Modules (not this one) contribute data-shaped trust rules —
issuer, JWKS URL, audience, required claims, and the principal a matching
token becomes — at the `:isaac.http/identity` berth; isaac-http owns only
the verification crypto (compact JWS against a JWKS document: RS256 and
ES256, `iss`/`aud`/`exp`/`nbf`/`iat` with clock skew, exact claim match). A
rule's `:issuer`/`:audience`/`:claims` values may be a **config ref** — a
vector path resolved against live config — so a module's manifest can trust
"whatever audience this deployment configured" without hard-coding a value;
a rule with an unresolved ref verifies nothing and is silently inert. An
operator can also declare or override a trust rule directly at
`config:http.auth.identity`, keyed by rule id, with the same shape modules
contribute — an entry here whose id matches a registered rule replaces it.

A JWT-shaped bearer is tried against every registered/configured rule
**before** bearer-hash principals. A hit becomes a principal with `:oidc? true` (marking it as identity-derived
rather than a bearer-hash match, for auditing). A JWT that fails its
*matching-issuer* rule (bad signature, expired, not-yet-valid, wrong
audience, claim mismatch) is refused outright at 401 with that specific
reason; a JWT whose issuer matches no rule falls through to bearer-hash
matching and, finding nothing there either, is refused `:unknown`.

**How to change it.** The crypto knobs, not the trust rules themselves
(those are per-module or per-deployment, above):

```
config set http.oidc.skew-s 90
config set http.oidc.jwks-cache-s 1800
```

`config:http.oidc.skew-s` (default 60s) tolerates clock drift on
`exp`/`nbf`/`iat`. `config:http.oidc.jwks-cache-s` (default 3600s) bounds how
long a fetched JWKS document is cached absent a `Cache-Control: max-age`
from the issuer, which wins when present.

**How to verify.** `isaac http auth list` includes OIDC-derived rows,
labeled `(oidc)`, alongside bearer principals.

### Troubleshooting

- **A JWT that should verify keeps failing `:signature`.** Confirm the
  issuer's JWKS actually serves the key that signed the token (`kid` must
  match); an unknown `kid` triggers one automatic JWKS refresh before
  giving up.
- **The issuer is unreachable and every JWT for it now fails.** That's
  `:jwks-unavailable`, logged and, after enough consecutive failures,
  posted as an attention notice `[verify: threshold config key
  http.oidc.jwks-alert-threshold is read by the code but not currently
  declared in this module's config schema — treat it as internal until a
  schema entry ships]`. A verified JWT from the same issuer afterward
  re-arms the alert.
- **A rule with a config-ref `:audience`/`:issuer`/`:claims` never
  matches.** Check that the referenced config path actually resolves —
  an unresolved ref makes the whole rule inert, not partially applied.

## Burst control

**What it is.** A per-client sliding-window counter over **unauthenticated**
responses (401 or 403, from any route, including one that refuses on its
own logic without knowing burst control exists — the counter watches the
response status, not the auth internals). It is **on by default**:
`config:http.burst` threshold 10 refusals in a 60s window trips detection
(`:server/burst-detected`, logged once per burst, optionally posted as an
attention notice), a 600s quiet period ends it (`:server/burst-ended`), and
a detected client is answered a bare 429 (`:server/burst-throttled`, once
per burst) until the quiet period elapses. Loopback and tailnet addresses
(`100.64.0.0/10`) are still *detected and logged* like any other client, but
are never actually throttled with a 429 — useful for a trusted health
checker that happens to hit an unscoped route a lot. Sweeping stale/ended
bursts happens lazily on the next request of *any* kind, not on a timer —
there is no background sweep process to check.

**How to change it.**

```
config set http.burst.enabled false
config set http.burst.threshold 20
config set http.burst.window-ms 30000
config set http.burst.cooldown-ms 300000
config set http.burst.throttle? false
config set http.burst.notify? false
```

Each knob overrides independently; the rest keep their defaults. Setting
`config:http.burst.enabled` to `false` turns the whole feature off —
unauthenticated responses are no longer counted, logged, or throttled.

**How to verify.** `config get http.burst` shows the effective table with
defaults filled in. `isaac logs server` shows `:server/burst-detected` /
`:server/burst-throttled` / `:server/burst-ended` as they happen; burst
state itself is in-memory only (no config, no disk) and resets on restart.

### Troubleshooting

- **A client is stuck at 429 long after the problem is fixed.** The
  cooldown clock only resets on the *next* hit from that client — sending
  one more (now-successful) request won't itself un-throttle it if it still
  counts as unauthenticated; fix the credential first, then the next
  qualifying request starts the cooldown countdown honestly.
- **Notifications aren't showing up even with `notify?` true.** Burst
  attention rides the same delivery seam as any other system-scoped
  attention (`isaac.agent#comms-and-delivery`) — it needs `:attention
  :notify :comm`/`:target` configured and a delivery queue on the
  classpath; without either, the burst is still detected and logged, just
  not posted anywhere.
- **A route you expected to be burst-exempt still gets throttled.** Only
  loopback and `100.64.0.0/10` addresses are exempt from throttling
  specifically — they're still counted toward detection, and every other
  address (including anything arriving via a proxy's `X-Forwarded-For`) is
  throttled normally once detected.

## Routes and doors

**What it is.** Any module can register an inbound HTTP endpoint — often
called a **door** informally — by contributing an entry to the
`:isaac.http/route` berth: a method (or `:*` for any method), a clout path
(literal segments, `:name` params, or a trailing `*` wildcard), a handler
symbol, and an optional `:scope`. isaac-http owns matching and dispatch
(exact-path lookup first, pattern fallback) and the auth/burst/logging
middleware every route passes through; it does not know what a given door
does with the request once its handler runs. A route with no `:scope`
requires admin, same rule as Inbound auth, above.

Doors you'll see in a typical install, each documented in its owning
module's own chapter:

| Method | Path | Owning module | Scope |
|---|---|---|---|
| `POST` | `/hail/send` | `isaac.hail` | `:hail/send` |
| `GET` | `/cli` (WebSocket) | `isaac.cli-server` | `:cli` |
| `*` | `/hooks/*` | `isaac.hooks` | `:hooks` |
| `POST` | `/claude/turns/:id` | the Claude driver module | `:mcp` |
| `POST` | `/google/pubsub` | `isaac.google` | `:google/push` |
| `GET` | `/google/oauth/callback` | `isaac.google` | `:google/oauth-callback` |
| `POST` | `/foreman/events` | `isaac.foreman` | (none declared) |

This table is illustrative, not authoritative — `isaac modules show <id>`
lists exactly what a specific installed module contributes, and it's the
one to trust when this list and a live install disagree. isaac-http itself
registers two built-ins: `GET /status` (Health and server logs, below) and
`GET /error` (throws intentionally; a smoke-test fixture, not a real
capability).

**Public exposure.** A door reachable from outside the machine — whether
because `:http :host` is bound non-loopback, or because something in front
of the process (a tunnel, a reverse proxy) forwards public traffic to it —
is reachable by anyone who can reach that address, door registration alone
grants no protection. Exposure is a network/deployment decision made
outside this module's config; what this module gives you is the scope
system above, which is what actually decides whether an inbound request
without the right credential gets in. Treat "is this process reachable from
outside the machine at all" as the question that decides whether Inbound
auth (principals, a legacy token, or OIDC identity) is optional or
mandatory in practice, independent of the non-loopback-bind startup check
described in Troubleshooting, above.

**How to change it.** Routes are code (manifest contributions), not config
— there is no `handbook__configure` path to add or remove one. What *is*
config is which scope a principal holds, which is how you actually control
who can reach a given door (Inbound auth, above).

**How to verify.** `isaac modules show <id>` lists a module's route
contributions. Any door's actual auth behavior can be checked the same way
as a built-in route: send it a request with and without a credential
holding its scope and read the status code.

### Troubleshooting

- **A door I expect to exist 404s.** Confirm the contributing module is
  installed (`isaac modules list`) and actually declares that route
  (`isaac modules show <id>`) — a 404 from isaac-http means no registered
  route matched, exact or pattern, not that the module misbehaved.
- **Two modules seem to fight over one path.** Route registration follows
  the same named-collision rule as any other berth (foundation's chapter,
  Modules and berths): the later module in `:modules` order wins, logged as
  a warning, not an error.
- **A door that "checks its own auth" still gets throttled by burst
  control.** That's intentional — burst control watches the response
  status (401/403) regardless of which layer produced it, so a door with
  bespoke verification is covered automatically; see Burst control, above.

## Health and server logs

**What it is.** `GET /status` is a built-in, unscoped-by-default route
reporting overall process health: `200` with `{"status": "ok", ...}` when
every subsystem is fine, `200` with `"status": "degraded"` when something
is mid-restart, or `503` with `"status": "unhealthy"` when a subsystem is
circuit-broken (down). The body's `subsystems` map lists each tracked
subsystem's own status, restart count, and last error — this is the
fastest way to check server health without reading logs.

Every request that reaches a handler (successful or not) logs `:http/request`
at info (method, uri, status, elapsed ms, client, principal name if any);
finer request lifecycle events (`:server/request-received`,
`:server/response-sent`) log at debug. A handler that throws logs
`:server/request-failed` at error and returns a bare 500 rather than leaking
a stack trace to the client. The client address logged and passed to burst
control is the first hop of `X-Forwarded-For` when present (a proxy or
tunnel fronting the server), otherwise the raw socket peer — so a
publicly-fronted server still attributes bursts to the real originating
client, not the proxy.

Server logs are an instance of foundation's general log-stream mechanism
(`isaac.foundation#logs`) — isaac-http contributes the `server` stream
(`logs/server.log`); nothing here is a separate logging system.

**How to change it.** `/status` itself has no config. General log level and
output are foundation's `config:logging.level` / `config:logging.output`.

**How to verify.**

```
isaac logs server
```

or hit `/status` directly with a request bearing whatever scope it
currently requires (none, unless auth is on and you want to see admin-gated
health separately from a public one — there is no config to scope `/status`
specifically; it follows the same no-`:scope`-means-admin rule as any other
route once auth is on at all).

### Troubleshooting

- **`/status` itself returns 401/403.** Once *any* auth is configured
  anywhere on the server (Inbound auth, above), `/status` — having no
  declared scope — requires `:*` (admin) like any other unscoped route.
  This is consistent with the rest of the module, not a special case, but
  it surprises operators expecting a health check to always be open.
- **A request's logged client address is the proxy, not the real caller.**
  Confirm the fronting proxy/tunnel actually sets `X-Forwarded-For`; without
  it, isaac-http has only the raw socket peer to log.
- **You expect a log line on your terminal and don't see it.** Server
  process logs never print to a foreground terminal by default — read
  `isaac logs server` (foundation's chapter, Logs, covers this in general).
