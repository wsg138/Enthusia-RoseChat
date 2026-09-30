# OpenAI chat moderation

RoseChat can optionally classify player-originated public chat with OpenAI's moderation endpoint while preserving chat availability when the remote service is slow or unavailable.

## Goals

- Use `omni-moderation-latest` for public player chat.
- Never block the Minecraft chat pipeline on an external request for more than 300 ms.
- Fail open on timeout, network failure, rate limiting, authentication failure, malformed responses, or circuit-breaker open state.
- Preserve enough recent channel context to distinguish ordinary Minecraft combat language from targeted abuse.
- Treat OpenAI category scores as signals. RoseChat's Minecraft-specific policy decides whether to allow, review, or delete a message.
- Notify the sender when an enforcement-level flag blocks or deletes a message.
- Count only enforcement-level flags as strikes. Two strikes in a rolling hour request a 30-day public mute from EnthusiaStaff.
- Persist the rolling strike window across RoseChat restarts without making RoseChat the punishment authority.
- Warn staff on every login while moderation is configured/enabled but unhealthy.

## Context model

Each initial moderation request carries two independent text inputs:

1. the target message by itself;
2. a bounded channel transcript containing recent messages before the target and the target itself.

The transcript uses explicit target markers and metadata, including the target index and whether the target is currently at the start, middle, or end of the available window. Keeping the target-only input separate prevents harmful text in neighboring messages from being attributed directly to the target.

A short follow-up context window may be used when the target itself is already near an Enthusia enforcement threshold and later chat arrives. The target remains explicitly marked and can therefore move from `END` to `MIDDLE`/`START`. Follow-up context is corroborating evidence only; neighboring content alone must not create a strike for an otherwise clean target. A high generic `violence` score by itself does not trigger the follow-up request.

## Minecraft-specific policy

The endpoint's top-level `flagged` value is not the deletion switch. In particular, generic `violence` is not an enforcement category because normal gameplay includes language such as `I killed him`, `die`, `fight me`, and `I'm going to kill you` in an in-game context.

More weight is given to targeted harassment, threatening harassment, hate, threatening hate, self-harm instructions, sexual content involving minors, graphic violence, and illicit violent content. Thresholds are configuration values and should be tuned in shadow mode before production enforcement.

A player strike is created only when RoseChat's policy returns `DELETE`, not merely because OpenAI reports `flagged=true` or a high generic-violence score.

## Timing

For a player-originated public message:

1. RoseChat's local checks and message rules run first.
2. The moderation request starts asynchronously.
3. If an enforcement decision arrives before 300 ms, RoseChat applies it before broadcast.
4. If no decision is available at 300 ms, RoseChat broadcasts normally.
5. A later enforcement decision deletes the already-published message only when RoseChat can uniquely resolve that exact message UUID, then notifies the sender.

An atomic message lifecycle prevents a response racing the timeout from broadcasting or enforcing twice. If late deletion cannot uniquely identify the target message, RoseChat refuses to guess and alerts staff instead of deleting a different message.

## Strike persistence

Only `DELETE` decisions enter the strike ledger. The ledger stores bounded rolling timestamps in `ai-moderation-strikes.tsv` under RoseChat's data directory and uses an atomic file replacement where the filesystem supports it. Expired timestamps are discarded when the ledger is read or updated.

The ledger exists only to preserve the one-hour escalation window across RoseChat restarts. It is not a punishment database. EnthusiaStaff remains authoritative for the resulting case and sanction.

## Failure behavior

Moderation is a soft subsystem. It has no authority to take chat down.

Repeated remote failures open a circuit breaker. While open, messages are sent without AI moderation. After the configured open interval expires, the next eligible public message is allowed to test the remote service again. Staff with the AI moderation status permission are warned on login while the subsystem is degraded/down.

## EnthusiaStaff integration

RoseChat does not dispatch punishment command strings. An optional integration contract is used so EnthusiaStaff remains the punishment authority. RoseChat owns only the persisted rolling enforcement-strike timestamps and submits a mute request with an idempotency key and moderation metadata after the configured threshold is reached.

EnthusiaStaff owns the durable public case and sanction. The companion integration uses a dedicated `chat.ai-moderation` policy and a 30-day public-chat-only mute: public chat is blocked while private messages remain available. Staff also checks for an already-active public mute before creating another case.

If EnthusiaStaff is absent or the integration is unavailable, chat moderation still works. RoseChat alerts that automatic sanction escalation is unavailable rather than substituting another punishment implementation.
