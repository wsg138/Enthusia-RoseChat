# Testing Enthusia RoseChat

RoseChat is a large Paper chat plugin with channels, filters, private/group messaging, Discord integration, placeholder conditions, tokenizers/composers, optional plugin hooks, persistence, and an EnthusiaStaff bridge. Handwritten regression tests live in this repository under `src/test/java/`.

This document distinguishes what is automated today from what still needs additional unit/integration/runtime coverage. A green build must not be interpreted as proof that every RoseChat feature has been exercised.

## Run locally

From the repository root:

```bash
./gradlew --no-daemon clean test shadowJar
```

On Windows:

```powershell
.\gradlew.bat --no-daemon clean test shadowJar
```

Run one suite with:

```bash
./gradlew test --tests dev.rosewood.rosechat.placeholder.condition.OperatorTest
```

HTML test results are written to `build/reports/tests/test/`; machine-readable XML is under `build/test-results/test/`.

The canonical GitHub Actions workflow also downloads the deployed LumaGuilds 2.1.13 API and rebuilds/tests RoseChat against it before verifying the shaded JAR.

## Automated coverage currently present

### EnthusiaStaff bridge

- `StaffChannelConfigurationTest` — normalization and channel-collision validation.
- `StaffBridgeCoordinatorTest` — bridge coordination behavior.
- `ModerationDecisionTest` — ALLOW/BLOCK/STAFF_ONLY factory semantics, feedback normalization, and required action validation.
- `StaffContextContractTest` — broadcast/private-message audit fields, required-value validation, and optional recipient normalization.

### Channels

- `ChannelConcurrencyTest` — current concurrency-sensitive channel behavior.

### Filtering and placeholder policy

- `FilterWarningTest` — stable locale keys for caps/spam/URL/language warnings.
- `OperatorTest` — case-insensitive equality/contains, all numeric comparison boundaries, stable symbols, and malformed-input fail-closed behavior.

### Coverage inventory

`FullFeatureCoverageContractTest` requires the maintained regression suites above to remain present. When a covered suite is intentionally renamed/replaced, update the inventory in the same PR. Do not satisfy the contract with an empty or source-text-only test.

## What this hardening PR adds and why

Before this campaign the repository had only three test classes despite a very large runtime surface. This branch adds deterministic tests where the behavior can be proven without booting Paper or external plugins:

- moderation decisions are a security/moderation API contract and should not silently change;
- placeholder operators parse user/admin-configured comparisons and therefore must fail closed on malformed numeric/null values;
- filter warning keys connect behavior to locale resources and are easy to break during refactors;
- Staff bridge context records carry IDs/names/classification/message/source data used across plugin boundaries and should reject missing required data;
- the inventory guard makes accidental deletion of the small existing regression base visible.

No production source, dependency version, build configuration, filter rules, credentials, or live server settings are changed by this test branch.

## Major coverage still missing

RoseChat is not yet exhaustively automated. The following are priority families for future test expansion:

1. **Message tokenizer core** — token boundaries, nested decorators, malformed markup, escaping, reset behavior and tokenizer ordering.
2. **Composers/rendering** — Adventure, Bungee, JSON, legacy, Markdown and plain-text equivalence/edge cases.
3. **Style tokenizers** — color, gradient, rainbow, format, fonts, hover/click/shadow behavior and permission interactions.
4. **FilterTokenizer and filters** — substitutions, exemptions, warning/block outcomes, caps/spam/URL/language boundaries and adversarial Unicode/spacing cases.
5. **Private messaging** — `/message`, `/reply`, ignore/toggle/social-spy behavior, offline/unknown targets, permissions and deletion IDs.
6. **Group chat** — create/invite/accept/deny/kick/leave/promote/rename/disband membership and authorization transitions.
7. **Channel behavior** — join/leave/move/mute/slowmode/toggle/visibility/radius/spy and conditional recipient rules.
8. **Persistence and migrations** — all five database migrations plus player/channel/group state round trips and restart behavior.
9. **Join/leave messages** — selection, placeholders, disabled/default behavior and reloads.
10. **Placeholder conditions** — Boolean, Number, String, Null, Compound and nested condition evaluation beyond the raw operator enum.
11. **Discord/DiscordSRV** — Minecraft↔Discord parsing, emoji/tags/channels/spoilers, webhook/message formatting and missing-provider behavior.
12. **Optional channel providers** — LumaGuilds, Towny, mcMMO, WorldGuard, Factions, KingdomsX, HuskTowns, BentoBox, SuperiorSkyblock, SimpleClans, MarriageMaster and others.
13. **Commands and argument handlers** — success, permission denial, malformed input, completions and state mutation for every registered command.
14. **Message deletion** — Adventure/Bungee deletion helper behavior and delete-command authorization/history edges.
15. **Signs and packets** — sign formatting and ProtocolLib packet-level behavior.
16. **RoseChat API/events** — event cancellation/mutation contracts and public API behavior.
17. **Plugin lifecycle/reload** — full Paper enable/disable/reload with optional dependencies present/absent.

Workers adding one of these feature families should add real behavioral tests and then extend this document and, where appropriate, the inventory guard.

## CI and how to interpret results

`.github/workflows/build.yml` is the canonical PR gate. It:

1. compiles against the fallback embedded LumaGuilds API surface;
2. downloads the deployed LumaGuilds 2.1.13 JAR;
3. runs `clean test shadowJar` against that deployed API;
4. verifies tests actually executed and none were skipped/failed/errored;
5. verifies required Staff/LumaGuilds integration classes are packaged and server/consumer APIs are not shaded incorrectly;
6. uploads the built RoseChat JAR.

Interpret failures as follows:

- **compile failure before tests** — branch/dependency/API incompatibility;
- **named JUnit failure** — inspect the exact assertion and confirm intended behavior before changing code or test expectations;
- **test-execution verification failure** — tests did not run in the quantity/shape the workflow expects;
- **provider packaging failure** — shaded artifact contract broke even if tests passed;
- **job with no steps** — GitHub runner/infrastructure failure, not a test result; rerun the unchanged head;
- **green workflow** — the current automated suite and packaging contract passed on that exact PR head, but the missing families above remain unproven.

## Runtime/manual boundaries

Use a controlled Paper test server or Sentinel-compatible runtime lane for behavior that depends on real Bukkit/Paper internals, external plugins, Discord, network/database services or client packet behavior. In particular, unit tests should not fake an optional provider and then claim production integration proof.

Never place production Discord tokens, database credentials, identifiable chat/player data, or live moderation logs in fixtures or CI output.

## Maintenance rules

- Add/update tests in the same PR as feature behavior changes.
- Keep deterministic policy/value/parser logic in fast repo-local tests.
- Use integration/runtime tests for actual Bukkit/plugin/provider interactions.
- Preserve failing regression cases when they expose genuine bugs; fix the owning production behavior rather than weakening the test.
- Update this guide whenever a new major feature family becomes covered or the canonical CI/result locations change.
