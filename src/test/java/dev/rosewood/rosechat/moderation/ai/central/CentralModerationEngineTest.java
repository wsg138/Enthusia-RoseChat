package dev.rosewood.rosechat.moderation.ai.central;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rosewood.rosechat.moderation.ai.AiModerationMetrics;
import dev.rosewood.rosechat.moderation.ai.AiModerationPolicy;
import dev.rosewood.rosechat.moderation.ai.OpenAiModerationClient;
import dev.rosewood.rosechat.moderation.ai.central.CentralModerationEngine.AuditRecord;
import dev.rosewood.rosechat.moderation.ai.central.CentralModerationEngine.EngineParams;
import dev.rosewood.rosechat.moderation.ai.central.CentralModerationEngine.PendingMessage;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle tests for {@link CentralModerationEngine} with deterministic
 * fakes. No live central service, no Bukkit.
 *
 * <p>Covers required tests 1-15, 19 and 20.</p>
 */
class CentralModerationEngineTest {
    private ScheduledExecutorService scheduler;
    private Executor networkExecutor;
    private FakeTransport transport;
    private FakeActions actions;
    private AiModerationMetrics metrics;
    private CentralModerationEngine engine;
    private Thread testThread;

    static final class FakeTransport implements CentralModerationEngine.Transport {
        final AtomicReference<Thread> callThread = new AtomicReference<>();
        final List<CentralModerationRequest> requests = new CopyOnWriteArrayList<>();
        volatile Function<CentralModerationRequest, CompletableFuture<CentralModerationDecision>> behavior =
                request -> CompletableFuture.completedFuture(allowDecision("SAFE"));

        @Override
        public CompletableFuture<CentralModerationDecision> moderate(CentralModerationRequest request) {
            callThread.set(Thread.currentThread());
            requests.add(request);
            return behavior.apply(request);
        }
    }

    static final class FakeActions implements CentralModerationEngine.Actions {
        final List<PendingMessage> published = new CopyOnWriteArrayList<>();
        final List<UUID> deleted = new CopyOnWriteArrayList<>();
        final List<String> blockedNotices = new CopyOnWriteArrayList<>();
        final List<String> removedNotices = new CopyOnWriteArrayList<>();
        final List<String> staffAlerts = new CopyOnWriteArrayList<>();
        final List<AuditRecord> audits = new CopyOnWriteArrayList<>();
        final ConcurrentLinkedQueue<Thread> publishThreads = new ConcurrentLinkedQueue<>();

        @Override
        public void publish(PendingMessage pending) {
            publishThreads.add(Thread.currentThread());
            published.add(pending);
        }

        @Override
        public void notifyBlocked(UUID senderId, String notice) {
            blockedNotices.add(notice);
        }

        @Override
        public void notifyRemoved(UUID senderId, String notice) {
            removedNotices.add(notice);
        }

        @Override
        public void deleteExactMessage(UUID rosechatMessageId) {
            deleted.add(rosechatMessageId);
        }

        @Override
        public void alertStaff(String detail) {
            staffAlerts.add(detail);
        }

        @Override
        public void audit(AuditRecord record) {
            audits.add(record);
        }
    }

