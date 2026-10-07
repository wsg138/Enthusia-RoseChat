package dev.rosewood.rosechat.api.chatbridge;

import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.api.staff.ChannelClassification;
import dev.rosewood.rosechat.chat.channel.Channel;
import dev.rosewood.rosechat.chat.channel.ChannelMessageOptions;
import dev.rosewood.rosechat.config.Settings;
import dev.rosewood.rosechat.manager.ChannelManager;
import dev.rosewood.rosechat.message.MessageRules;
import dev.rosewood.rosechat.message.MessageUtils;
import dev.rosewood.rosechat.message.RoseMessage;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Bukkit;

/**
 * Canonical RoseChat admission path for authenticated Discord -> Minecraft transport.
 *
 * <p>This runtime does not authenticate a network peer. The external Enthusia transport must do
 * that before calling the API. RoseChat owns final channel/privacy/mute/filter admission and
 * always dispatches through the existing Discord-origin message direction so messages cannot
 * echo back to Discord or Bungee.</p>
 */
public final class InboundChatBridgeRuntime implements AutoCloseable {
    private static final int DEFAULT_DEDUPE_CAPACITY = 4_096;

    private final RoseChat plugin;
    private final Clock clock;
    private final int maximumDedupeEntries;
    private final Object dedupeLock = new Object();
    // Concurrency policy: every access to this insertion-ordered map is guarded by dedupeLock.
    private final Map<String, Long> dedupeUntil = new LinkedHashMap<>();
    private volatile boolean closed;

    public InboundChatBridgeRuntime(RoseChat plugin) {
        this(plugin, Clock.systemUTC(), DEFAULT_DEDUPE_CAPACITY);
    }

    InboundChatBridgeRuntime(RoseChat plugin, Clock clock, int maximumDedupeEntries) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumDedupeEntries < 1) {
            throw new IllegalArgumentException("maximumDedupeEntries must be positive");
        }
        this.maximumDedupeEntries = maximumDedupeEntries;
    }

    /**
     * Accepts one already-authenticated, explicitly routed Discord message.
     *
     * @param inbound bounded provider-neutral message
     * @return terminal admission result; only ACCEPTED/DUPLICATE are safe transport ACKs
     */
    public InboundChatResult accept(InboundDiscordChatMessage inbound) {
        Objects.requireNonNull(inbound, "inbound");
        if (this.closed) {
            return InboundChatResult.FAILED;
        }

        long now = this.clock.millis();
        if (inbound.isExpired(now)) {
            return InboundChatResult.EXPIRED;
        }

        Channel channel = this.plugin.getManager(ChannelManager.class).getChannel(inbound.logicalChannelId());
        if (channel == null) {
            return InboundChatResult.CHANNEL_NOT_FOUND;
        }
        if (this.plugin.getStaffService() == null) {
            return InboundChatResult.POLICY_UNAVAILABLE;
        }
        if (this.plugin.getStaffService().classifyChannel(inbound.logicalChannelId())
                != ChannelClassification.PUBLIC) {
            return InboundChatResult.CHANNEL_NOT_PUBLIC;
        }
        if (channel.isMuted()) {
            return InboundChatResult.CHANNEL_MUTED;
        }

        InboundChatResult reservation = this.reserve(inbound, now);
        if (reservation != null) {
            return reservation;
        }

        List<String> lines = this.lines(inbound);
        if (lines.size() > Settings.DISCORD_MESSAGE_LIMIT.get()) {
            return InboundChatResult.TOO_MANY_LINES;
        }

        RosePlayer sender = this.sender(inbound.sender());
        List<RoseMessage> prepared = new ArrayList<>();
        for (String line : lines) {
            if (MessageUtils.isMessageEmpty(line)) {
                continue;
            }
            RoseMessage message = RoseMessage.forChannel(sender, channel);
            message.setPlaceholders(placeholders(inbound.sender()));

            MessageRules rules = new MessageRules();
            if (Settings.REQUIRE_PERMISSIONS.get()) {
                rules.applyAllFilters();
            }
            MessageRules.RuleOutputs outputs = rules.apply(message, line);
            if (outputs.isBlocked()) {
                return InboundChatResult.BLOCKED;
            }
            message.setPlayerInput(outputs.getFilteredMessage());
            prepared.add(message);
        }
        if (prepared.isEmpty()) {
            return InboundChatResult.EMPTY;
        }

        String discordFormat = channel.getSettings().getFormats().get("discord-to-minecraft");
        String format = discordFormat != null ? discordFormat : channel.getSettings().getFormats().get("chat");
        try {
            for (RoseMessage message : prepared) {
                channel.send(new ChannelMessageOptions.Builder()
                        .wrapper(message)
                        .discordId(inbound.externalMessageId())
                        .format(format)
                        .build());
            }
            return InboundChatResult.ACCEPTED;
        } catch (RuntimeException exception) {
            this.plugin.getLogger().warning(
                    "Provider-neutral Discord ingress failed after admission: "
                            + exception.getClass().getSimpleName());
            return InboundChatResult.FAILED;
        }
    }

    private InboundChatResult reserve(InboundDiscordChatMessage inbound, long now) {
        synchronized (this.dedupeLock) {
            Long existing = this.dedupeUntil.get(inbound.externalMessageId());
            if (existing != null && existing >= now) {
                return InboundChatResult.DUPLICATE;
            }
            if (existing != null) {
                this.dedupeUntil.remove(inbound.externalMessageId());
            }
            this.dedupeUntil.entrySet().removeIf(entry -> entry.getValue() < now);
            if (this.dedupeUntil.size() >= this.maximumDedupeEntries) {
                return InboundChatResult.SATURATED;
            }
            this.dedupeUntil.put(inbound.externalMessageId(), inbound.expiresAtEpochMillis());
            return null;
        }
    }

    private RosePlayer sender(InboundChatSender sender) {
        RosePlayer player = sender.linkedMinecraftId() == null
                ? new RosePlayer(sender.displayName(), true)
                : new RosePlayer(Bukkit.getOfflinePlayer(sender.linkedMinecraftId()));
        player.setDiscordProxy(true);
        return player;
    }

    private static List<String> lines(InboundDiscordChatMessage inbound) {
        List<String> lines = new ArrayList<>();
        String plainText = inbound.plainText();
        if (!plainText.isEmpty()) {
            lines.addAll(List.of(plainText.split("\\R", -1)));
        }
        lines.addAll(inbound.attachmentUrls());
        return List.copyOf(lines);
    }

    private static StringPlaceholders placeholders(InboundChatSender sender) {
        return StringPlaceholders.builder()
                .add("user_name", sender.userName())
                .add("user_role", sender.roleName())
                .add("user_color", sender.colorHex())
                .add("user_tag", sender.userTag())
                .add("user_nickname", sender.displayName())
                .build();
    }

    @Override
    public void close() {
        this.closed = true;
        synchronized (this.dedupeLock) {
            this.dedupeUntil.clear();
        }
    }
}
