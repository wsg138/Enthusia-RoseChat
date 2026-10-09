# Central AI chat moderation (Policy v1)

RoseChat submits player-originated public chat to the central Enthusia AI
Moderation service (`POST /v1/moderate`) while preserving chat availability
when the service is slow or unavailable.

The central service is the **single semantic moderation authority**.
RoseChat enforces only the central `message_action` (`ALLOW`/`BLOCK`).
Review priority, strike recommendations, containment, and support flows are
surfaced to staff diagnostics and the audit log; RoseChat never converts them
into automatic punishments. EnthusiaStaff owns human review and all
punishment/case authority.

## Goals

- Never block the Minecraft chat pipeline on an external request for more than 300 ms (default hold: 200 ms).
- Fail open on timeout, network failure, 503 saturation, malformed responses, authentication failure, idempotency conflicts, or circuit-breaker open state.
- Keep all network I/O off the main Paper thread.
- Delete a late-BLOCKed message only when the exact RoseChat message UUID is resolved; never guess.
- Give staff a non-secret health/status view of the central client.

## Channel/profile mapping

| RoseChat surface | `ChannelClassification` | Central profile |
|---|---|---|
| Public channels | `PUBLIC` | `minecraft_public` |
| Private messages | `PRIVATE` | `minecraft_private` (same bounded central engine; authoritative UUID recipient metadata when available) |
| Staff channels | `STAFF` | exempt — bypassed locally, text never submitted |

Staff-only/configured-exempt text is never sent to the central service, not even for logging.

## Request identity and mirrors

- `external_message_id` is derived once per logical message (`rosechat-mc-<event UUID>`) and is stable across retries. It is never regenerated: changed content under the same key is a central-side conflict, not a new event.
- `canonical_message_id` (`rosechat-canonical-<event UUID>`) groups platform mirrors of the same logical message. The Minecraft original and its Discord mirror share one canonical ID so the service stores a single moderation event and replays the decision to aliases.
- The provider-neutral mirror contract: after publication, the moderation layer exposes the canonical ID for a RoseChat message UUID (`CentralModerationEngine.registerMirrorAlias`). A future Discord transport (W14) resolves the same canonical ID for the mirror copy instead of inventing a second logical message. W13 builds no second Discord transport and does not change the existing one.
- A bounded in-memory registry maps central external IDs back to the exact published RoseChat message UUIDs. Only IDs that resolve through this registry are ever deleted.

## Timing

For a player-originated public message:

1. RoseChat's local checks and message rules run first.
2. The central request starts asynchronously on a worker thread.
3. If a decision arrives before the hold deadline (default 200 ms), RoseChat applies it before broadcast: `BLOCK` suppresses the message and notifies the sender; `ALLOW` publishes normally.
4. If no decision is available at the deadline, RoseChat publishes normally (fail open).
5. A later `BLOCK` deletes the already-published message only when the exact message UUID resolves through the ID registry, then notifies the sender. If the UUID cannot be resolved, RoseChat refuses to guess and alerts staff instead.

An atomic message lifecycle prevents a response racing the timeout from publishing or enforcing twice.

Private messages use the same bounded central engine after RoseChat's local PM checks and message rules. An early `BLOCK` suppresses delivery. If the hold expires, the PM fails open and is delivered after mutable local permission/mute state is revalidated. RoseChat has no authoritative post-send PM retraction primitive, so a later `BLOCK` is audited and surfaced to staff instead of guessing a deletion or telling the sender the message was removed.

## Friendly block explanations

For a successfully enforced early BLOCK, RoseChat already has a private
`notifyBlocked` hook. It now prefers the optional central
`player_notice` field instead of displaying raw classifier constants such
as `SEVERE_HARASSMENT`. The central message is a restrained, fixed-format
explanation (e.g. possible harassment or prohibited language) plus a reminder
to contact staff if it was a mistake.

The client refuses malformed, oversized or markup-bearing notice text and
falls back to a generic message. For Minecraft private messages, the notice
correctly says "Your private message was blocked"; public chat uses "Your
message was blocked". A successfully removed public message uses the
"Your public message was removed" wording. The existing exact-message
ledger prevents duplicate enforcement/notifications on retries.

