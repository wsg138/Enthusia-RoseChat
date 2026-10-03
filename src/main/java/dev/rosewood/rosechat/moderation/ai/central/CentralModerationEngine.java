package dev.rosewood.rosechat.moderation.ai.central;

import dev.rosewood.rosechat.moderation.ai.AiModerationMetrics;
import dev.rosewood.rosechat.moderation.ai.central.CentralModerationException.AuthenticationFailed;
import dev.rosewood.rosechat.moderation.ai.central.CentralModerationException.Conflict;
import dev.rosewood.rosechat.moderation.ai.central.CentralModerationException.ConnectionFailed;
import dev.rosewood.rosechat.moderation.ai.central.CentralModerationException.MalformedResponse;
import dev.rosewood.rosechat.moderation.ai.central.CentralModerationException.TimedOut;
import dev.rosewood.rosechat.moderation.ai.central.CentralModerationException.Unavailable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bukkit-free lifecycle engine for central Policy-v1 moderation.
 *
 * <p>Owns the full per-message lifecycle for central mode:</p>
 * <ul>
 *   <li>short bounded hold before publication (never more than the configured budget);</li>
 *   <li>asynchronous transport calls dispatched off the submitting thread;</li>
 *   <li>fail-open on every failure mode (timeout, 503, malformed, auth, circuit-open, backpressure);</li>
 *   <li>circuit breaker with half-open reprobe;</li>
 *   <li>generation fencing so stale async completions cannot act after reload/retire;</li>
 *   <li>exact-message late deletion: only a UUID resolved through the {@link CentralModerationIds.IdRegistry}
 *       is ever deleted, never a guess;</li>
 *   <li>retroactive {@code related_messages} handling restricted to resolvable Minecraft references;</li>
 *   <li>idempotent deletion: the current and retroactive paths share one deletion ledger.</li>
 * </ul>
 *
 * <p>All Bukkit/Paper interaction is pushed behind {@link Actions}; the engine
 * itself performs no server-thread work and no blocking I/O.</p>
 */
public final class CentralModerationEngine {
    /**
     * Asynchronous transport to the central service. Implementations must not
     * perform blocking I/O on the calling thread.
     */
    public interface Transport {
        CompletableFuture<CentralModerationDecision> moderate(CentralModerationRequest request);
    }

    /**
     * Side effects implemented by the Bukkit wiring. Called on engine worker
     * threads; implementations hop to the server thread where the API requires it.
     */
    public interface Actions {
        /** Delivers the message to chat (the hold expired or the path failed open). */
        void publish(PendingMessage pending);

        /** Player-facing notice that the message was blocked before publication. */
        void notifyBlocked(UUID senderId, String notice);

        /** Player-facing notice that a published message was removed. */
        void notifyRemoved(UUID senderId, String notice);

        /** Deletes exactly the given RoseChat message UUID. Never called with a guessed ID. */
        void deleteExactMessage(UUID rosechatMessageId);

        /** Staff/health diagnostic. Never carries credentials. */
        void alertStaff(String detail);

        /** Diagnostic audit sink. */
        void audit(AuditRecord record);
    }

    public record AuditRecord(
            Instant at,
            UUID eventId,
            UUID senderId,
            String senderName,
            String channelId,
            String text,
            String outcome,
            String semanticLabel,
            Double confidence,
            List<String> reasonCodes,
            long latencyMs
    ) {
    }

    public record EngineParams(
            Duration maxChatHold,
            Duration requestTimeout,
            int failuresToOpen,
            Duration circuitOpenDuration,
            int maxInFlight,
            int globalRequestsPerMinute,
            int playerRequestsPerTenSeconds,
            int maxConflictDiagnostics,
            int maxRelatedPerResponse,
            long lateResolveRetryMs,
            int lateResolveMaxAttempts
    ) {
        public EngineParams {
            Objects.requireNonNull(maxChatHold, "maxChatHold");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            Objects.requireNonNull(circuitOpenDuration, "circuitOpenDuration");
            if (maxChatHold.isNegative() || maxChatHold.toMillis() > 300) {
                throw new IllegalArgumentException("maxChatHold must be between 0 and 300ms");
            }
            if (requestTimeout.isZero() || requestTimeout.isNegative()) {
                throw new IllegalArgumentException("requestTimeout must be positive");
            }
            if (failuresToOpen < 1 || maxInFlight < 1 || globalRequestsPerMinute < 1
                    || playerRequestsPerTenSeconds < 1 || maxConflictDiagnostics < 1
                    || maxRelatedPerResponse < 1 || lateResolveRetryMs < 1 || lateResolveMaxAttempts < 1) {
                throw new IllegalArgumentException("invalid engine bounds");
            }
        }

        public static EngineParams defaults() {
            return new EngineParams(
                    Duration.ofMillis(200),
                    Duration.ofSeconds(2),
                    3,
                    Duration.ofSeconds(60),
                    64,
                    450,
                    8,
                    25,
                    32,
                    250L,
                    8
            );
        }
    }

