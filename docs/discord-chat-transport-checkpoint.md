# Discord chat transport checkpoint

Tracking: `wsg138/EnthusiaStaff#268` (umbrella `wsg138/EnthusiaStaff#264`).

This checkpoint defines the RoseChat-side safety/transport boundary for retiring DiscordSRV chat transport. It intentionally does **not** remove DiscordSRV, deploy anything, create a second Discord Gateway/JDA owner, or alter the active AI moderation path.

## Canonical RoseChat export point

The outbound bridge must be invoked from `RoseChatChannel.send(...)` **after** the existing Enthusia Staff `allowChannelDispatch(...)` gate has accepted the message and beside the current `sendToDiscord(...)` stage.

Do not export from a parallel raw `AsyncChatEvent` listener. Doing so can observe a message before RoseChat/Staff moderation, mute enforcement, channel privacy, or other policy has rejected it.

The bridge must also reject any message classified `PRIVATE` or `STAFF`, and any message whose origin is Discord. Those checks are deliberately repeated in `OutboundChatBridgeCoordinator` as defense in depth.

The contract checkpoint is now followed by a bounded runtime-wiring checkpoint:

- RoseChat owns one `OutboundChatBridgeCoordinator` and registers it through Bukkit's service manager for a future provider.
- `RoseChatChannel.send(...)` invokes the coordinator only after `allowChannelDispatch(...)` accepts the message.
- only local `PLAYER_TO_SERVER` traffic is eligible for export; `SERVER_TO_SERVER` relays are skipped so a network message is not exported once per backend.
- Discord-originated traffic is offered with origin `DISCORD` only to enforce the coordinator's loop-suppression boundary.
- channel privacy is classified through the existing staff service before publication, and the coordinator still independently rejects `PRIVATE`/`STAFF`.
- the filtered `RoseMessage.playerInput` is the plain-text fallback, not the raw pre-filter input.
- the current DiscordSRV `sendToDiscord(...)` call remains in place immediately after the provider-neutral publication call.
- RoseChat shutdown unregisters and permanently closes the coordinator, preventing a stale provider registration from surviving plugin disable.

No replacement transport is installed by this checkpoint, so production Discord delivery remains unchanged. A later Staff transport checkpoint must use this service in shadow/non-duplicating form until the DiscordSRV path is explicitly cut over.

## InteractiveChat addon split

The official `LOOHP/InteractiveChat-DiscordSRV-Addon` cannot survive DiscordSRV removal unchanged because its plugin metadata hard-depends on both `InteractiveChat` and `DiscordSRV`.

### Rendering/resource logic to preserve in a minimal fork

- `graphics/ImageGeneration`
- `graphics/ImageUtils`
- item, inventory, tooltip, map and book render/resource helpers
- the `DiscordDisplayData` hierarchy and the parts of display-data processing that produce render artifacts
- InteractiveChat placeholder/component extraction performed by the outbound processing pipeline
- resource packs, textures, model renderer and NMS/resource helpers required by those renderers

### DiscordSRV/JDA transport logic to replace or split

- DiscordSRV event subscriptions in `listeners/OutboundToDiscordEvents`
- Discord-ready, Discord-command and Discord-interaction listeners
- `JDAUtils` and command/JDA helper code
- JDA construction and send operations in `DiscordMessageContent`
- JDA interaction types embedded in `InteractionHandler` and parts of `DiscordContentUtils`
- DiscordSRV/JDA-specific bootstrap registration

`DiscordContentUtils` is mixed rather than purely rendering code: it performs valuable image/render preparation but also creates JDA action rows, buttons, menus and JDA-bound message objects. A fork should split this class at the artifact boundary instead of copying its transport coupling forward.

The preferred seam is: **InteractiveChat data -> render artifacts -> provider-neutral rendered chat DTO -> Enthusia transport**. StaffBot alone turns that DTO into Discord/JDA messages.

No `wsg138` InteractiveChat / InteractiveChat-DiscordSRV-Addon fork was found during the 2026-09-30 live GitHub reconciliation. If a fork is created, upstream GPLv3+ licensing, copyright and attribution must be preserved.

## Provider-neutral contract

`OutboundChatMessage` is intentionally free of DiscordSRV/JDA classes. It carries:

- stable RoseChat event ID for duplicate suppression;
- stable central `external_message_id` for the Minecraft copy;
- stable central `canonical_message_id` that W14 must preserve on the Discord mirror;
- creation time and hard expiry for ephemeral delivery;
- logical RoseChat channel ID for explicit routing;
- privacy classification;
- origin for loop suppression;
- Minecraft UUID when present plus a safe presentation name;
- canonical bounded plain text, which remains the fallback if rich rendering fails.

The later Staff transport envelope should preserve `externalMessageId` and `canonicalMessageId` unchanged while adding the authenticated backend/server identity and explicit Minecraft-server <-> Discord-channel routing. W14 must use its own Discord snowflake as the Discord-side external ID while reusing this canonical ID; it must never reconstruct mirror identity by message text. Reply references, normalized mention intents and bounded rendered attachments belong in the rendered/transport stage rather than in RoseChat's policy event.

## Delivery semantics

`OutboundChatBridgeCoordinator` is deliberately best-effort:

- no durable queue and no MariaDB outbox;
- transport exceptions are contained and cannot cancel Minecraft chat;
- Discord-originated events are suppressed;
- only `PUBLIC` messages are eligible;
- duplicate event IDs are suppressed for a short bounded window;
- expired and oversize payloads are rejected;
- dedupe state is bounded and applies backpressure by dropping export work instead of growing without limit;
- bridge registration is single-owner and shutdown-safe.

The existing Enthusia persistent channel is the intended network envelope because it already supplies TLS 1.3, HMAC authentication, timestamp/nonce replay checks, message IDs/ACKs and reconnect behavior. Chat must **not** inherit the durable moderation-outbox semantics: a disconnected Discord bridge drops/ages out chat instead of replaying hours-old conversation later.

## Checkpoint test ownership

This RoseChat checkpoint covers public outbound success, outage fail-open, loop suppression, duplicate suppression, private/staff isolation, expiry/oversize rejection, bounded pressure and bridge shutdown/unregistration.

Later runtime/StaffBot/fork checkpoints still need coverage for:

- the exact post-moderation RoseChat wiring including muted/rejected messages;
- rich renderer success and renderer-failure plain-text fallback;
- explicit routing failures;
- authenticated Discord ingress validation and replay rejection;
- unlinked Discord identities;
- reply/mention normalization;
- attachment/embed bounds;
- transport reconnect/restart behavior and bounded send queues.
