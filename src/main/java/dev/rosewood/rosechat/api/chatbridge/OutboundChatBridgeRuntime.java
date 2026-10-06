package dev.rosewood.rosechat.api.chatbridge;

import dev.rosewood.rosechat.api.staff.ChannelClassification;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Converts one policy-approved RoseChat message into the provider-neutral outbound bridge DTO.
 *
 * <p>This runtime is intentionally ephemeral. It owns no retry queue and delegates all
 * privacy, duplicate, size, expiry, and transport-failure handling to
 * {@link OutboundChatBridgeCoordinator}.</p>
 */
public final class OutboundChatBridgeRuntime {

    static final Duration DEFAULT_MESSAGE_TTL = Duration.ofSeconds(30);

    private final OutboundChatBridgeCoordinator coordinator;
    private final Clock clock;
    private final Duration messageTtl;

    /**
     * Creates the production runtime with a short best-effort delivery window.
     *
     * @param coordinator outbound bridge safety boundary
     */
    public OutboundChatBridgeRuntime(OutboundChatBridgeCoordinator coordinator) {
        this(coordinator, Clock.systemUTC(), DEFAULT_MESSAGE_TTL);
    }

    OutboundChatBridgeRuntime(
            OutboundChatBridgeCoordinator coordinator,
            Clock clock,
            Duration messageTtl
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.messageTtl = Objects.requireNonNull(messageTtl, "messageTtl");
        if (messageTtl.isZero() || messageTtl.isNegative()) {
            throw new IllegalArgumentException("outbound chat bridge message TTL must be positive");
        }
    }

    /**
     * Publishes one already-approved Minecraft chat message to the optional bridge.
     *
     * @param eventId stable RoseChat message UUID
     * @param minecraftPlayerId Minecraft sender UUID
     * @param displayName safe sender presentation name
     * @param plainText filtered canonical message text
     * @param logicalChannelId RoseChat channel id
     * @param classification resolved privacy classification
     * @return bridge dispatch result; failures never throw into chat delivery
     */
    public OutboundChatBridgeCoordinator.DispatchResult publish(
            UUID eventId,
            UUID minecraftPlayerId,
            String displayName,
            String plainText,
            String logicalChannelId,
            ChannelClassification classification
    ) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(minecraftPlayerId, "minecraftPlayerId");
        Objects.requireNonNull(classification, "classification");

        long now = this.clock.millis();
        String eventKey = eventId.toString();
        return this.coordinator.publish(new OutboundChatMessage(
                eventId,
                "rosechat-mc-" + eventKey,
                "rosechat-canonical-" + eventKey,
                now,
                now + this.messageTtl.toMillis(),
                logicalChannelId,
                classification,
                OutboundChatMessage.Origin.MINECRAFT,
                minecraftPlayerId,
                displayName,
                plainText
        ));
    }
}
