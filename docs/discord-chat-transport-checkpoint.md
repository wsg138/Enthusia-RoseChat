# Discord chat transport checkpoint

Tracking: `wsg138/EnthusiaStaff#268` (umbrella `wsg138/EnthusiaStaff#264`).

This checkpoint defines the RoseChat-side safety/transport boundary for retiring DiscordSRV chat transport. It intentionally does **not** remove DiscordSRV, deploy anything, create a second Discord Gateway/JDA owner, or alter the active AI moderation path.

## Canonical RoseChat export point

The outbound bridge must be invoked from `RoseChatChannel.send(...)` **after** the existing Enthusia Staff `allowChannelDispatch(...)` gate has accepted the message and beside the current `sendToDiscord(...)` stage.

Do not export from a parallel raw `AsyncChatEvent` listener. Doing so can observe a message before RoseChat/Staff moderation, mute enforcement, channel privacy, or other policy has rejected it.

The bridge must also reject any message classified `PRIVATE` or `STAFF`, and any message whose origin is Discord. Those checks are deliberately repeated in `OutboundChatBridgeCoordinator` as defense in depth.

## Runtime checkpoint

The provider-neutral bridge is now wired into `RoseChatChannel` at the reviewed post-policy seam:

- the call occurs only after `allowChannelDispatch(...)` has accepted the message;
- only canonical Minecraft-origin `PLAYER_TO_SERVER` messages are exported directly;
- `SERVER_TO_SERVER` export follows the existing `shouldSendBungeeToDiscord()` decision so the replacement path does not create a second network fan-out policy;
- `SERVER_TO_SERVER_RAW`, `MINECRAFT_TO_DISCORD`, and `DISCORD_TO_MINECRAFT` are not offered to the outbound bridge;
- channel classification is resolved through the active RoseChat Staff service and is passed unchanged to `OutboundChatBridgeCoordinator`, which still rejects `PRIVATE` and `STAFF` traffic;
- the bridge uses the existing RoseChat message UUID for stable `rosechat-mc-*` and `rosechat-canonical-*` identifiers;
- each outbound candidate has a 30-second best-effort lifetime and no durable retry/outbox;
- current `sendToDiscord(...)` DiscordSRV delivery remains in place immediately after the new bridge call.

`RoseChatAPI.installOutboundChatBridge(...)` is the single-owner installation surface for the later Enthusia transport runtime. Installing a bridge does not disable DiscordSRV and does not create a Discord/JDA client inside RoseChat.

This remains a dual-path migration checkpoint. Do not disable RoseChat DiscordSRV delivery until the Staff transport, StaffBot JDA egress, InteractiveChat renderer fork, routing, and non-production parity/acceptance work are complete.

## Provider-neutral Discord ingress checkpoint

The next migration checkpoint exposes one canonical RoseChat API for authenticated
Discord-to-Minecraft messages without changing the active DiscordSRV listener.

`RoseChatAPI.acceptInboundDiscordChat(...)` accepts only the bounded
`InboundDiscordChatMessage` contract. Network authentication and Discord-channel
to Minecraft-server routing remain responsibilities of the external Enthusia
transport. RoseChat then independently enforces:

- a short maximum message lifetime, bounded clock skew, and bounded duplicate cache;
- exact logical RoseChat channel lookup;
- a wrapper-aware `RoseChatChannel` implementation (group/dynamic channel types are rejected);
- public-channel classification through the current Staff policy service;
- channel mute state;
- RoseChat message filters;
- bounded line/attachment counts;
- HTTPS-only attachment references that are displayed as links and never fetched;
- linked Minecraft UUID authority separated from Discord presentation fields.

Accepted messages are converted into a Discord-proxy `RosePlayer` and dispatched
through the existing `ChannelMessageOptions.wrapper(...)` seam. That makes the
existing `DISCORD_TO_MINECRAFT` direction authoritative for Staff preflight,
recipient policy, parsing, message logging, and loop suppression. The path never
calls the Minecraft-to-Discord or Bungee fan-out stages.

Terminal policy/configuration rejections such as blocked content, private/staff
channels, muted channels, unknown routes, duplicates, and expired messages are
acknowledged-and-dropped so a transport cannot retry rejected chat until expiry.
Only transient RoseChat policy unavailability or dedupe saturation remain retryable
within the original message TTL. A post-admission dispatch failure is terminal because
partial recipient delivery cannot be ruled out safely.

This checkpoint does **not** yet install StaffBot/Velocity/Paper ingress transport,
disable the DiscordSRV listener, replace Discord message edit/delete handling, or
authorize production routing. DiscordSRV remains the live inbound provider while
the authenticated Enthusia transport is implemented and tested separately.

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

- rich renderer success and renderer-failure plain-text fallback;
- explicit routing failures;
- authenticated Discord ingress validation and replay rejection;
- unlinked Discord identities;
- reply/mention normalization;
- attachment/embed bounds;
- transport reconnect/restart behavior and bounded send queues.