    @BeforeEach
    void setUp() {
        testThread = Thread.currentThread();
        scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "test-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        networkExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-network");
            thread.setDaemon(true);
            return thread;
        });
        transport = new FakeTransport();
        actions = new FakeActions();
        metrics = new AiModerationMetrics();
        engine = new CentralModerationEngine(
                transport,
                actions,
                new EngineParams(
                        Duration.ofMillis(60),
                        Duration.ofSeconds(2),
                        2,
                        Duration.ofMillis(400),
                        64,
                        450,
                        8,
                        25,
                        32,
                        20L,
                        3
                ),
                metrics,
                Clock.systemUTC(),
                scheduler,
                networkExecutor
        );
    }

    @AfterEach
    void tearDown() {
        engine.retire();
        scheduler.shutdownNow();
        ((java.util.concurrent.ExecutorService) networkExecutor).shutdownNow();
    }

    private PendingMessage pending() {
        return pending(UUID.randomUUID());
    }

    private PendingMessage pending(UUID eventId) {
        return new PendingMessage(
                eventId,
                ChannelProfile.MINECRAFT_PUBLIC,
                "global",
                "global",
                "",
                List.of(),
                UUID.randomUUID(),
                "Steve",
                "hello world"
        );
    }

    private PendingMessage privatePending(boolean lateDeletionSupported) {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        return new PendingMessage(
                UUID.randomUUID(),
                ChannelProfile.MINECRAFT_PRIVATE,
                "smp",
                "private",
                "minecraft-private:" + senderId + ':' + recipientId,
                List.of(recipientId),
                senderId,
                "Steve",
                "private hello",
                lateDeletionSupported
        );
    }

    static CentralModerationDecision allowDecision(String label) {
        return new CentralModerationDecision(
                CentralModerationDecision.MessageAction.ALLOW, "INGESTED", false, label,
                0.99D, "NONE", "NONE", "NONE", List.of(), List.of(), "policy-v1", "m1", "", false);
    }

    static CentralModerationDecision blockDecision(String label, List<RelatedMessageRef> related) {
        return new CentralModerationDecision(
                CentralModerationDecision.MessageAction.BLOCK, "INGESTED", false, label,
                0.99D, "URGENT", "STRIKE", "MUTE", List.of("TARGETED_ABUSE"), related,
                "policy-v1", "m1", "", false);
    }

    static CentralModerationDecision degradedAllow() {
        return new CentralModerationDecision(
                CentralModerationDecision.MessageAction.ALLOW, "FAIL_OPEN", true, "SAFE",
                null, "NONE", "NONE", "NONE", List.of(), List.of(), "policy-v1", "m1",
                "classifier_error", false);
    }

    private static void await(Supplier<Boolean> condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.get()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for: " + what);
            }
        }
    }

    // ---- Required test 1: ALLOW before the hold deadline ----

    @Test
    void allowBeforeHoldDeadlinePublishesOnce() {
        PendingMessage message = pending();
        engine.submit(message);

        await(() -> actions.published.size() == 1, "message publication");

        assertEquals(1, transport.requests.size(), "exactly one central request");
        assertEquals(0, actions.deleted.size());
        assertEquals(1, metrics.snapshot().allows());
        assertTrue(actions.audits.stream().anyMatch(a -> "CENTRAL_ALLOW".equals(a.outcome())));
    }

    // ---- Required test 2: BLOCK before publish ----

    @Test
    void blockBeforePublishNeverPublishes() {
        transport.behavior = request -> CompletableFuture.completedFuture(
                blockDecision("SEVERE_HARASSMENT", List.of()));
        PendingMessage message = pending();
        engine.submit(message);

        await(() -> actions.blockedNotices.size() == 1, "block notice");

        assertTrue(actions.published.isEmpty(), "blocked message must never be published");
        assertEquals(1, metrics.snapshot().centralBlocked());
        assertTrue(actions.blockedNotices.get(0).contains("SEVERE_HARASSMENT"));
        assertTrue(actions.audits.stream().anyMatch(a -> "CENTRAL_BLOCK_PRE_BROADCAST".equals(a.outcome())));
    }

    @Test
    void privateMessageUsesPrivateProfileAndAuthoritativeRecipient() {
        PendingMessage message = privatePending(false);
        engine.submit(message);

        await(() -> actions.published.size() == 1, "private publication");
        await(() -> transport.requests.size() == 1, "private central request");

        CentralModerationRequest request = transport.requests.get(0);
        assertEquals(ChannelProfile.MINECRAFT_PRIVATE, request.profile());
        assertEquals("private", request.channelId());
        assertEquals(message.conversationId, request.conversationId());
        assertEquals(message.recipientIds, request.recipientIds());
        assertEquals(message.externalMessageId, request.externalMessageId());
        assertEquals(message.canonicalMessageId, request.canonicalMessageId());
    }

    @Test
    void privateBlockBeforePublishUsesPrivateSurfaceAndNeverPublishes() {
        transport.behavior = request -> CompletableFuture.completedFuture(
                blockDecision("SEVERE_HARASSMENT", List.of()));
        PendingMessage message = privatePending(false);
        engine.submit(message);

        await(() -> actions.blockedNotices.size() == 1, "private block notice");

        assertTrue(actions.published.isEmpty());
        assertTrue(actions.blockedNotices.get(0).contains("private message"));
        assertTrue(actions.audits.stream().anyMatch(
                audit -> "CENTRAL_BLOCK_PRE_BROADCAST".equals(audit.outcome())));
    }

    @Test
    void latePrivateBlockDoesNotClaimOrGuessRetraction() {
        CompletableFuture<CentralModerationDecision> future = new CompletableFuture<>();
        transport.behavior = request -> future;
        PendingMessage message = privatePending(false);
        engine.submit(message);

        await(() -> actions.published.size() == 1, "private fail-open publication");
        future.complete(blockDecision("SEVERE_HARASSMENT", List.of()));

        await(() -> actions.audits.stream().anyMatch(
                audit -> "CENTRAL_BLOCK_LATE_UNRETRACTABLE".equals(audit.outcome())),
                "late private block audit");

        assertTrue(actions.deleted.isEmpty(), "private late block must not guess a deletion target");
        assertTrue(actions.removedNotices.isEmpty(),
                "sender must not be told an already-delivered private message was removed");
        assertTrue(actions.staffAlerts.stream().anyMatch(alert -> alert.contains("LATE BLOCK")));
    }

    // ---- Required test 3: timeout -> publish/fail open ----

    @Test
    void timeoutFailsOpen() {
        transport.behavior = request -> CompletableFuture.failedFuture(
                new CentralModerationException.TimedOut("deadline", null));
        PendingMessage message = pending();
        engine.submit(message);

        await(() -> actions.published.size() == 1, "fail-open publication");

        assertEquals(1, metrics.snapshot().centralTimeouts());
        assertTrue(actions.deleted.isEmpty());
        assertTrue(actions.audits.stream().anyMatch(a -> "CENTRAL_TIMEOUT_FAIL_OPEN".equals(a.outcome())));
    }

    // ---- Required test 4: 503 -> fail open ----

    @Test
    void serviceUnavailableFailsOpen() {
        transport.behavior = request -> CompletableFuture.failedFuture(
                new CentralModerationException.Unavailable("saturated"));
        PendingMessage message = pending();
        engine.submit(message);

        await(() -> actions.published.size() == 1, "fail-open publication");

        assertEquals(1, metrics.snapshot().centralUnavailable());
        assertTrue(actions.deleted.isEmpty());
    }

    // ---- Required test 5: central degraded/fail-open response -> allow ----

    @Test
    void degradedResponseAllowsAndNeverBlocks() {
        transport.behavior = request -> CompletableFuture.completedFuture(new CentralModerationDecision(
                CentralModerationDecision.MessageAction.BLOCK, "INGESTED", true, "AMBIGUOUS_REVIEW",
                null, "NORMAL", "NONE", "NONE", List.of(), List.of(), "policy-v1", "m1",
                "classifier_error", false));
        PendingMessage message = pending();
        engine.submit(message);

        await(() -> actions.published.size() == 1, "degraded allow publication");

        assertEquals(1, metrics.snapshot().centralDegraded());
        assertEquals(0, metrics.snapshot().centralBlocked());
        assertTrue(actions.blockedNotices.isEmpty());
        assertTrue(actions.audits.stream().anyMatch(a -> "CENTRAL_DEGRADED_ALLOW".equals(a.outcome())));
    }

    // ---- Required test 6: 409 id conflict -> fail open + diagnostic, no ID regeneration loop ----

    @Test
    void conflictFailsOpenWithDiagnosticAndNoRetryLoop() {
        transport.behavior = request -> CompletableFuture.failedFuture(
                new CentralModerationException.Conflict("idempotency conflict", "key reused with different input"));
        PendingMessage message = pending();
        String externalId = message.externalMessageId;
        engine.submit(message);

        await(() -> actions.published.size() == 1, "conflict fail-open publication");

        assertEquals(1, transport.requests.size(), "a 409 must not trigger retries with mutated IDs");
        assertEquals(externalId, transport.requests.get(0).externalMessageId());
        assertEquals(1, metrics.snapshot().centralConflicts());
        List<CentralModerationEngine.ConflictDiagnostic> diagnostics = engine.health().conflictDiagnostics();
        assertEquals(1, diagnostics.size());
        assertEquals(externalId, diagnostics.get(0).externalMessageId());
        assertTrue(actions.staffAlerts.stream().anyMatch(alert -> alert.contains("409")),
                "staff must be alerted about the integration defect");
        assertTrue(actions.audits.stream().anyMatch(a -> "CENTRAL_CONFLICT_FAIL_OPEN".equals(a.outcome())));
        assertFalse(engine.health().circuitOpen(), "a 409 is an integration defect, not a service outage");
    }

    // ---- Required test 7: exact late BLOCK deletion ----

    @Test
    void lateBlockDeletesExactlyTheResolvedMessage() {
        CompletableFuture<CentralModerationDecision> future = new CompletableFuture<>();
        transport.behavior = request -> future;
        PendingMessage message = pending();
        UUID exactMessageId = UUID.randomUUID();
        engine.submit(message);

        await(() -> actions.published.size() == 1, "fail-open publication before late BLOCK");
        engine.notePublished(message.externalMessageId, exactMessageId);
        future.complete(blockDecision("SEVERE_HARASSMENT", List.of()));

        await(() -> actions.deleted.size() == 1, "late deletion");

        assertEquals(exactMessageId, actions.deleted.get(0), "only the exact resolved UUID may be deleted");
        assertEquals(1, actions.removedNotices.size());
        assertEquals(1, metrics.snapshot().lateDeletes());
    }

    // ---- Required test 8: late deletion cannot resolve exact UUID -> no guessed deletion ----

    @Test
    void lateBlockWithoutResolvableUuidDeletesNothing() {
        CompletableFuture<CentralModerationDecision> future = new CompletableFuture<>();
        transport.behavior = request -> future;
        PendingMessage message = pending();
        engine.submit(message);

        await(() -> actions.published.size() == 1, "fail-open publication before late BLOCK");
        future.complete(blockDecision("SEVERE_HARASSMENT", List.of()));

        await(() -> actions.staffAlerts.stream().anyMatch(a -> a.contains("could not uniquely identify")),
                "staff alert for unresolvable late deletion");

        assertTrue(actions.deleted.isEmpty(), "must never guess a message UUID");
    }

    // ---- Required test 9: retroactive related_messages delete only exact Minecraft refs ----

    @Test
    void retroactiveRelatedMessagesDeleteOnlyResolvableMinecraftRefs() {
        UUID uuidA = UUID.randomUUID();
        UUID uuidB = UUID.randomUUID();
        String externalA = CentralModerationIds.externalMessageId(UUID.randomUUID());
        String externalB = CentralModerationIds.externalMessageId(UUID.randomUUID());
        engine.notePublished(externalA, uuidA);
        engine.notePublished(externalB, uuidB);

        engine.applyRelatedMessages(List.of(
                new RelatedMessageRef("minecraft", "global", "global", externalA),
                new RelatedMessageRef("MINECRAFT", "global", "global", externalB),
                new RelatedMessageRef("minecraft", "global", "global", "rosechat-mc-unknown-external")
        ));

        assertEquals(2, actions.deleted.size());
        assertTrue(actions.deleted.contains(uuidA));
        assertTrue(actions.deleted.contains(uuidB));

        engine.applyRelatedMessages(List.of(
                new RelatedMessageRef("minecraft", "global", "global", externalB)));
        assertEquals(2, actions.deleted.size(), "retroactive deletion must be idempotent");
    }

    // ---- Required test 10: foreign Discord related refs are ignored ----

    @Test
    void discordRelatedRefsAreIgnoredForLocalDeletion() {
        UUID uuidA = UUID.randomUUID();
        String external = CentralModerationIds.externalMessageId(UUID.randomUUID());
        engine.notePublished(external, uuidA);

        engine.applyRelatedMessages(List.of(
                new RelatedMessageRef("discord", "guild-1", "123", external),
                new RelatedMessageRef("discord", "guild-1", "123", "discord-999")
        ));

        assertTrue(actions.deleted.isEmpty(), "Discord refs belong to the W14 surface, not RoseChat");
    }

    // ---- Required test 11: retry preserves external/canonical IDs ----

    @Test
    void retryOfTheSameLogicalMessageReusesIds() {
        UUID eventId = UUID.randomUUID();
        engine.submit(pending(eventId));
        engine.submit(pending(eventId));

        await(() -> transport.requests.size() == 2, "both attempts submitted");

        CentralModerationRequest first = transport.requests.get(0);
        CentralModerationRequest second = transport.requests.get(1);
        assertEquals(first.externalMessageId(), second.externalMessageId(),
                "a retry must not regenerate the idempotency ID");
        assertEquals(first.canonicalMessageId(), second.canonicalMessageId());
        assertEquals(CentralModerationIds.externalMessageId(eventId), first.externalMessageId());
    }

    // ---- Required test 12 is covered by CentralModerationIdsTest (mirrorAliasesShareOneCanonicalId) ----

    // ---- Required test 13: circuit breaker opens/reprobes without blocking chat ----

    @Test
    void circuitBreakerOpensAndReprobes() {
        transport.behavior = request -> CompletableFuture.failedFuture(
                new CentralModerationException.TimedOut("deadline", null));

        engine.submit(pending());
        engine.submit(pending());
        await(() -> transport.requests.size() == 2, "two failing requests");
        await(() -> engine.health().circuitOpen(), "circuit opens after failure callbacks complete");
        assertTrue(actions.staffAlerts.stream().anyMatch(a -> a.contains("OFFLINE")));

        int publishedBefore = actions.published.size();
        engine.submit(pending());
        await(() -> actions.published.size() == publishedBefore + 1, "circuit-open fail-open publish");
        assertEquals(2, transport.requests.size(), "no request while the circuit is open");

        try {
            Thread.sleep(500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        transport.behavior = request -> CompletableFuture.completedFuture(allowDecision("SAFE"));
        PendingMessage probe = pending();
        engine.submit(probe);
        await(() -> actions.published.contains(probe), "half-open reprobe publication");

        assertEquals(3, transport.requests.size(), "the reprobe must reach the service");
        assertFalse(engine.health().circuitOpen(), "a successful reprobe closes the circuit");
        assertTrue(actions.staffAlerts.stream().anyMatch(a -> a.contains("RECOVERED")));
    }

    // ---- Required test 14: local queue/backpressure fails open ----

    @Test
    void backpressureFailsOpenWithoutQueueingForever() {
        CentralModerationEngine pressured = new CentralModerationEngine(
                transport, actions,
                new EngineParams(Duration.ofMillis(60), Duration.ofSeconds(2), 5,
                        Duration.ofMillis(400), 1, 450, 8, 25, 32, 20L, 3),
                metrics, Clock.systemUTC(), scheduler, networkExecutor);
        CompletableFuture<CentralModerationDecision> stuck = new CompletableFuture<>();
        transport.behavior = request -> stuck;

        PendingMessage first = pending();
        PendingMessage second = pending();
        pressured.submit(first);
        await(() -> transport.requests.size() == 1, "first request in flight");
        pressured.submit(second);

        await(() -> actions.published.contains(second), "backpressure fail-open publish");

        assertEquals(1, transport.requests.size(), "saturated queue must not admit a second request");
        assertEquals(1, metrics.snapshot().rateLimited());
        assertTrue(actions.audits.stream().anyMatch(a -> "CENTRAL_RATE_LIMIT_FAIL_OPEN".equals(a.outcome())));
        pressured.retire();
    }

    // ---- Required test 15: reload/disable generation fence prevents stale enforcement ----

    @Test
    void staleCompletionAfterReloadCannotEnforce() {
        CompletableFuture<CentralModerationDecision> future = new CompletableFuture<>();
        transport.behavior = request -> future;
        PendingMessage message = pending();
        engine.submit(message);

        engine.advanceGeneration();
        future.complete(blockDecision("SEVERE_HARASSMENT", List.of()));

        await(() -> actions.published.size() == 1, "stale completion publishes fail-open");

        assertTrue(actions.blockedNotices.isEmpty(), "a stale BLOCK must never enforce after reload");
        assertTrue(actions.deleted.isEmpty());
    }

    // ---- Required test 19: main-thread/network separation ----

    @Test
    void networkIsNeverTouchedOnTheSubmittingThread() {
        PendingMessage message = pending();
        engine.submit(message);

        await(() -> actions.published.size() == 1, "publication");

        assertNotEquals(testThread, transport.callThread.get(),
                "the transport must not run on the submitting thread");
        for (Thread publishThread : actions.publishThreads) {
            assertNotEquals(testThread, publishThread, "publication must not run on the submitting thread");
        }
    }

    // ---- Required test 20: no second local decision authority in central mode ----

    @Test
    void centralEngineHoldsNoLegacyDecisionAuthority() {
        List<String> legacyTypes = new ArrayList<>();
        for (Field field : CentralModerationEngine.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            if (type == AiModerationPolicy.class || type == OpenAiModerationClient.class) {
                legacyTypes.add(field.getName());
            }
        }
        assertTrue(legacyTypes.isEmpty(),
                "central engine must not consult the legacy policy/client: " + legacyTypes);
    }

    @Test
    void centralDecisionIsTheSoleAuthorityEvenWhenLegacyPolicyWouldDelete() {
        transport.behavior = request -> CompletableFuture.completedFuture(allowDecision("SAFE"));
        PendingMessage toxic = new PendingMessage(
                UUID.randomUUID(), ChannelProfile.MINECRAFT_PUBLIC, "global", "global", "",
                List.of(), UUID.randomUUID(), "Griefer",
                "kys you absolute trash irl i know where you live");
        engine.submit(toxic);

        await(() -> actions.published.size() == 1, "central ALLOW publishes");

        assertEquals(0, metrics.snapshot().centralBlocked());
        assertTrue(actions.audits.stream().anyMatch(a -> "CENTRAL_ALLOW".equals(a.outcome())),
                "the audit trail must show the central decision as the authority");
    }
}
