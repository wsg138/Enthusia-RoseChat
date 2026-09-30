package dev.rosewood.rosechat.moderation.ai;

import com.google.gson.Gson;
import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.api.RoseChatAPI;
import dev.rosewood.rosechat.api.staff.AutomatedModerationResult;
import dev.rosewood.rosechat.api.staff.AutomatedPublicMuteRequest;
import dev.rosewood.rosechat.api.staff.ChannelClassification;
import dev.rosewood.rosechat.api.staff.RoseChatAutomatedModerationService;
import dev.rosewood.rosechat.chat.channel.Channel;
import dev.rosewood.rosechat.chat.channel.ChannelMessageOptions;
import dev.rosewood.rosechat.message.DeletableMessage;
import dev.rosewood.rosechat.message.RosePlayer;
import java.io.File;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
    private final RoseChat plugin;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Health> health = new AtomicReference<>(Health.disabled());
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final Map<UUID, Instant> muteRequestedUntil = new ConcurrentHashMap<>();
    private volatile Instant circuitOpenUntil = Instant.EPOCH;
    private volatile AiModerationConfig config;
    private volatile AiModerationContextBuffer context;
    private volatile AiModerationPolicy policy;
    private volatile AiModerationStrikeStore strikeStore;
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
        this.consecutiveFailures.set(0);
        this.circuitOpenUntil = Instant.EPOCH;
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
        AiModerationConfig current = this.config;
        if (!eligible(channel, options, current)) {
            channel.send(options);
            return;
        }
        OpenAiModerationClient activeClient = this.client;
        if (activeClient == null || circuitOpen()) {
            channel.send(options);
            return;
        }

        UUID eventId = UUID.randomUUID();
        UUID senderId = options.sender().getUUID();
        String senderName = safeName(options.sender());
        AiModerationContextBuffer.Snapshot snapshot = this.context.record(
                channel.getId(), eventId, senderId, senderName, options.message()
        );
        PendingMessage pending = new PendingMessage(eventId, channel, options, senderId, senderName);
        this.scheduler.schedule(
                () -> publishIfPending(pending),
                current.maximumChatHold().toMillis(),
                TimeUnit.MILLISECONDS
        );

        activeClient.moderate(snapshot.targetMessage(), snapshot.transcript())
                .whenComplete((batch, failure) -> {
                    if (failure != null) {
                        onRequestFailure(failure);
                        publishIfPending(pending);
                        return;
                    }
                    onRequestSuccess();
                    applyVerdict(pending, snapshot, this.policy.evaluate(batch), false);
                });
    }

    private boolean eligible(Channel channel, ChannelMessageOptions options, AiModerationConfig current) {
        if (current == null || !current.enabled() || options.sender() == null
                || !options.sender().isPlayer() || options.sender().getUUID() == null) {
            return false;
        }
        return plugin.getStaffService() == null
                || plugin.getStaffService().classifyChannel(channel.getId()) == ChannelClassification.PUBLIC;
    }

    private boolean circuitOpen() {
        return this.circuitOpenUntil.isAfter(clock.instant());
    }

    private void applyVerdict(
            PendingMessage pending,
            AiModerationContextBuffer.Snapshot snapshot,
            AiModerationPolicy.Verdict verdict,
            boolean followUp
    ) {
        AiModerationConfig current = this.config;
        if (current.shadowMode()) {
            publishIfPending(pending);
            if (verdict.action() != AiModerationPolicy.Action.ALLOW) {
                alertStaff("[AI shadow] " + pending.senderName + " would be " + verdict.action()
                        + " for " + verdict.category() + " (severity " + verdict.severity() + ").");
            }
        } else if (verdict.action() == AiModerationPolicy.Action.DELETE) {
            enforceDelete(pending, verdict);
        } else {
            publishIfPending(pending);
            if (verdict.action() == AiModerationPolicy.Action.ALERT_ONLY) {
                alertStaff("AI moderation flagged " + pending.senderName + " for staff review: "
                        + verdict.category() + " (severity " + verdict.severity() + ").");
                notifyPlayer(pending.senderId, "Your message was flagged for staff review by chat moderation.");
            }
        }

        if (!followUp && verdict.followUpUseful()) {
            this.scheduler.schedule(
                    () -> followUp(pending, snapshot),
                    current.followUpDelay().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void followUp(PendingMessage pending, AiModerationContextBuffer.Snapshot original) {
        if (pending.enforced.get() || this.client == null || circuitOpen()) {
            return;
        }
        AiModerationContextBuffer.Snapshot later = this.context
                .snapshot(original.channelId(), original.eventId())
                .orElse(null);
        if (later == null || !later.hasAfterContext()) {
            return;
        }
        this.client.moderate(later.targetMessage(), later.transcript())
                .whenComplete((batch, failure) -> {
                    if (failure != null) {
                        onRequestFailure(failure);
                        return;
                    }
                    onRequestSuccess();
                    applyVerdict(pending, later, this.policy.evaluate(batch), true);
                });
    }

    private void publishIfPending(PendingMessage pending) {
        if (!pending.state.compareAndSet(MessageState.PENDING, MessageState.PUBLISHED)) {
            return;
        }
        pending.beforeMessageIds.addAll(messageIds(pending.options.sender()));
        pending.channel.send(pending.options);
        resolvePublishedMessageId(pending, 0);
    }

    private void enforceDelete(PendingMessage pending, AiModerationPolicy.Verdict verdict) {
        if (!pending.enforced.compareAndSet(false, true)) {
            return;
        }
        MessageState previous = pending.state.getAndUpdate(state ->
                state == MessageState.PENDING ? MessageState.BLOCKED : state);
        boolean late = previous == MessageState.PUBLISHED;
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
        alertStaff("AI moderation " + (late ? "removed" : "blocked") + " a public message from "
                + pending.senderName + ": " + verdict.category() + " (severity " + verdict.severity() + ").");
        recordStrike(pending, verdict);
    }

    private String enforcementNotice(String action, AiModerationPolicy.Verdict verdict) {
        return "Your public message was " + action + " by AI moderation ("
                + verdict.category() + ", severity " + verdict.severity() + "/100).";
    }

    private void recordStrike(PendingMessage pending, AiModerationPolicy.Verdict verdict) {
        Instant now = clock.instant();
        int count = this.strikeStore.record(pending.senderId);
        if (count < config.requiredStrikes()) {
            notifyPlayer(pending.senderId, "AI moderation strike " + count + "/" + config.requiredStrikes()
                    + ". Another enforcement within " + config.strikeWindow().toMinutes()
                    + " minutes can result in a " + config.muteDuration().toDays() + "-day public mute.");
            return;
        }
        Instant existing = muteRequestedUntil.get(pending.senderId);
        if (existing != null && existing.isAfter(now)) {
            return;
        }
        requestStaffMute(pending, verdict, count, now);
    }

    private void requestStaffMute(
            PendingMessage pending,
            AiModerationPolicy.Verdict verdict,
            int count,
            Instant now
    ) {
        RoseChatAutomatedModerationService service = Bukkit.getServicesManager()
                .load(RoseChatAutomatedModerationService.class);
        if (service == null || service.apiVersion() != RoseChatAutomatedModerationService.API_VERSION) {
            alertStaff("AI moderation reached the public-mute threshold for " + pending.senderName
                    + ", but the EnthusiaStaff automated moderation service is unavailable.");
            return;
        }
        AutomatedPublicMuteRequest request = new AutomatedPublicMuteRequest(
                pending.senderId,
                pending.senderName,
                pending.eventId,
                verdict.category(),
                verdict.severity(),
                count,
                config.muteDuration(),
                "rosechat-ai:" + pending.eventId
        );
        AutomatedModerationResult result;
        try {
            result = service.applyPublicMute(request);
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning("EnthusiaStaff automated public mute failed: " + exception.getClass().getSimpleName());
            alertStaff("AI moderation could not apply the " + config.muteDuration().toDays()
                    + "-day public mute for " + pending.senderName + "; EnthusiaStaff integration failed.");
            return;
        }
        if (result != null && result.status() == AutomatedModerationResult.Status.APPLIED) {
            muteRequestedUntil.put(pending.senderId, now.plus(config.muteDuration()));
            notifyPlayer(pending.senderId, "You have been publicly muted for " + config.muteDuration().toDays()
                    + " days after " + count + " AI moderation enforcement strikes within "
                    + config.strikeWindow().toMinutes() + " minutes.");
            alertStaff("EnthusiaStaff applied the AI moderation public mute for " + pending.senderName + ".");
        } else {
            String detail = result == null ? "no result" : result.status() + ": " + result.detail();
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
        java.util.List<DeletableMessage> messages = pending.options.sender().getPlayerData().getMessageLog().getDeletableMessages();
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
        java.util.List<DeletableMessage> messages = player.getPlayerData().getMessageLog().getDeletableMessages();
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
        deletePublished(messageId);
    }

    private void deletePublished(UUID messageId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                RosePlayer rosePlayer = new RosePlayer(player);
                if (rosePlayer.getPlayerData() != null) {
                    RoseChatAPI.getInstance().deleteMessage(rosePlayer, messageId);
                }
            }
        });
    }

    private void onRequestSuccess() {
        consecutiveFailures.set(0);
        Health prior = health.getAndSet(new Health(Status.HEALTHY, "OpenAI moderation responding"));
        if (prior.status() == Status.DOWN) {
            alertStaff("AI chat moderation recovered; normal moderation requests have resumed.");
        }
    }

    private void onRequestFailure(Throwable failure) {
        int failures = consecutiveFailures.incrementAndGet();
        String reason = rootType(failure);
        if (failures >= config.failuresToOpen()) {
            circuitOpenUntil = clock.instant().plus(config.circuitOpenDuration());
            Health prior = health.getAndSet(new Health(Status.DOWN,
                    reason + "; circuit open until " + circuitOpenUntil));
            if (prior.status() != Status.DOWN) {
                alertStaff("AI chat moderation is DOWN and chat is fail-open. Reason: " + reason + '.');
            }
        } else {
            health.set(new Health(Status.DEGRADED, reason));
        }
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
