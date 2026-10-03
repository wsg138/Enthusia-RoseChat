package dev.rosewood.rosechat.moderation.ai;

import com.google.gson.Gson;
import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.api.RoseChatAPI;
import dev.rosewood.rosechat.api.staff.ChannelClassification;
import dev.rosewood.rosechat.chat.channel.Channel;
import dev.rosewood.rosechat.chat.channel.ChannelMessageOptions;
import dev.rosewood.rosechat.config.Settings;
import dev.rosewood.rosechat.message.DeletableMessage;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosechat.moderation.ai.central.CentralCredentials;
import dev.rosewood.rosechat.moderation.ai.central.CentralModerationClient;
import dev.rosewood.rosechat.moderation.ai.central.CentralModerationEngine;
import dev.rosewood.rosechat.moderation.ai.central.ChannelProfile;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Chat-moderation lifecycle for RoseChat.
 *
 * <p>The central Policy-v1 moderation service is the <strong>single</strong>
 * semantic moderation authority. The legacy local OpenAI threshold policy
 * ({@link AiModerationPolicy}), the direct OpenAI production path
 * ({@link OpenAiModerationClient} as a decision source), and the local strike
 * ledger escalation path are retired as production authorities: they are never
 * consulted for production ALLOW/DELETE while central mode is active, and no
 * automatic punishment is ever issued. EnthusiaStaff owns human review and all
 * punishment/case authority.</p>
 *
 * <p>RoseChat enforces only the central {@code message_action} (ALLOW/BLOCK).
 * Central {@code strike_recommendation} / {@code containment} values are
 * surfaced to staff diagnostics and the audit log; they are never converted
 * into mutes or bans by this plugin.</p>
 *
 * <p>Preserved safety properties: short bounded hold, async I/O (no
 * main-thread network), fail-open on every failure, circuit breaker,
 * generation fencing across reloads, exact-message late deletion, and staff
 * health/status visibility.</p>
 */
public final class AiModerationManager implements AutoCloseable, Listener {
    private static final int STAFF_ALERT_MESSAGE_LIMIT = 180;
    private static final int MAX_DELIVERY_TARGETS = 1024;

    private static final String COLOR_DARK_GRAY = "\u00A78";
    private static final String COLOR_GRAY = "\u00A77";
    private static final String COLOR_AQUA = "\u00A7b";
    private static final String COLOR_DARK_AQUA = "\u00A73";
    private static final String COLOR_GREEN = "\u00A7a";
    private static final String COLOR_YELLOW = "\u00A7e";
    private static final String COLOR_GOLD = "\u00A76";
    private static final String COLOR_RED = "\u00A7c";
    private static final String COLOR_BOLD = "\u00A7l";
    private static final String COLOR_RESET = "\u00A7r";