    public record ConflictDiagnostic(Instant at, String externalMessageId, String detail) {
    }

    public record EngineHealth(
            boolean circuitOpen,
            Instant circuitOpenUntil,
            int inFlight,
            long consecutiveFailures,
            String lastFailureCategory,
            Instant lastFailureAt,
            String lastPolicyVersion,
            String lastModelVersion,
            List<ConflictDiagnostic> conflictDiagnostics
    ) {
    }

    public static final class PendingMessage {
        public final UUID eventId;
        public final String externalMessageId;
        public final String canonicalMessageId;
        public final ChannelProfile profile;
        public final String scopeId;
        public final String channelId;
        public final String conversationId;
        public final List<UUID> recipientIds;
        public final UUID senderId;
        public final String senderName;
        public final String text;

        private final AtomicReference<MessageState> state = new AtomicReference<>(MessageState.PENDING);
        private final AtomicBoolean enforced = new AtomicBoolean();
        private final AtomicBoolean deleteRequested = new AtomicBoolean();
        private volatile ScheduledFuture<?> holdTimer;

        public PendingMessage(
                UUID eventId,
                ChannelProfile profile,
                String scopeId,
                String channelId,
                String conversationId,
                List<UUID> recipientIds,
                UUID senderId,
                String senderName,
                String text
        ) {
            this.eventId = Objects.requireNonNull(eventId, "eventId");
            this.externalMessageId = CentralModerationIds.externalMessageId(eventId);
            this.canonicalMessageId = CentralModerationIds.canonicalMessageId(eventId);
            this.profile = Objects.requireNonNull(profile, "profile");
            this.scopeId = scopeId == null ? "" : scopeId;
            this.channelId = channelId == null ? "" : channelId;
            this.conversationId = conversationId == null ? "" : conversationId;
            this.recipientIds = recipientIds == null ? List.of() : List.copyOf(recipientIds);
            this.senderId = Objects.requireNonNull(senderId, "senderId");
            this.senderName = senderName == null ? "" : senderName;
            this.text = text == null ? "" : text;
        }

        CentralModerationRequest toRequest(Instant occurredAt) {
            return new CentralModerationRequest(
                    profile,
                    scopeId,
                    channelId,
                    conversationId,
                    externalMessageId,
                    canonicalMessageId,
                    senderId,
                    recipientIds,
                    occurredAt,
                    text,
                    ""
            );
        }
    }

    private enum MessageState {
        PENDING,
        PUBLISHED,
        BLOCKED
    }

    private final Transport transport;
    private final Actions actions;
    private final EngineParams params;
    private final AiModerationMetrics metrics;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Executor networkExecutor;
    private final CentralModerationIds.IdRegistry idRegistry = new CentralModerationIds.IdRegistry();

    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean retired = new AtomicBoolean();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger inFlightRequests = new AtomicInteger();
    private final AtomicReference<Instant> circuitOpenUntil = new AtomicReference<>(Instant.EPOCH);
    private final AtomicBoolean circuitWasOpen = new AtomicBoolean();
    private final AtomicReference<String> lastFailureCategory = new AtomicReference<>("");
    private final AtomicReference<Instant> lastFailureAt = new AtomicReference<>();
    private final AtomicReference<String> lastPolicyVersion = new AtomicReference<>("");
    private final AtomicReference<String> lastModelVersion = new AtomicReference<>("");
    private final Deque<ConflictDiagnostic> conflictDiagnostics = new ArrayDeque<>();
    private final Map<String, PendingMessage> pendingByExternalId = new ConcurrentHashMap<>();
    private final Map<String, Boolean> deletedExternalIds = new ConcurrentHashMap<>();
    private final Map<String, Boolean> conflictAlertedExternalIds = new ConcurrentHashMap<>();
    private final Object requestWindowLock = new Object();
    private final Deque<Instant> globalRequestWindow = new ArrayDeque<>();
    private final Map<UUID, Deque<Instant>> playerRequestWindows = new ConcurrentHashMap<>();

