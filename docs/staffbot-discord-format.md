# StaffBot Discord format

## Spec

When a channel supplies a nonblank `formats.discord-transport`, RoseChat shall use it for the
provider-neutral styled Discord line while preserving the in-game format, DiscordSRV format,
canonical message body, moderation gates, routing and message IDs. Missing/blank settings shall
preserve the current behavior. This setting affects SHADOW and AUTHORITATIVE transport alike.

## Prove

The owner supplied a screenshot of `#staff-bot-testing` showing literal `<reset>` and Markdown
markers inside rank labels. Live SMP config on inspection uses SHADOW transport; DiscordSRV
global still maps to a different channel. Source base `818e3eb` passes the in-game line format
to `publishRenderedOrFallback`, so `minecraft-to-discord` does not govern this output.
No historical failing test is claimed. Regression coverage checks a dedicated format together
with preservation of other formats, absent/blank fallback and a null caller fallback.

## Engine and architecture

Resolve the optional format before scheduling existing transport rendering. No exported DTO,
provider-neutral bridge signature, companion-plugin dependency, privacy gate or persistence
schema changes. StaffBot still owns `[SMP · linked]` identity decoration. Existing configuration
continues to work without adding a key.

## Configuration after a merged build

Add a plain-text custom placeholder in `custom-placeholders.yml`. Map actual group IDs to display
labels as needed; do not reuse styled LuckPerms prefixes.

```yaml
discord-rank:
  text:
    condition: "%vault_rank%"
    avid: "Avid"
    default: "%vault_rank%"
```

In the exported channel's `channels.yml` formats:

```yaml
discord-transport: '[%enthusiarep_score%] [{discord-rank}] %player_name%: {message}'
```

Expected body: `[11] [Avid] Kavebo: hi`. StaffBot adds its server/account decoration separately.
This configuration is not active until the merged implementation is deployed and RoseChat's
configuration is reloaded through an owner-authorized operation. Existing messages are unchanged.

## Refine and acceptance

No repository SPEAR skill, EARS validator or state helper was present in the authoritative tree;
this document is the scoped requirement/task/evidence record. Canonical validation is
`gradlew.bat --no-daemon clean test shadowJar` plus `git diff --check`.
Real Discord rendering, configured rank mappings, duplicate forwarding and reload acceptance
remain runtime checks. Do not deploy this unmerged worktree to production.

Local Java 21 validation: 150 tests passed, including two new configuration regressions;
`shadowJar` and `git diff --check` passed. The first Windows run exposed the existing
`StaffCommandOwnershipConfigTest` CRLF-sensitive resource check. Normalizing the local
`channels.yml` file to LF (no semantic config change) made the full suite pass.
The supplied interactive comparison is simulated. A publicly hosted mobile preview is not
available in this task, and the local fragment must not be described as a mobile delivery link.
