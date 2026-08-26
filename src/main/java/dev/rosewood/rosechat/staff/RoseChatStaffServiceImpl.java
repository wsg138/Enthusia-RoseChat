package dev.rosewood.rosechat.staff;

import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.api.staff.BridgeRegistration;
import dev.rosewood.rosechat.api.staff.BroadcastContext;
import dev.rosewood.rosechat.api.staff.ChannelClassification;
import dev.rosewood.rosechat.api.staff.ChannelRecipientContext;
import dev.rosewood.rosechat.api.staff.MessageSource;
import dev.rosewood.rosechat.api.staff.MessageSurface;
import dev.rosewood.rosechat.api.staff.ModerationDecision;
import dev.rosewood.rosechat.api.staff.PresenceContext;
import dev.rosewood.rosechat.api.staff.PresenceType;
import dev.rosewood.rosechat.api.staff.PrivateMessageContext;
import dev.rosewood.rosechat.api.staff.RoseChatModerationBridge;
import dev.rosewood.rosechat.api.staff.RoseChatStaffService;
import dev.rosewood.rosechat.api.staff.StaffChannelConfiguration;
import dev.rosewood.rosechat.api.staff.TransmissionContext;
import dev.rosewood.rosechat.chat.channel.Channel;
import dev.rosewood.rosechat.chat.channel.ChannelMessageOptions;
import dev.rosewood.rosechat.hook.channel.rosechat.GroupChannel;
import dev.rosewood.rosechat.manager.ChannelManager;
import dev.rosewood.rosechat.message.MessageDirection;
import dev.rosewood.rosechat.message.RoseMessage;
import dev.rosewood.rosechat.message.RosePlayer;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class RoseChatStaffServiceImpl implements RoseChatStaffService, AutoCloseable {
    private static final String DEFAULT_STAFF_CHANNEL = "staff";
    private static final String DEFAULT_GLOBAL_CHANNEL = "global";
    private static final String STAFF_CHANNEL_UNAVAILABLE =
            "Your message could not be sent to staff right now. Please try again shortly.";

    private final RoseChat plugin;
    private final StaffBridgeCoordinator bridge;

    public RoseChatStaffServiceImpl(RoseChat plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.bridge = new StaffBridgeCoordinator(plugin.getLogger());
    }

    @Override
    public Optional<String> getCurrentChannel(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return Optional.empty();
        }
        Channel channel = new RosePlayer(player).getChannel();
        return channel == null ? Optional.empty() : Optional.of(channel.getId());
    }

    @Override
    public boolean setCurrentChannel(UUID playerId, String channelId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(channelId, "channelId");
        if (!Bukkit.isPrimaryThread()) {
            return false;
        }

        Player player = Bukkit.getPlayer(playerId);
        Channel channel = this.findChannel(channelId);
        if (player == null || !player.isOnline() || channel == null) {
            return false;
        }

        RosePlayer rosePlayer = new RosePlayer(player);
        if (!channel.canJoinByCommand(rosePlayer)) {
            return false;
        }
        if (channel instanceof GroupChannel && !channel.getMembers().contains(playerId)) {
            return false;
        }
        return rosePlayer.switchChannel(channel, channel instanceof GroupChannel);
    }

    @Override
    public Optional<String> getStaffChannel() {
        return this.bridge.configuration()
                .map(StaffChannelConfiguration::staffChannelId)
                .or(() -> this.findChannel(DEFAULT_STAFF_CHANNEL) == null
                        ? Optional.empty()
                        : Optional.of(DEFAULT_STAFF_CHANNEL));
    }

    @Override
    public String getGlobalChannel() {
        Optional<String> configured = this.bridge.configuration()
                .map(StaffChannelConfiguration::globalChannelId)
                .filter(channel -> !channel.isBlank());
        if (configured.isPresent()) {
            return configured.orElseThrow();
        }

        Channel defaultChannel = this.plugin.getManager(ChannelManager.class).getDefaultChannel();
        return defaultChannel == null ? DEFAULT_GLOBAL_CHANNEL : defaultChannel.getId();
    }

    @Override
    public ChannelClassification classifyChannel(String channelId) {
        return this.bridge.classify(
                Objects.requireNonNull(channelId, "channelId"),
                DEFAULT_STAFF_CHANNEL
        );
    }

    @Override
    public boolean toggleStaffChannel(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Optional<String> current = this.getCurrentChannel(playerId);
        if (current.isEmpty()) {
            return false;
        }
        String staffChannel = this.getStaffChannel().orElse(null);
        if (staffChannel == null) {
            return false;
        }
        String destination = current.orElseThrow().equalsIgnoreCase(staffChannel)
                ? this.getGlobalChannel()
                : staffChannel;
        return this.setCurrentChannel(playerId, destination);
    }

    @Override
    public BridgeRegistration installBridge(
            String owner,
            StaffChannelConfiguration configuration,
            RoseChatModerationBridge bridge
    ) {
        return this.bridge.install(owner, configuration, bridge);
    }

    @Override
    public Optional<String> getBridgeOwner() {
        return this.bridge.owner();
    }

    public boolean allowChannelDispatch(
            RoseMessage message,
            String channelId,
            MessageDirection direction
    ) {
        UUID senderId = message.getSender().getUUID();
        if (senderId == null) {
            return true;
        }

        String senderName = safeName(message.getSender());
        ChannelClassification classification = this.classifyChannel(channelId);
        BroadcastContext broadcast = new BroadcastContext(
                message.getUUID(),
                senderId,
                senderName,
                channelId,
                classification,
                message.getPlayerInput(),
                source(direction)
        );
        ModerationDecision decision = this.bridge.beforeBroadcast(broadcast);
        if (decision.action() == ModerationDecision.Action.STAFF_ONLY
                && classification == ChannelClassification.STAFF) {
            return true;
        }
        return this.applyDecision(
                decision,
                message.getSender(),
                message.getPlayerInput(),
                channelId,
                MessageSurface.CHANNEL,
                true
        );
    }

    public boolean allowChannelPreflight(RosePlayer sender, String channelId, String message) {
        UUID senderId = sender.getUUID();
        if (senderId == null) {
            return true;
        }
        ModerationDecision decision = this.bridge.enforceMute(new TransmissionContext(
                senderId,
                safeName(sender),
                MessageSurface.CHANNEL,
                channelId,
                message
        ));
        return this.applyDecision(
                decision,
                sender,
                message,
                channelId,
                MessageSurface.CHANNEL,
                false
        );
    }

    public boolean allowPrivatePreflight(RoseMessage message, RosePlayer recipient, String plainMessage) {
        PrivateMessageContext context = this.privateContext(message, recipient, plainMessage);
        if (context == null) {
            return true;
        }
        ModerationDecision decision = this.bridge.enforceMute(new TransmissionContext(
                context.senderId(),
                context.senderName(),
                MessageSurface.PRIVATE_MESSAGE,
                context.recipientName(),
                context.message()
        ));
        return this.applyDecision(
                decision,
                message.getSender(),
                plainMessage,
                context.recipientName(),
                MessageSurface.PRIVATE_MESSAGE,
                false
        );
    }

    public boolean allowPrivateMessage(RoseMessage message, RosePlayer recipient, String plainMessage) {
        PrivateMessageContext context = this.privateContext(message, recipient, plainMessage);
        if (context == null) {
            return true;
        }
        ModerationDecision decision = this.bridge.beforePrivateMessage(context);
        return this.applyDecision(
                decision,
                message.getSender(),
                plainMessage,
                context.recipientName(),
                MessageSurface.PRIVATE_MESSAGE,
                true
        );
    }

    public void capturePrivateMessage(RoseMessage message, RosePlayer recipient, String plainMessage) {
        PrivateMessageContext context = this.privateContext(message, recipient, plainMessage);
        if (context != null) {
            this.bridge.capturePrivateMessage(context);
        }
    }

    public boolean canReceiveChannelMessage(RoseMessage message, RosePlayer recipient, String channelId) {
        UUID recipientId = recipient.getUUID();
        if (recipientId == null) {
            return true;
        }
        return this.bridge.canReceiveChannelMessage(new ChannelRecipientContext(
                message.getUUID(),
                Optional.ofNullable(message.getSender().getUUID()),
                recipientId,
                channelId,
                this.classifyChannel(channelId)
        ));
    }

    public boolean canRenderPresence(UUID subjectId, UUID viewerId, PresenceType type) {
        return this.bridge.canRenderPresence(new PresenceContext(subjectId, viewerId, type));
    }

    @Override
    public void close() {
        this.bridge.close();
    }

    private boolean applyDecision(
            ModerationDecision decision,
            RosePlayer sender,
            String message,
            String destination,
            MessageSurface surface,
            boolean messageAlreadyFiltered
    ) {
        return switch (decision.action()) {
            case ALLOW -> true;
            case BLOCK -> {
                if (!decision.feedback().isBlank()) {
                    sender.send(decision.feedback());
                }
                yield false;
            }
            case STAFF_ONLY -> {
                if (!this.sendToStaff(sender, message, destination, surface, messageAlreadyFiltered)) {
                    sender.send(STAFF_CHANNEL_UNAVAILABLE);
                }
                yield false;
            }
        };
    }

    private boolean sendToStaff(
            RosePlayer sender,
            String message,
            String destination,
            MessageSurface surface,
            boolean messageAlreadyFiltered
    ) {
        String staffChannelId = this.getStaffChannel().orElse(null);
        Channel staffChannel = staffChannelId == null ? null : this.findChannel(staffChannelId);
        if (staffChannel == null) {
            return false;
        }
        String staffMessage = surface == MessageSurface.PRIVATE_MESSAGE
                ? "[PM to " + destination + "] " + message
                : message;
        staffChannel.send(new ChannelMessageOptions.Builder()
                .sender(sender)
                .message(staffMessage)
                .bypassSlowmode(true)
                .bypassStaffBridge(true)
                .bypassMessageRules(messageAlreadyFiltered)
                .build());
        return true;
    }

    private PrivateMessageContext privateContext(
            RoseMessage message,
            RosePlayer recipient,
            String plainMessage
    ) {
        UUID senderId = message.getSender().getUUID();
        if (senderId == null) {
            return null;
        }
        return new PrivateMessageContext(
                message.getUUID(),
                senderId,
                safeName(message.getSender()),
                Optional.ofNullable(recipient.getUUID()),
                safeName(recipient),
                plainMessage
        );
    }

    private Channel findChannel(String channelId) {
        String normalized = channelId.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        ChannelManager channelManager = this.plugin.getManager(ChannelManager.class);
        Channel exact = channelManager.getChannel(normalized);
        if (exact != null) {
            return exact;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return channelManager.getChannels().entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(lower))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static MessageSource source(MessageDirection direction) {
        return switch (direction) {
            case SERVER_TO_SERVER, SERVER_TO_SERVER_RAW -> MessageSource.NETWORK;
            case DISCORD_TO_MINECRAFT -> MessageSource.DISCORD;
            default -> MessageSource.PLAYER;
        };
    }

    private static String safeName(RosePlayer player) {
        String name = player.getRealName();
        return name == null || name.isBlank() ? player.getName() : name;
    }
}
