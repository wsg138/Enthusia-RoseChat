package dev.rosewood.rosechat.moderation.ai;

import com.google.gson.Gson;
import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.api.RoseChatAPI;
import dev.rosewood.rosechat.api.staff.AutomatedModerationEvidence;
import dev.rosewood.rosechat.api.staff.AutomatedModerationResult;
import dev.rosewood.rosechat.api.staff.AutomatedPublicMuteRequest;
import dev.rosewood.rosechat.api.staff.ChannelClassification;
import dev.rosewood.rosechat.api.staff.RoseChatAutomatedModerationService;
import dev.rosewood.rosechat.chat.channel.Channel;
import dev.rosewood.rosechat.chat.channel.ChannelMessageOptions;
import dev.rosewood.rosechat.config.Settings;
import dev.rosewood.rosechat.message.DeletableMessage;
import dev.rosewood.rosechat.message.RosePlayer;
import java.io.File;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class AiModerationManager implements AutoCloseable, Listener {
    private static final int SAFE_GLOBAL_REQUESTS_PER_MINUTE = 450;
    private static final int SAFE_PLAYER_REQUESTS_PER_TEN_SECONDS = 8;
    private static final int MAX_IN_FLIGHT_REQUESTS = 64;

    private final RoseChat plugin;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Health> health = new AtomicReference<>(Health.disabled());
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger inFlightRequests = new AtomicInteger();
    private final Map<UUID, Instant> muteRequestedUntil = new ConcurrentHashMap<>();
    private final Object requestWindowLock = new Object();
    private final Deque<Instant> globalRequestWindow = new ArrayDeque<>();
    private final Map<UUID, Deque<Instant>> playerRequestWindows = new HashMap<>();
    private final AiModerationMetrics metrics = new AiModerationMetrics();
    private final AiModerationRuntimeFence runtimeFence = new AiModerationRuntimeFence();
    private volatile Instant circuitOpenUntil = Instant.EPOCH;
    private volatile AiModerationConfig config;
    private volatile AiModerationContextBuffer context;
    private volatile AiModerationPolicy policy;
    private volatile AiModerationStrikeStore strikeStore;
    private volatile AiModerationAuditStore auditStore;
    private volatile OpenAiModerationClient client;

    public AiModerationManager(RoseChat plugin) {
        this(plugin, Clock.systemUTC());
    }

    AiModerationManager(RoseChat plugin, Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.clock = Objects.requireNonNull(clock, "clock");
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "RoseChat-AI-Moderation");
            thread.setDaemon(true);
            return thread;
        };
        this.scheduler = Executors.newScheduledThreadPool(2, factory);
        reload();
    }

    public synchronized void reload() {
        runtimeFence.advance();
        AiModerationConfig loaded = AiModerationConfig.load(plugin);
        this.config = loaded;
        this.context = new AiModerationContextBuffer(clock, loaded);
        this.policy = new AiModerationPolicy(loaded);
        this.strikeStore = new AiModerationStrikeStore(
                plugin.getDataFolder().toPath().resolve("ai-moderation-strikes.tsv"),
                clock,
                loaded.strikeWindow(),
                plugin.getLogger()
        );
        this.auditStore = new AiModerationAuditStore(
                plugin.getDataFolder().toPath().resolve("ai-moderation-audit"),
                clock,
                plugin.getLogger()
        );
        this.consecutiveFailures.set(0);
        this.circuitOpenUntil = Instant.EPOCH;
        synchronized (requestWindowLock) {
            globalRequestWindow.clear();
            playerRequestWindows.clear();
        }
        if (!loaded.enabled()) {
            this.client = null;
            this.health.set(Health.disabled());
            return;
        }
        String key = resolveApiKey(loaded);
        if (key.isBlank()) {
            this.client = null;
            this.health.set(new Health(Status.DOWN,
                    "api-key is blank and environment variable " + loaded.apiKeyEnvironmentVariable() + " is missing"));
            plugin.getLogger().warning("AI moderation is enabled but no OpenAI API key is configured in ai-moderation.yml or its fallback environment variable; chat will fail open.");
            return;
        }
        this.client = new OpenAiModerationClient(
                HttpClient.newBuilder().connectTimeout(loaded.requestTimeout()).build(),
                new Gson(),
                loaded,
                key
        );
        this.health.set(new Health(Status.HEALTHY, "configured"));
    }

    public void moderateAndSend(Channel channel, ChannelMessageOptions options) {
        RuntimeSnapshot runtime = runtimeSnapshot();
        AiModerationConfig current = runtime.config();
        if (!eligible(channel, options, current)) {
            channel.send(options);
            return;
        }
        OpenAiModerationClient activeClient = runtime.client();
        if (activeClient == null || circuitOpen()) {
            channel.send(options);
            return;
        }

        UUID eventId = UUID.randomUUID();
        UUID senderId = options.sender().getUUID();
        String senderName = safeName(options.sender());
        if (!reserveRequest(senderId)) {
            metrics.requestRateLimited();
            audit(eventId, senderId, senderName, channel.getId(), options.message(),
                    "LOCAL_RATE_LIMIT_FAIL_OPEN", "none", 0.0D, 0, 0L);
            channel.send(options);
            return;
        }

        AiModerationContextBuffer.Snapshot snapshot = runtime.context().record(
                channel.getId(), eventId, senderId, senderName, options.message()
        );
        PendingMessage pending = new PendingMessage(eventId, channel, options, senderId, senderName);
        this.scheduler.schedule(
                () -> publishIfPending(pending),
                current.maximumChatHold().toMillis(),
                TimeUnit.MILLISECONDS
        );

        metrics.requestStarted();
        long started = System.nanoTime();
        try {
            activeClient.moderate(snapshot.targetMessage(), snapshot.transcript())
                    .whenComplete((batch, failure) -> completeInitialRequest(
                            runtime, pending, snapshot, eventId, senderId, senderName, started, batch, failure
                    ));
        } catch (RuntimeException exception) {
            finishRequest();
            long latencyMs = elapsedMs(started);
            if (runtimeFence.isCurrent(runtime.generation())) {
                metrics.requestFailed(latencyMs);
                audit(eventId, senderId, senderName, channel.getId(), options.message(),
                        "API_FAILURE_FAIL_OPEN", rootType(exception), 0.0D, 0, latencyMs);
                onRequestFailure(exception, current);
            }
            publishIfPending(pending);
        }
    }

    private void completeInitialRequest(
            RuntimeSnapshot runtime,
            PendingMessage pending,
            AiModerationContextBuffer.Snapshot snapshot,
            UUID eventId,
            UUID senderId,
            String senderName,
            long started,
            OpenAiModerationClient.BatchResult batch,
            Throwable failure
    ) {
        finishRequest();
        long latencyMs = elapsedMs(started);
        if (!runtimeFence.isCurrent(runtime.generation())) {
            publishIfPending(pending);
            return;
        }
        if (failure != null) {
            metrics.requestFailed(latencyMs);
            audit(eventId, senderId, senderName, pending.channel.getId(), pending.options.message(),
                    "API_FAILURE_FAIL_OPEN", rootType(failure), 0.0D, 0, latencyMs);
            onRequestFailure(failure, runtime.config());
            publishIfPending(pending);
            return;
        }
        metrics.requestSucceeded(latencyMs);
        onRequestSuccess();
        applyVerdict(pending, snapshot, runtime.policy().evaluate(batch), false, latencyMs, runtime);
    }

    private boolean eligible(Channel channel, ChannelMessageOptions options, AiModerationConfig current) {
        if (current == null || !current.enabled() || options.sender() == null
                || !options.sender().isPlayer() || options.sender().getUUID() == null) {
            return false;
        }
        return plugin.getStaffService() == null
                || plugin.getStaffService().classifyChannel(channel.getId()) == ChannelClassification.PUBLIC;
    }

    private synchronized RuntimeSnapshot runtimeSnapshot() {
        return new RuntimeSnapshot(
                runtimeFence.current(), config, context, policy, strikeStore, client
        );
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
            if (globalRequestWindow.size() >= SAFE_GLOBAL_REQUESTS_PER_MINUTE
                    || playerWindow.size() >= SAFE_PLAYER_REQUESTS_PER_TEN_SECONDS
                    || inFlightRequests.get() >= MAX_IN_FLIGHT_REQUESTS) {
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
        return this.circuitOpenUntil.isAfter(clock.instant());
    }

    private void applyVerdict(
            PendingMessage pending,
            AiModerationContextBuffer.Snapshot snapshot,
            AiModerationPolicy.Verdict verdict,
            boolean followUp,
            long latencyMs,
            RuntimeSnapshot runtime
    ) {
        if (!runtimeFence.isCurrent(runtime.generation())) {
            publishIfPending(pending);
            return;
        }
        AiModerationConfig current = runtime.config();
        if (current.shadowMode()) {
            publishIfPending(pending);
            if (verdict.action() != AiModerationPolicy.Action.ALLOW) {
                metrics.shadowFlagged();
                alertStaff("[AI shadow] " + pending.senderName + " would be " + verdict.action()
                        + " for " + verdict.category() + " (severity " + verdict.severity() + ").");
            }
            auditVerdict(pending, verdict, (followUp ? "FOLLOWUP_SHADOW_" : "SHADOW_") + verdict.action(), latencyMs);
        } else if (verdict.action() == AiModerationPolicy.Action.DELETE) {
            enforceDelete(pending, verdict, latencyMs, followUp, current, runtime.strikeStore());
        } else {
            publishIfPending(pending);
            if (verdict.action() == AiModerationPolicy.Action.ALERT_ONLY) {
                metrics.alerted();
                alertStaff("AI moderation flagged " + pending.senderName + " for staff review: "
                        + verdict.category() + " (severity " + verdict.severity() + ").");
                notifyPlayer(pending.senderId, "Your message was flagged for staff review by chat moderation.");
            } else {
                metrics.allowed();
            }
            auditVerdict(pending, verdict, (followUp ? "FOLLOWUP_" : "") + verdict.action(), latencyMs);
        }

        if (!followUp && verdict.followUpUseful()) {
            this.scheduler.schedule(
                    () -> followUp(pending, snapshot, runtime),
                    current.followUpDelay().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void followUp(
            PendingMessage pending,
            AiModerationContextBuffer.Snapshot original,
            RuntimeSnapshot runtime
    ) {
        if (!runtimeFence.isCurrent(runtime.generation()) || pending.enforced.get() || circuitOpen()) {
            return;
        }
        OpenAiModerationClient activeClient = runtime.client();
        if (activeClient == null) {
            return;
        }
        AiModerationContextBuffer.Snapshot later = runtime.context()
                .snapshot(original.channelId(), original.eventId())
                .orElse(null);
        if (later == null || !later.hasAfterContext()) {
            return;
        }
        if (!reserveRequest(pending.senderId)) {
            metrics.requestRateLimited();
            audit(pending.eventId, pending.senderId, pending.senderName, pending.channel.getId(), pending.options.message(),
                    "FOLLOWUP_RATE_LIMITED", "none", 0.0D, 0, 0L);
            return;
        }
        metrics.requestStarted();
        long started = System.nanoTime();
        try {
            activeClient.moderate(later.targetMessage(), later.transcript())
                    .whenComplete((batch, failure) -> completeFollowUp(runtime, pending, later, started, batch, failure));
        } catch (RuntimeException exception) {
            finishRequest();
            if (runtimeFence.isCurrent(runtime.generation())) {
                metrics.requestFailed(elapsedMs(started));
                onRequestFailure(exception, runtime.config());
            }
        }
    }

    private void completeFollowUp(
            RuntimeSnapshot runtime,
            PendingMessage pending,
            AiModerationContextBuffer.Snapshot later,
            long started,
            OpenAiModerationClient.BatchResult batch,
            Throwable failure
    ) {
        finishRequest();
        long latencyMs = elapsedMs(started);
        if (!runtimeFence.isCurrent(runtime.generation())) {
            return;
        }
        if (failure != null) {
            metrics.requestFailed(latencyMs);
            audit(pending.eventId, pending.senderId, pending.senderName,
                    pending.channel.getId(), pending.options.message(),
                    "FOLLOWUP_API_FAILURE", rootType(failure), 0.0D, 0, latencyMs);
            onRequestFailure(failure, runtime.config());
            return;
        }
        metrics.requestSucceeded(latencyMs);
        onRequestSuccess();
        applyVerdict(pending, later, runtime.policy().evaluate(batch), true, latencyMs, runtime);
    }

    private void publishIfPending(PendingMessage pending) {
        if (!pending.state.compareAndSet(MessageState.PENDING, MessageState.PUBLISHED)) {
            return;
        }
        pending.beforeMessageIds.addAll(messageIds(pending.options.sender()));
        pending.channel.send(pending.options);
        resolvePublishedMessageId(pending, 0);
    }

    private void enforceDelete(
            PendingMessage pending,
            AiModerationPolicy.Verdict verdict,
            long latencyMs,
            boolean followUp,
            AiModerationConfig current,
            AiModerationStrikeStore activeStrikeStore
    ) {
        if (!pending.enforced.compareAndSet(false, true)) {
            return;
        }
        MessageState previous = pending.state.getAndUpdate(state ->
                state == MessageState.PENDING ? MessageState.BLOCKED : state);
        boolean late = previous == MessageState.PUBLISHED;
        metrics.deleted(late);
        if (previous == MessageState.PENDING) {
            notifyPlayer(pending.senderId, enforcementNotice("blocked", verdict));
        } else if (late) {
            pending.deleteRequested.set(true);
            UUID messageId = pending.publishedMessageId.get();
            if (messageId != null) {
                deletePublishedOnce(pending, messageId);
            }
            notifyPlayer(pending.senderId, enforcementNotice("removed", verdict));
        }
        auditVerdict(pending, verdict,
                (followUp ? "FOLLOWUP_" : "") + (late ? "DELETE_LATE" : "DELETE_PRE_BROADCAST"), latencyMs);
        alertStaff("AI moderation " + (late ? "removed" : "blocked") + " a public message from "
                + pending.senderName + ": " + verdict.category() + " (severity " + verdict.severity() + ").");
        if (current.punishmentsEnabled()) {
            recordStrike(pending, verdict, current, activeStrikeStore);
        }
    }

    private String enforcementNotice(String action, AiModerationPolicy.Verdict verdict) {
        return "Your public message was " + action + " by AI moderation ("
                + verdict.category() + ", severity " + verdict.severity() + "/100).";
    }

    private void recordStrike(
            PendingMessage pending,
            AiModerationPolicy.Verdict verdict,
            AiModerationConfig current,
            AiModerationStrikeStore activeStrikeStore
    ) {
        Instant now = clock.instant();
        AiModerationStrikeStore.RecordResult result = activeStrikeStore.record(
                pending.senderId,
                pending.eventId,
                pending.options.message(),
                verdict.category(),
                verdict.confidence(),
                verdict.severity()
        );
        int count = result.count();
        if (count < current.requiredStrikes()) {
            notifyPlayer(pending.senderId, "AI moderation strike " + count + "/" + current.requiredStrikes()
                    + ". Another enforcement within " + current.strikeWindow().toMinutes()
                    + " minutes can result in a " + current.muteDuration().toDays() + "-day public mute.");
            return;
        }
        Instant existing = muteRequestedUntil.get(pending.senderId);
        if (existing != null && existing.isAfter(now)) {
            return;
        }
        requestStaffMute(pending, verdict, count, result.evidence(), now, current);
    }

    private void requestStaffMute(
            PendingMessage pending,
            AiModerationPolicy.Verdict verdict,
            int count,
            List<AiModerationStrikeStore.StrikeEvidence> strikeEvidence,
            Instant now,
            AiModerationConfig current
    ) {
        RoseChatAutomatedModerationService service = Bukkit.getServicesManager()
                .load(RoseChatAutomatedModerationService.class);
        if (service == null || service.apiVersion() != RoseChatAutomatedModerationService.API_VERSION) {
            alertStaff("AI moderation reached the public-mute threshold for " + pending.senderName
                    + ", but the EnthusiaStaff automated moderation service is unavailable or uses an older API contract.");
            return;
        }
        List<AutomatedModerationEvidence> evidence = strikeEvidence.stream()
                .map(item -> new AutomatedModerationEvidence(
                        item.eventId(),
                        item.at(),
                        item.message(),
                        item.category(),
                        item.confidence(),
                        item.severity()
                ))
                .toList();
        AutomatedPublicMuteRequest request = new AutomatedPublicMuteRequest(
                pending.senderId,
                pending.senderName,
                pending.eventId,
                verdict.category(),
                verdict.severity(),
                count,
                current.muteDuration(),
                "rosechat-ai:" + pending.eventId,
                evidence
        );
        AutomatedModerationResult moderationResult;
        try {
            moderationResult = service.applyPublicMute(request);
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning("EnthusiaStaff automated public mute failed: " + exception.getClass().getSimpleName());
            alertStaff("AI moderation could not apply the " + current.muteDuration().toDays()
                    + "-day public mute for " + pending.senderName + "; EnthusiaStaff integration failed.");
            return;
        }
        if (moderationResult != null && moderationResult.status() == AutomatedModerationResult.Status.APPLIED) {
            muteRequestedUntil.put(pending.senderId, now.plus(current.muteDuration()));
            notifyPlayer(pending.senderId, "You have been publicly muted for " + current.muteDuration().toDays()
                    + " days after " + count + " AI moderation enforcement strikes within "
                    + current.strikeWindow().toMinutes() + " minutes.");
            alertStaff("EnthusiaStaff applied the AI moderation public mute for " + pending.senderName + ".");
        } else {
            String detail = moderationResult == null ? "no result" : moderationResult.status() + ": " + moderationResult.detail();
            alertStaff("AI moderation reached the public-mute threshold for " + pending.senderName
                    + ", but EnthusiaStaff did not apply it (" + detail + ").");
        }
    }

    private void resolvePublishedMessageId(PendingMessage pending, int attempt) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            UUID resolved = uniqueNewMessageId(pending);
            if (resolved != null) {
                pending.publishedMessageId.compareAndSet(null, resolved);
                if (pending.deleteRequested.get()) {
                    deletePublishedOnce(pending, resolved);
                }
                return;
            }
            if (attempt < 8) {
                scheduler.schedule(() -> resolvePublishedMessageId(pending, attempt + 1), 50, TimeUnit.MILLISECONDS);
            } else if (pending.deleteRequested.get()) {
                alertStaff("AI moderation flagged a message after broadcast, but RoseChat could not uniquely identify its message UUID for late deletion.");
            }
        });
    }

    private UUID uniqueNewMessageId(PendingMessage pending) {
        if (pending.options.sender().getPlayerData() == null) {
            return null;
        }
        List<DeletableMessage> messages = pending.options.sender().getPlayerData().getMessageLog().getDeletableMessages();
        UUID candidate = null;
        synchronized (messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                DeletableMessage message = messages.get(i);
                if (pending.beforeMessageIds.contains(message.getUUID())) {
                    continue;
                }
                if (!Objects.equals(message.getSender(), pending.senderId)
                        || !Objects.equals(message.getChannel(), pending.channel.getId())) {
                    continue;
                }
                if (candidate != null && !candidate.equals(message.getUUID())) {
                    return null;
                }
                candidate = message.getUUID();
            }
        }
        return candidate;
    }

    private static Set<UUID> messageIds(RosePlayer player) {
        Set<UUID> ids = new HashSet<>();
        if (player.getPlayerData() == null) {
            return ids;
        }
        List<DeletableMessage> messages = player.getPlayerData().getMessageLog().getDeletableMessages();
        synchronized (messages) {
            for (DeletableMessage message : messages) {
                ids.add(message.getUUID());
            }
        }
        return ids;
    }

    private void deletePublishedOnce(PendingMessage pending, UUID messageId) {
        if (!pending.deletionDispatched.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> deletePublishedOnServerThread(pending, messageId));
    }

    private void deletePublishedOnServerThread(PendingMessage pending, UUID messageId) {
        boolean discordIdMissingBeforeDelete = findDiscordId(messageId) == null;
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            RosePlayer rosePlayer = new RosePlayer(player);
            if (rosePlayer.getPlayerData() != null) {
                RoseChatAPI.getInstance().deleteMessage(rosePlayer, messageId);
            }
        }
        propagateNetworkDeletion(pending, messageId);
        if (discordIdMissingBeforeDelete && Settings.DELETE_DISCORD_MESSAGES.get()) {
            retryDiscordDeletion(messageId, 0);
        }
    }

    private void propagateNetworkDeletion(PendingMessage pending, UUID messageId) {
        RoseChatAPI api = RoseChatAPI.getInstance();
        if (!api.isBungee()) {
            return;
        }
        for (String server : pending.channel.getServers()) {
            api.getBungeeManager().sendMessageDeletion(server, messageId);
        }
    }

    private void retryDiscordDeletion(UUID messageId, int attempt) {
        if (attempt > 10 || RoseChatAPI.getInstance().getDiscord() == null) {
            return;
        }
        this.scheduler.schedule(() -> Bukkit.getScheduler().runTask(plugin, () -> {
            String discordId = findDiscordId(messageId);
            if (discordId != null) {
                RoseChatAPI.getInstance().getDiscord().deleteMessage(discordId);
            } else {
                retryDiscordDeletion(messageId, attempt + 1);
            }
        }), 100, TimeUnit.MILLISECONDS);
    }

    private String findDiscordId(UUID messageId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            RosePlayer rosePlayer = new RosePlayer(player);
            if (rosePlayer.getPlayerData() == null) {
                continue;
            }
            List<DeletableMessage> messages = rosePlayer.getPlayerData().getMessageLog().getDeletableMessages();
            synchronized (messages) {
                for (DeletableMessage message : messages) {
                    if (messageId.equals(message.getUUID()) && message.getDiscordId() != null) {
                        return message.getDiscordId();
                    }
                }
            }
        }
        return null;
    }

    private void onRequestSuccess() {
        consecutiveFailures.set(0);
        Health prior = health.getAndSet(new Health(Status.HEALTHY, "OpenAI moderation responding"));
        if (prior.status() == Status.DOWN) {
            alertStaff("AI chat moderation recovered; normal moderation requests have resumed.");
        }
    }

    private void onRequestFailure(Throwable failure, AiModerationConfig current) {
        int failures = consecutiveFailures.incrementAndGet();
        String reason = rootType(failure);
        if (failures >= current.failuresToOpen()) {
            circuitOpenUntil = clock.instant().plus(current.circuitOpenDuration());
            Health prior = health.getAndSet(new Health(Status.DOWN,
                    reason + "; circuit open until " + circuitOpenUntil));
            if (prior.status() != Status.DOWN) {
                alertStaff("AI chat moderation is DOWN and chat is fail-open. Reason: " + reason + '.');
            }
        } else {
            health.set(new Health(Status.DEGRADED, reason));
        }
    }

    private void auditVerdict(PendingMessage pending, AiModerationPolicy.Verdict verdict, String outcome, long latencyMs) {
        audit(pending.eventId, pending.senderId, pending.senderName, pending.channel.getId(), pending.options.message(),
                outcome, verdict.category(), verdict.confidence(), verdict.severity(), latencyMs);
    }

    private void audit(
            UUID eventId,
            UUID playerId,
            String playerName,
            String channel,
            String message,
            String outcome,
            String category,
            double confidence,
            int severity,
            long latencyMs
    ) {
        AiModerationAuditStore store = this.auditStore;
        if (store == null) {
            return;
        }
        store.record(new AiModerationAuditStore.Entry(
                clock.instant(), eventId, playerId, playerName, channel, message,
                outcome, category, confidence, severity, latencyMs
        ));
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String rootType(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    private void notifyPlayer(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        });
    }

    private void alertStaff(String message) {
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.hasPermission(config.staffStatusPermission()))
                .forEach(player -> player.sendMessage(message)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        AiModerationConfig current = this.config;
        Health currentHealth = this.health.get();
        if (!current.enabled() || currentHealth.status() == Status.HEALTHY
                || !event.getPlayer().hasPermission(current.staffStatusPermission())) {
            return;
        }
        event.getPlayer().sendMessage("AI CHAT MODERATION " + currentHealth.status()
                + " - chat is fail-open. " + currentHealth.detail());
    }

    public Health health() {
        return health.get();
    }

    public AiModerationMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private String resolveApiKey(AiModerationConfig loaded) {
        File file = new File(plugin.getDataFolder(), "ai-moderation.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String configured = yaml.getString("api-key", "");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }

        String environment = System.getenv(loaded.apiKeyEnvironmentVariable());
        return environment == null ? "" : environment.trim();
    }

    private static String safeName(RosePlayer player) {
        String real = player.getRealName();
        return real == null || real.isBlank() ? player.getName() : real;
    }

    public enum Status {
        DISABLED,
        HEALTHY,
        DEGRADED,
        DOWN
    }

    public record Health(Status status, String detail) {
        public Health {
            Objects.requireNonNull(status, "status");
            detail = detail == null ? "" : detail;
        }

        private static Health disabled() {
            return new Health(Status.DISABLED, "disabled");
        }
    }

    private record RuntimeSnapshot(
            long generation,
            AiModerationConfig config,
            AiModerationContextBuffer context,
            AiModerationPolicy policy,
            AiModerationStrikeStore strikeStore,
            OpenAiModerationClient client
    ) {
        private RuntimeSnapshot {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(strikeStore, "strikeStore");
        }
    }

    private enum MessageState {
        PENDING,
        PUBLISHED,
        BLOCKED
    }

    private static final class PendingMessage {
        private final UUID eventId;
        private final Channel channel;
        private final ChannelMessageOptions options;
        private final UUID senderId;
        private final String senderName;
        private final AtomicReference<MessageState> state = new AtomicReference<>(MessageState.PENDING);
        private final AtomicBoolean enforced = new AtomicBoolean();
        private final AtomicBoolean deleteRequested = new AtomicBoolean();
        private final AtomicBoolean deletionDispatched = new AtomicBoolean();
        private final AtomicReference<UUID> publishedMessageId = new AtomicReference<>();
        private final Set<UUID> beforeMessageIds = ConcurrentHashMap.newKeySet();

        private PendingMessage(
                UUID eventId,
                Channel channel,
                ChannelMessageOptions options,
                UUID senderId,
                String senderName
        ) {
            this.eventId = eventId;
            this.channel = channel;
            this.options = options;
            this.senderId = senderId;
            this.senderName = senderName;
        }
    }
}