    private final RoseChat plugin;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService networkExecutor;
    private final AtomicReference<Health> health = new AtomicReference<>(Health.disabled());
    private final AiModerationMetrics metrics = new AiModerationMetrics();
    private final Map<UUID, DeliveryTarget> deliveryTargets = new ConcurrentHashMap<>();
    private final EngineActions engineActions = new EngineActions();
    private volatile AiModerationConfig config;
    private volatile AiModerationAuditStore auditStore;
    private volatile CentralModerationEngine engine;
    private volatile CentralModerationClient centralClient;

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
        ThreadFactory networkFactory = runnable -> {
            Thread thread = new Thread(runnable, "RoseChat-Central-Moderation-Net");
            thread.setDaemon(true);
            return thread;
        };
        this.networkExecutor = Executors.newSingleThreadExecutor(networkFactory);
        reload();
    }

    public synchronized void reload() {
        CentralModerationEngine previous = this.engine;
        if (previous != null) {
            previous.retire();
        }
        this.engine = null;
        this.centralClient = null;
        deliveryTargets.clear();

        AiModerationConfig loaded = AiModerationConfig.load(plugin);
        this.config = loaded;
        this.auditStore = new AiModerationAuditStore(
                plugin.getDataFolder().toPath().resolve("ai-moderation-audit"),
                clock,
                plugin.getLogger()
        );

        if (!loaded.enabled()) {
            this.health.set(Health.disabled());
            return;
        }
        if (!loaded.centralMode()) {
            this.health.set(new Health(Status.DOWN,
                    "central moderation is not configured (central.enabled=false or central.base-uri is blank); "
                            + "no semantic moderation is active and chat fails open"));
            plugin.getLogger().warning(
                    "AI moderation is enabled but the central service is not configured; chat will fail open "
                            + "with no semantic moderation. Set central.base-uri plus credentials.");
            return;
        }
        CentralCredentials credentials = CentralCredentials.resolve(loaded);
        if (!credentials.complete()) {
            this.health.set(new Health(Status.DOWN,
                    "central moderation credentials incomplete (" + credentials.safeSummary() + "); chat fails open"));
            plugin.getLogger().warning("Central AI moderation is enabled but credentials are incomplete ("
                    + credentials.safeSummary() + "); chat will fail open.");
            return;
        }
        if (loaded.punishmentsEnabled()) {
            plugin.getLogger().warning("ai-moderation.yml sets legacy punishments.enabled=true, which has no effect: "
                    + "central mode never records AI strikes or requests automatic punishments. "
                    + "EnthusiaStaff owns all punishment authority.");
        }

        URI baseUri = URI.create(loaded.centralBaseUri().trim());
        this.centralClient = new CentralModerationClient(
                HttpClient.newBuilder().connectTimeout(loaded.centralRequestTimeout()).build(),
                new Gson(),
                baseUri,
                credentials.clientId(),
                () -> CentralCredentials.resolve(loaded).token(),
                loaded.centralRequestTimeout()
        );
        CentralModerationEngine.EngineParams engineParams = new CentralModerationEngine.EngineParams(
                loaded.maximumChatHold(),
                loaded.centralRequestTimeout(),
                loaded.failuresToOpen(),
                loaded.circuitOpenDuration(),
                64,
                450,
                8,
                25,
                32,
                250L,
                8
        );
        this.engine = new CentralModerationEngine(
                centralClient::moderate,
                engineActions,
                engineParams,
                metrics,
                clock,
                scheduler,
                networkExecutor
        );
        this.health.set(new Health(Status.HEALTHY,
                "central moderation active (" + credentials.safeSummary() + ")"));
    }

    public void moderateAndSend(Channel channel, ChannelMessageOptions options) {
        AiModerationConfig current = this.config;
        if (!eligible(channel, options, current)) {
            channel.send(options);
            return;
        }
        CentralModerationEngine activeEngine = this.engine;
        if (activeEngine == null) {
            channel.send(options);
            return;
        }

        UUID eventId = UUID.randomUUID();
        UUID senderId = options.sender().getUUID();
        String senderName = safeName(options.sender());
        String scopeId = current.centralScopeId().isBlank() ? channel.getId() : current.centralScopeId();
        CentralModerationEngine.PendingMessage pending = new CentralModerationEngine.PendingMessage(
                eventId,
                ChannelProfile.MINECRAFT_PUBLIC,
                scopeId,
                channel.getId(),
                "",
                List.of(),
                senderId,
                senderName,
                options.message()
        );
        if (deliveryTargets.size() >= MAX_DELIVERY_TARGETS) {
            channel.send(options);
            return;
        }
        deliveryTargets.put(eventId, new ChannelDeliveryTarget(channel, options, options.sender()));
        activeEngine.submit(pending);
    }

    /**
     * Submits one already-filtered private message through the same bounded central engine.
     *
     * @return true when central moderation owns publication; false when the caller should fail open immediately
     */
    public boolean moderatePrivateAndSend(
            UUID eventId,
            RosePlayer sender,
            RosePlayer recipient,
            String message,
            Runnable publisher,
            Runnable blocked
    ) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(blocked, "blocked");

        AiModerationConfig current = this.config;
        CentralModerationEngine activeEngine = this.engine;
        UUID senderId = sender.getUUID();
        if (current == null || !current.enabled() || activeEngine == null
                || !sender.isPlayer() || senderId == null) {
            return false;
        }
        if (deliveryTargets.size() >= MAX_DELIVERY_TARGETS) {
            return false;
        }

        UUID recipientId = recipient.getUUID();
        List<UUID> recipientIds = recipientId == null ? List.of() : List.of(recipientId);
        String scopeId = current.centralScopeId().isBlank() ? "minecraft-private" : current.centralScopeId();
        String conversationId = recipientId == null ? "" : privateConversationId(senderId, recipientId);
        CentralModerationEngine.PendingMessage pending = new CentralModerationEngine.PendingMessage(
                eventId,
                ChannelProfile.MINECRAFT_PRIVATE,
                scopeId,
                "private",
                conversationId,
                recipientIds,
                senderId,
                safeName(sender),
                message,
                false
        );
        deliveryTargets.put(eventId, new PrivateDeliveryTarget(sender, publisher, blocked));
        activeEngine.submit(pending);
        return true;
    }

    private static String privateConversationId(UUID senderId, UUID recipientId) {
        String first = senderId.toString();
        String second = recipientId.toString();
        return first.compareTo(second) <= 0
                ? "minecraft-private:" + first + ':' + second
                : "minecraft-private:" + second + ':' + first;
    }

    private boolean eligible(Channel channel, ChannelMessageOptions options, AiModerationConfig current) {
        if (current == null || !current.enabled() || options.sender() == null
                || !options.sender().isPlayer() || options.sender().getUUID() == null) {
            return false;
        }
        if (plugin.getStaffService() == null) {
            return true;
        }
        ChannelClassification classification = plugin.getStaffService().classifyChannel(channel.getId());
        ChannelProfile profile = ChannelProfile.forClassification(classification);
        if (profile == ChannelProfile.EXEMPT) {
            return false;
        }
        return profile == ChannelProfile.MINECRAFT_PUBLIC;
    }

    private final class EngineActions implements CentralModerationEngine.Actions {
        @Override
        public void publish(CentralModerationEngine.PendingMessage pending) {
            DeliveryTarget target = deliveryTargets.remove(pending.eventId);
            if (target == null) {
                return;
            }
            if (target instanceof ChannelDeliveryTarget channelTarget) {
                Set<UUID> beforeMessageIds = messageIds(channelTarget.sender());
                channelTarget.channel().send(channelTarget.options());
                beginResolvePublishedUuid(pending, channelTarget, beforeMessageIds, 0);
                return;
            }
            if (target instanceof PrivateDeliveryTarget privateTarget) {
                Bukkit.getScheduler().runTask(plugin, privateTarget.publisher());
            }
        }

        @Override
        public void notifyBlocked(UUID senderId, String notice) {
            notifyPlayer(senderId, notice);
        }

        @Override
        public void notifyRemoved(UUID senderId, String notice) {
            notifyPlayer(senderId, notice);
        }

        @Override
        public void deleteExactMessage(UUID rosechatMessageId) {
            Bukkit.getScheduler().runTask(plugin, () -> deletePublishedOnServerThread(rosechatMessageId));
        }

        @Override
        public void alertStaff(String detail) {
            alertStaffRaw(staffAlertPrefix()
                    + COLOR_GOLD + COLOR_BOLD + "AI" + COLOR_RESET
                    + COLOR_DARK_GRAY + " • " + COLOR_GRAY + detail);
        }

        @Override
        public void audit(CentralModerationEngine.AuditRecord record) {
            if ("CENTRAL_BLOCK_PRE_BROADCAST".equals(record.outcome())) {
                DeliveryTarget target = deliveryTargets.remove(record.eventId());
                if (target instanceof PrivateDeliveryTarget privateTarget) {
                    Bukkit.getScheduler().runTask(plugin, privateTarget.blocked());
                }
            }
            AiModerationAuditStore store = auditStore;
            if (store == null) {
                return;
            }
            store.record(new AiModerationAuditStore.Entry(
                    record.at(), record.eventId(), record.senderId(), record.senderName(), record.channelId(),
                    record.text(), record.outcome(), record.semanticLabel(),
                    record.confidence() == null ? 0.0D : record.confidence(), 0, record.latencyMs()
            ));
        }
    }

    private void beginResolvePublishedUuid(
            CentralModerationEngine.PendingMessage pending,
            ChannelDeliveryTarget target,
            Set<UUID> beforeMessageIds,
            int attempt
    ) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            UUID resolved = uniqueNewMessageId(target, beforeMessageIds);
            if (resolved != null) {
                CentralModerationEngine activeEngine = engine;
                if (activeEngine != null) {
                    activeEngine.notePublished(pending.externalMessageId, resolved);
                    activeEngine.registerMirrorAlias(pending.canonicalMessageId, pending.externalMessageId);
                }
                return;
            }
            if (attempt < 8) {
                scheduler.schedule(
                        () -> beginResolvePublishedUuid(pending, target, beforeMessageIds, attempt + 1),
                        50, TimeUnit.MILLISECONDS);
            }
        });
    }

    private UUID uniqueNewMessageId(ChannelDeliveryTarget target, Set<UUID> beforeMessageIds) {
        if (target.sender().getPlayerData() == null) {
            return null;
        }
        List<DeletableMessage> messages = target.sender().getPlayerData().getMessageLog().getDeletableMessages();
        UUID candidate = null;
        synchronized (messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                DeletableMessage message = messages.get(i);
                if (beforeMessageIds.contains(message.getUUID())) {
                    continue;
                }
                if (!Objects.equals(message.getSender(), target.sender().getUUID())
                        || !Objects.equals(message.getChannel(), target.channel().getId())) {
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

    private void deletePublishedOnServerThread(UUID messageId) {
        boolean discordIdMissingBeforeDelete = findDiscordId(messageId) == null;
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            RosePlayer rosePlayer = new RosePlayer(player);
            if (rosePlayer.getPlayerData() != null) {
                RoseChatAPI.getInstance().deleteMessage(rosePlayer, messageId);
            }
        }
        propagateNetworkDeletion(messageId);
        if (discordIdMissingBeforeDelete && Settings.DELETE_DISCORD_MESSAGES.get()) {
            retryDiscordDeletion(messageId, 0);
        }
    }

    private void propagateNetworkDeletion(UUID messageId) {
        RoseChatAPI api = RoseChatAPI.getInstance();
        if (!api.isBungee()) {
            return;
        }
        Set<String> servers = new HashSet<>();
        for (Channel channel : api.getChannels()) {
            servers.addAll(channel.getServers());
        }
        for (String server : servers) {
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

    private void notifyPlayer(UUID playerId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        });
    }

    private void alertStaffRaw(String formattedMessage) {
        AiModerationConfig current = this.config;
        String permission = current == null ? "rosechat.ai.alerts" : current.staffStatusPermission();
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.hasPermission(permission))
                .forEach(player -> player.sendMessage(formattedMessage)));
    }

    private static String staffAlertPrefix() {
        return COLOR_DARK_GRAY + '[' + COLOR_AQUA + COLOR_BOLD + "AI MOD"
                + COLOR_RESET + COLOR_DARK_GRAY + "] ";
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        AiModerationConfig current = this.config;
        Health currentHealth = this.health.get();
        if (current == null || !current.enabled() || currentHealth.status() == Status.HEALTHY
                || !event.getPlayer().hasPermission(current.staffStatusPermission())) {
            return;
        }
        event.getPlayer().sendMessage(staffAlertPrefix()
                + COLOR_RED + COLOR_BOLD + "FAIL-OPEN" + COLOR_RESET
                + COLOR_DARK_GRAY + " • " + COLOR_GRAY + currentHealth.status()
                + ": " + currentHealth.detail());
    }

    public Health health() {
        CentralModerationEngine activeEngine = this.engine;
        Health base = health.get();
        if (activeEngine == null) {
            return base;
        }
        CentralModerationEngine.EngineHealth engineHealth = activeEngine.health();
        AiModerationMetrics.Snapshot snapshot = metrics.snapshot();
        String detail = base.detail()
                + " | circuit=" + (engineHealth.circuitOpen() ? "OPEN" : "closed")
                + " inFlight=" + engineHealth.inFlight()
                + " allow=" + snapshot.allows() + " block=" + snapshot.centralBlocked()
                + " timeout=" + snapshot.centralTimeouts() + " conflict=" + snapshot.centralConflicts()
                + " degraded=" + snapshot.centralDegraded()
                + " p95=" + snapshot.p95LatencyMs() + "ms"
                + (engineHealth.lastPolicyVersion().isBlank() ? ""
                        : " policy=" + engineHealth.lastPolicyVersion())
                + (engineHealth.lastModelVersion().isBlank() ? ""
                        : " model=" + engineHealth.lastModelVersion())
                + (engineHealth.lastFailureCategory().isBlank() ? ""
                        : " lastFailure=" + engineHealth.lastFailureCategory());
        Status status = base.status();
        if (engineHealth.circuitOpen()) {
            status = Status.DOWN;
        } else if (engineHealth.consecutiveFailures() > 0) {
            status = Status.DEGRADED;
        }
        return new Health(status, detail);
    }

    public AiModerationMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }

    /**
     * @return the active central engine, or {@code null} when central mode is
     * not active (chat fails open with no semantic moderation).
     */
    CentralModerationEngine centralEngine() {
        return engine;
    }

    @Override
    public void close() {
        CentralModerationEngine activeEngine = this.engine;
        if (activeEngine != null) {
            activeEngine.retire();
            this.engine = null;
        }
        scheduler.shutdownNow();
        networkExecutor.shutdownNow();
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

    private sealed interface DeliveryTarget permits ChannelDeliveryTarget, PrivateDeliveryTarget {
        RosePlayer sender();
    }

    private record ChannelDeliveryTarget(
            Channel channel,
            ChannelMessageOptions options,
            RosePlayer sender
    ) implements DeliveryTarget {
    }

    private record PrivateDeliveryTarget(
            RosePlayer sender,
            Runnable publisher,
            Runnable blocked
    ) implements DeliveryTarget {
    }
}