    public CentralModerationEngine(
            Transport transport,
            Actions actions,
            EngineParams params,
            AiModerationMetrics metrics,
            Clock clock,
            ScheduledExecutorService scheduler,
            Executor networkExecutor
    ) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.params = Objects.requireNonNull(params, "params");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.networkExecutor = Objects.requireNonNull(networkExecutor, "networkExecutor");
    }

    /**
     * Advances the generation fence. Stale async completions from earlier
     * generations publish fail-open but never enforce.
     */
    public void advanceGeneration() {
        generation.incrementAndGet();
    }

    /**
     * Submits a message for central moderation. Never performs network I/O on
     * the calling thread: the transport call is dispatched to the network
     * executor.
     */
    public void submit(PendingMessage pending) {
        Objects.requireNonNull(pending, "pending");
        long gen = generation.get();
        if (retired.get()) {
            publishFailOpen(pending, gen);
            return;
        }
        if (circuitOpen()) {
            metrics.requestRateLimited();
            audit(pending, "CENTRAL_CIRCUIT_OPEN_FAIL_OPEN", "none", null, List.of(), 0L);
            publishFailOpen(pending, gen);
            return;
        }
        if (!reserveRequest(pending.senderId)) {
            metrics.requestRateLimited();
            audit(pending, "CENTRAL_RATE_LIMIT_FAIL_OPEN", "none", null, List.of(), 0L);
            publishFailOpen(pending, gen);
            return;
        }
        pendingByExternalId.put(pending.externalMessageId, pending);
        pending.holdTimer = scheduler.schedule(
                () -> publishIfPending(pending, gen),
                params.maxChatHold().toMillis(),
                TimeUnit.MILLISECONDS
        );
        metrics.requestStarted();
        long started = System.nanoTime();
        networkExecutor.execute(() -> {
            if (retired.get() || generation.get() != gen) {
                finishRequest();
                publishIfPending(pending, gen);
                return;
            }
            CentralModerationRequest request = pending.toRequest(clock.instant());
            CompletableFuture<CentralModerationDecision> future;
            try {
                future = transport.moderate(request);
            } catch (RuntimeException exception) {
                completeWithFailure(pending, gen, started, exception);
                return;
            }
            if (future == null) {
                completeWithFailure(pending, gen, started,
                        new CentralModerationException.RequestFailed("transport returned null future"));
                return;
            }
            future.whenComplete((decision, failure) -> {
                if (failure != null) {
                    Throwable cause = failure instanceof java.util.concurrent.CompletionException
                            && failure.getCause() != null ? failure.getCause() : failure;
                    completeWithFailure(pending, gen, started, cause);
                } else if (decision == null) {
                    completeWithFailure(pending, gen, started,
                            new CentralModerationException.MalformedResponse("transport returned null decision"));
                } else {
                    completeWithDecision(pending, gen, started, decision);
                }
            });
        });
    }

    /**
     * Records that a submitted message was published as the exact RoseChat
     * message UUID. Triggers any pending late deletion for that message.
     */
    public void notePublished(String externalMessageId, UUID rosechatMessageId) {
        Objects.requireNonNull(externalMessageId, "externalMessageId");
        Objects.requireNonNull(rosechatMessageId, "rosechatMessageId");
        idRegistry.registerPublished(externalMessageId, rosechatMessageId);
        PendingMessage pending = pendingByExternalId.get(externalMessageId);
        if (pending != null && pending.deleteRequested.get()) {
            tryDeleteExact(pending, generation.get(), 0);
        }
    }

    /**
     * Applies retroactive deletion references from a central response. Only
     * references that name the Minecraft surface and resolve through this
     * instance's ID registry are deleted. Discord/foreign references are
     * ignored. Deletion is idempotent per external ID.
     */
    public void applyRelatedMessages(List<RelatedMessageRef> refs) {
        if (refs == null || refs.isEmpty() || retired.get()) {
            return;
        }
        int bound = Math.min(refs.size(), params.maxRelatedPerResponse());
        for (int i = 0; i < bound; i++) {
            RelatedMessageRef ref = refs.get(i);
            if (ref == null || !ref.isMinecraftSurface()) {
                continue;
            }
            if (!ref.isResolvable()) {
                continue;
            }
            if (deletedExternalIds.containsKey(ref.externalMessageId())) {
                continue;
            }
            UUID messageId = idRegistry.resolveMessageId(ref.externalMessageId());
            if (messageId == null) {
                continue;
            }
            deletedExternalIds.put(ref.externalMessageId(), Boolean.TRUE);
            actions.deleteExactMessage(messageId);
        }
    }

    /**
     * Records a platform mirror alias for a canonical message, completing the
     * provider-neutral mirror contract: the Minecraft original and its Discord
     * mirror share one canonical ID, and the service stores a single event.
     */
    public void registerMirrorAlias(String canonicalMessageId, String externalMessageId) {
        idRegistry.registerAlias(canonicalMessageId, externalMessageId);
    }

    public EngineHealth health() {
        List<ConflictDiagnostic> conflicts;
        synchronized (conflictDiagnostics) {
            conflicts = new ArrayList<>(conflictDiagnostics);
        }
        Instant openUntil = circuitOpenUntil.get();
        return new EngineHealth(
                openUntil.isAfter(clock.instant()),
                openUntil,
                inFlightRequests.get(),
                consecutiveFailures.get(),
                lastFailureCategory.get(),
                lastFailureAt.get(),
                lastPolicyVersion.get(),
                lastModelVersion.get(),
                conflicts
        );
    }

    /**
     * Retires the engine: every still-pending message is published fail-open
     * and no further enforcement is possible from this instance.
     */
    public void retire() {
        if (!retired.compareAndSet(false, true)) {
            return;
        }
        long gen = generation.get();
        for (PendingMessage pending : pendingByExternalId.values()) {
            ScheduledFuture<?> timer = pending.holdTimer;
            if (timer != null) {
                timer.cancel(false);
            }
            publishFailOpen(pending, gen);
        }
        pendingByExternalId.clear();
    }

    CentralModerationIds.IdRegistry idRegistry() {
        return idRegistry;
    }

    private void completeWithDecision(PendingMessage pending, long gen, long startedNanos,
                                      CentralModerationDecision decision) {
        finishRequest();
        long latencyMs = elapsedMs(startedNanos);
        if (retired.get() || generation.get() != gen) {
            publishIfPending(pending, gen);
            return;
        }
        metrics.requestSucceeded(latencyMs);
        onRequestSuccess(decision);
        if (!decision.policyVersion().isBlank()) {
            lastPolicyVersion.set(decision.policyVersion());
        }
        if (!decision.modelVersion().isBlank()) {
            lastModelVersion.set(decision.modelVersion());
        }
        if (decision.failOpen() || decision.messageAction() == CentralModerationDecision.MessageAction.ALLOW) {
            metrics.allowed();
            if (decision.degraded()) {
                metrics.centralDegraded();
            }
            audit(pending,
                    decision.failOpen() ? "CENTRAL_DEGRADED_ALLOW" : "CENTRAL_ALLOW",
                    decision.semanticLabel(), decision.confidence(), decision.reasonCodes(), latencyMs);
            publishIfPending(pending, gen);
            applyRelatedMessages(decision.relatedMessages());
            return;
        }
        enforceBlock(pending, gen, decision, latencyMs);
        applyRelatedMessages(decision.relatedMessages());
    }

    private void completeWithFailure(PendingMessage pending, long gen, long startedNanos, Throwable failure) {
        finishRequest();
        long latencyMs = elapsedMs(startedNanos);
        if (retired.get() || generation.get() != gen) {
            publishIfPending(pending, gen);
            return;
        }
        if (failure instanceof Conflict conflict) {
            metrics.requestFailed(latencyMs);
            metrics.centralConflict();
            recordConflict(pending, conflict);
            audit(pending, "CENTRAL_CONFLICT_FAIL_OPEN", "none", null, List.of(), latencyMs);
            publishIfPending(pending, gen);
            return;
        }
        metrics.requestFailed(latencyMs);
        String category = failureCategory(failure);
        if (failure instanceof TimedOut) {
            metrics.centralTimeout();
        } else if (failure instanceof Unavailable) {
            metrics.centralUnavailable();
        }
        lastFailureCategory.set(category);
        lastFailureAt.set(clock.instant());
        audit(pending, "CENTRAL_" + category + "_FAIL_OPEN", "none", null, List.of(), latencyMs);
        onRequestFailure(category);
        publishIfPending(pending, gen);
    }

    private static String failureCategory(Throwable failure) {
        if (failure instanceof TimedOut) {
            return "TIMEOUT";
        }
        if (failure instanceof Unavailable) {
            return "SERVICE_UNAVAILABLE";
        }
        if (failure instanceof ConnectionFailed) {
            return "CONNECTION_FAILED";
        }
        if (failure instanceof AuthenticationFailed) {
            return "AUTH_FAILED";
        }
        if (failure instanceof MalformedResponse) {
            return "MALFORMED";
        }
        return "REQUEST_FAILED";
    }

    private void recordConflict(PendingMessage pending, Conflict conflict) {
        synchronized (conflictDiagnostics) {
            conflictDiagnostics.addLast(new ConflictDiagnostic(
                    clock.instant(), pending.externalMessageId, conflict.diagnosticBody()));
            while (conflictDiagnostics.size() > params.maxConflictDiagnostics()) {
                conflictDiagnostics.removeFirst();
            }
        }
        if (conflictAlertedExternalIds.putIfAbsent(pending.externalMessageId, Boolean.TRUE) == null) {
            actions.alertStaff("Central moderation reported an idempotency conflict (409) for message "
                    + pending.externalMessageId + "; failing open without regenerating IDs. Detail: "
                    + conflict.diagnosticBody());
        }
    }

    private void enforceBlock(PendingMessage pending, long gen, CentralModerationDecision decision, long latencyMs) {
        if (!pending.enforced.compareAndSet(false, true)) {
            return;
        }
        metrics.centralBlocked();
        MessageState previous = pending.state.getAndUpdate(state ->
                state == MessageState.PENDING ? MessageState.BLOCKED : state);
        boolean late = previous == MessageState.PUBLISHED;
        metrics.deleted(late);
        if (previous == MessageState.PENDING) {
            ScheduledFuture<?> timer = pending.holdTimer;
            if (timer != null) {
                timer.cancel(false);
            }
            pendingByExternalId.remove(pending.externalMessageId);
            actions.notifyBlocked(pending.senderId,
                    "Your public message was blocked by AI moderation (" + decision.semanticLabel() + ").");
            audit(pending, "CENTRAL_BLOCK_PRE_BROADCAST", decision.semanticLabel(),
                    decision.confidence(), decision.reasonCodes(), latencyMs);
            actions.alertStaff("BLOCKED " + pending.senderName + " [" + decision.semanticLabel() + "]");
        } else if (late) {
            long blockGen = generation.get();
            pending.deleteRequested.set(true);
            actions.notifyRemoved(pending.senderId,
                    "Your public message was removed by AI moderation (" + decision.semanticLabel() + ").");
            audit(pending, "CENTRAL_BLOCK_LATE", decision.semanticLabel(),
                    decision.confidence(), decision.reasonCodes(), latencyMs);
            actions.alertStaff("REMOVED " + pending.senderName + " [" + decision.semanticLabel() + "]");
            tryDeleteExact(pending, blockGen, 0);
        } else {
            audit(pending, "CENTRAL_BLOCK_RACE_NOOP", decision.semanticLabel(),
                    decision.confidence(), decision.reasonCodes(), latencyMs);
        }
    }

    private void tryDeleteExact(PendingMessage pending, long gen, int attempt) {
        if (retired.get() || generation.get() != gen) {
            return;
        }
        UUID messageId = idRegistry.resolveMessageId(pending.externalMessageId);
        if (messageId != null) {
            dispatchDeleteExact(pending, messageId);
            return;
        }
        if (attempt >= params.lateResolveMaxAttempts()) {
            actions.alertStaff("AI moderation could not uniquely identify the message UUID for late deletion of "
                    + pending.externalMessageId + "; refusing to guess and leaving chat safe.");
            audit(pending, "CENTRAL_LATE_DELETE_UNRESOLVED", "none", null, List.of(), 0L);
            pendingByExternalId.remove(pending.externalMessageId);
            return;
        }
        scheduler.schedule(() -> {
            if (!retired.get() && generation.get() == gen && pending.deleteRequested.get()) {
                tryDeleteExact(pending, gen, attempt + 1);
            }
        }, params.lateResolveRetryMs(), TimeUnit.MILLISECONDS);
    }

    private void dispatchDeleteExact(PendingMessage pending, UUID messageId) {
        if (deletedExternalIds.putIfAbsent(pending.externalMessageId, Boolean.TRUE) != null) {
            return;
        }
        pendingByExternalId.remove(pending.externalMessageId);
        actions.deleteExactMessage(messageId);
    }

    private void publishIfPending(PendingMessage pending, long gen) {
        if (retired.get() || generation.get() != gen) {
            if (pending.state.compareAndSet(MessageState.PENDING, MessageState.PUBLISHED)) {
                pendingByExternalId.remove(pending.externalMessageId);
                safePublish(pending);
            }
            return;
        }
        if (!pending.state.compareAndSet(MessageState.PENDING, MessageState.PUBLISHED)) {
            return;
        }
        pendingByExternalId.remove(pending.externalMessageId);
        safePublish(pending);
    }

    private void publishFailOpen(PendingMessage pending, long gen) {
        publishIfPending(pending, gen);
    }

    private void safePublish(PendingMessage pending) {
        try {
            actions.publish(pending);
        } catch (RuntimeException exception) {
            actions.alertStaff("AI moderation failed to publish a fail-open message: "
                    + exception.getClass().getSimpleName());
        }
    }

    private boolean reserveRequest(UUID playerId) {
        Instant now = clock.instant();
        synchronized (requestWindowLock) {
            while (!globalRequestWindow.isEmpty()
                    && globalRequestWindow.peekFirst().isBefore(now.minusSeconds(60))) {
                globalRequestWindow.removeFirst();
            }
            Deque<Instant> playerWindow = playerRequestWindows.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
            while (!playerWindow.isEmpty() && playerWindow.peekFirst().isBefore(now.minusSeconds(10))) {
                playerWindow.removeFirst();
            }
            if (globalRequestWindow.size() >= params.globalRequestsPerMinute()
                    || playerWindow.size() >= params.playerRequestsPerTenSeconds()
                    || inFlightRequests.get() >= params.maxInFlight()) {
                return false;
            }
            globalRequestWindow.addLast(now);
            playerWindow.addLast(now);
            inFlightRequests.incrementAndGet();
            return true;
        }
    }

    private void finishRequest() {
        inFlightRequests.updateAndGet(value -> Math.max(0, value - 1));
    }

    private boolean circuitOpen() {
        return circuitOpenUntil.get().isAfter(clock.instant());
    }

    private void onRequestSuccess(CentralModerationDecision decision) {
        consecutiveFailures.set(0);
        if (circuitWasOpen.compareAndSet(true, false)) {
            circuitOpenUntil.set(Instant.EPOCH);
            actions.alertStaff("RECOVERED Central moderation is responding again.");
        }
    }

    private void onRequestFailure(String category) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= params.failuresToOpen()) {
            Instant until = clock.instant().plus(params.circuitOpenDuration());
            circuitOpenUntil.set(until);
            if (circuitWasOpen.compareAndSet(false, true)) {
                actions.alertStaff("OFFLINE Central moderation fail-open active. Reason: " + category + '.');
            }
        }
    }

    private void audit(PendingMessage pending, String outcome, String semanticLabel,
                       Double confidence, List<String> reasonCodes, long latencyMs) {
        try {
            actions.audit(new AuditRecord(
                    clock.instant(),
                    pending.eventId,
                    pending.senderId,
                    pending.senderName,
                    pending.channelId,
                    pending.text,
                    outcome,
                    semanticLabel,
                    confidence,
                    reasonCodes,
                    latencyMs
            ));
        } catch (RuntimeException ignored) {
            // Auditing must never break the chat path.
        }
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
