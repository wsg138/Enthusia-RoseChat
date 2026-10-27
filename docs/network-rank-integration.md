# Network-owned Discord rank integration

## Spec

### REQ-NET-001 - Linked identity
WHEN DiscordSRV resolves a Minecraft account link THE SYSTEM SHALL render the existing Minecraft prefix and nickname after a gold [D] marker.

### REQ-NET-002 - Unlinked identity
IF no Minecraft account link exists THEN THE SYSTEM SHALL retain a neutral Discord nickname without granting a Minecraft rank identity.

### REQ-NET-003 - Delivery boundary
THE SYSTEM SHALL leave production files, configuration and processes unchanged during source reconciliation.

### REQ-NET-004 - Compatible upgrade
WHEN Discord placeholder defaults are reloaded THE SYSTEM SHALL fill missing entries and upgrade only exact untouched legacy text defaults while preserving custom text, explicit empty sections, hover and unrelated settings.

### REQ-NET-005 - Nested rendering
WHEN linked or unlinked Discord identity text is tokenized THE SYSTEM SHALL resolve the nested Minecraft prefix only for a linked sender and retain the neutral unlinked fallback.

## Review follow-up

Tasks: address PR #9's upgrade gap and real rendering coverage. Current canonical master remains 0f88bbd after fetch. The installed config was copied only when absent, so legacy defaults otherwise survive upgrades; static YAML assertions did not prove recursive prefix expansion. This is verified brownfield failure-path evidence, not invented historical red/green.

Architecture/evidence: `PlaceholderManager.reload`, `ConditionManager`, `RoseChatPlaceholderTokenizer`, `MessageTokenizer` and the inspected RoseGarden 1.5.7 `CommentedFileConfiguration` Reader/save APIs. Config upgrade is a platform/config adapter; no domain, account-link authority, permission or rank resolver changes. Only exact legacy text defaults are replaced, never arbitrary partially customized sections. Comments and unrelated values remain in the existing commented configuration.

Verification: five migration tests cover persisted reload/idempotence, legacy upgrade, new installs and customization preservation. Two rendering tests use the actual recursive tokenizer, shipped text conditions, color/format tokenizers and plain composer. Bukkit version, plugin manager/config and PAPI availability are test boundaries, not live providers. Hover ItemStacks and real account linkage/prefix providers remain controlled Paper/client acceptance gates. Canonical clean test/shadowJar passes 140 tests with no failures/errors/skips; the 20 staff API classes and provider packaging contract are retained. External EARS and whitespace checks pass. Hosted exact-head review/CI are still separate gates; no merge or deployment occurred.

## Task and evidence

- Integration starts from fetched canonical BadgersMC/Enthusia-RoseChat master 0f88bbd, the repository owned by the network's RoseChat gitlink. Existing wsg138 PR #21 source at c272610f is not automatically included in that pin.
- Preserve the two reviewed candidate commits via cherry-pick; no unrelated changes from the dirty development checkout are copied. This is brownfield source integration, not newly invented historical test-first evidence.
- Prove: retain the candidate's linked/unlinked/builder-reuse behavioral tests and its default-resource contract. Existing account-link lookup is the identity authority, not Discord roles or message text. No project-local SPEAR state/EARS helpers exist; use this scoped requirement/task record and the existing external EARS validator.
- Engine/arch: the DiscordSRV adapter supplies only identity placeholders. Existing rank resolver, channel routing, permission gates, provider API and persistent data remain unchanged. The review follow-up above adds a guarded config upgrade; source-only work does not authorize deploying it or reloading live configuration.
- Refine: canonical clean test/shadowJar passes 133 tests with zero failures/errors/skips. Packaging contains exactly 20 staff API class entries and no server/consumer/LumaGuilds-owned provider classes. External EARS and diff checks pass. Hosted checks and linked online/offline/unlinked client acceptance are independent gates. Keep the monorepo pin unchanged until canonical merge and verified combined build.
