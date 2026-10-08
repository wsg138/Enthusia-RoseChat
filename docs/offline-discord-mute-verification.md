# Offline linked Discord mute verification

Owner-directed repair: `OWNER-OFFLINE-DISCORD-MUTE`, based on authoritative `wsg138/Enthusia-RoseChat:master` at `cd0290b` and Staff main at `ceb12e0f`.

## Spec -> prove -> engine -> arch -> refine

Linked Discord senders must be checked independently of their Minecraft online-session cache. An additive `enforceDiscordMute` completion-stage callback defaults to the existing mute callback for legacy bridges. No existing constructor descriptor or callback is removed.
Staff performs authoritative MUTE/PUBLIC_MUTE lookup on its bounded workers, limits outstanding work to 32, and returns unverified after two seconds. Timeout does not release admission while underlying work remains queued/running. Lookup never warms a long-lived offline cache.
RoseChat waits asynchronously, fails closed on callback errors/null/timeout/bridge replacement, and performs feedback and delivery only on its own asynchronous scheduler. Registration revision fences reject stale queued delivery. Existing Minecraft cache gates and downstream freeze, vanish, ignore/spy and filters remain intact.

## Proof and acceptance

Focused provider tests exercise pending offline verification, legacy mute enforcement, null/failure containment, timeout without mutation of the provider future, stale registration and closed lifecycle. Existing full tests/build are also run on Java 21.
A Windows-only CRLF-sensitive assertion in the existing Staff-command configuration test was normalized without changing its assertions or product configuration.
Final local Java 21 suite: 135 tests, zero failures/errors/skips; `test shadowJar` passed. Hosted review/checks and actual offline-client delivery remain separate gates.
Staff focused tests pass; its full local MariaDB validation is unavailable without Docker and five unrelated source-text tests reproduce on unchanged main. Hosted checks are not replaced by local tests.

No project-local EARS validator or SPEAR state helper exists; this manual requirement/evidence record does not claim automated EARS validation.
Both source changes are required for the offline fix. Review, hosted exact-head checks, canonical merge and authorized test-server proof remain pending. No production deployment/restart/configuration change is performed.
The Staff aggregate-copy license restriction remains unchanged; no RoseChat source is copied into Staff or a monorepo.