RoseChat does not claim a late, already-delivered private message was
retracted. Late PM blocks still go to the staff diagnostic flow without a
false player deletion notice. Fail-open, degraded, ALLOW and exempt responses
are never presented as player blocks. The API still determines the action;
the notice itself confers no punishment/strike authority.

This integration depends on an independently approved central API version
which supplies `player_notice`. Earlier API versions remain compatible:
RoseChat uses a generic safe notice when that field is absent. Nothing in
this draft enables production blocking, changes channel exemptions or
requests a live server reload.

## Failure behavior

Moderation is a soft subsystem. It has no authority to take chat down.

- Connect failure, request timeout, HTTP 503, malformed responses, and authentication/config failures all fail open for ordinary chat.
- A central `409 Conflict` is treated as an idempotency/integration defect: fail open, keep the original IDs (never regenerate, never retry in a loop), record a bounded diagnostic, and alert staff.
- A response with `degraded=true` or `ingestion_status=FAIL_OPEN` is always treated as `ALLOW`.
- Repeated remote failures open a circuit breaker. While open, messages pass through without central moderation. After the configured open interval expires, the next eligible message reprobes the service. Staff with the AI moderation status permission are warned on login while the subsystem is degraded/down.

## Retroactive deletion (`related_messages`)

When a central response names prior messages:

- Only structured entries with `platform: minecraft` are considered. Discord/foreign-platform entries are ignored for local deletion (the Discord listener owns that surface).
- Only entries whose `external_message_id` resolves through this instance's ID registry are deleted, and only by their exact RoseChat message UUID.
- Unresolvable references are skipped, never guessed.
- Work is bounded per response and idempotent per external ID: the current and retroactive paths share one deletion ledger, so a message is never deleted twice.

## Health/status/metrics

`/rosechat ai status` (and the manager's health surface) shows bounded, non-secret information:

- central configured/enabled state and credential provenance (`client-id via config`, `token via env:NAME` — values never shown);
- circuit status and in-flight depth;
- request success/failure/timeout/conflict/degraded counts;
- p50/p95/p99 client-observed latency;
- ALLOW/BLOCK counts and late deletions;
- last failure category;
- central policy/model versions from the last response.

Bearer tokens and full credential material never appear in logs, status output, or diagnostics.

## Configuration

```yaml
central:
  enabled: true
  base-uri: 'http://10.0.0.5:8080'   # private network URI; never public
  client-id: ''                       # or ROSECHAT_MODERATION_CLIENT_ID
  client-id-environment-variable: ROSECHAT_MODERATION_CLIENT_ID
  token-environment-variable: ROSECHAT_MODERATION_TOKEN  # token ONLY from env
  scope-id: ''                        # defaults to the channel id
  request-timeout-ms: 2000
```

The bearer token is never read from the YAML file. The base URI must be reachable over the private network; `127.0.0.1` of another container is not assumed. If the service is unreachable, chat keeps working and staff are warned — the private-network route is an operator dependency, not something this client fixes by exposing the API publicly.

## Legacy migration

The legacy OpenAI threshold policy, the direct OpenAI production path, the local prose-transcript context buffer, and the local strike-ledger escalation are **retired as production authorities**. They are marked `@Deprecated` in code and in `ai-moderation.yml`; old config files still parse (fields are ignored with a warning where they previously had an effect).

- `/rosechat ai test` now probes the central service (diagnostic only, never enforces).
- `/rosechat ai inspect <message>` remains as an explicitly labeled legacy OpenAI diagnostic; it cannot enforce or record strikes.
- The `strikes.*` and `policy.*` config sections are parsed for migration compatibility but decide nothing in central mode.
- `punishments.enabled=true` in an old file logs a warning and has no effect: central mode never records AI strikes or requests automatic punishments.

## EnthusiaStaff integration

RoseChat does not dispatch punishment command strings and no longer submits automatic public-mute requests. EnthusiaStaff owns the durable public case and sanction; human review happens through the central review API (W15), not through RoseChat.
