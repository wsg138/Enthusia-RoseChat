package dev.rosewood.rosechat.api.chatbridge;

import dev.rosewood.rosechat.api.staff.ChannelClassification;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds and publishes one styled provider-neutral render for an approved Minecraft chat event.
 */
public final class OutboundChatRenderBridgeRuntime {

    static final Duration DEFAULT_MESSAGE_TTL = Duration.ofSeconds(30);

    private final OutboundChatRenderBridgeCoordinator coordinator;
    private final Clock clock;
    private final Duration messageTtl;

    public OutboundChatRenderBridgeRuntime(OutboundChatRenderBridgeCoordinator coordinator) {
        this(coordinator, Clock.systemUTC(), DEFAULT_MESSAGE_TTL);
    }

    OutboundChatRenderBridgeRuntime(
            OutboundChatRenderBridgeCoordinator coordinator,
            Clock clock,
            Duration messageTtl
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.messageTtl = Objects.requireNonNull(messageTtl, "messageTtl");
        if (messageTtl.isZero() || messageTtl.isNegative()) {
            throw new IllegalArgumentException("outbound render bridge message TTL must be positive");
        }
    }

    public boolean installed() {
        return this.coordinator.installed();
    }

    public OutboundChatRenderBridgeCoordinator.DispatchResult publish(
            UUID eventId,
            UUID minecraftPlayerId,
            String displayName,
            String canonicalPlainText,
            String logicalChannelId,
            ChannelClassification classification,
            String bodyPlainText,
            String bodyMarkdown,
            String bodyAdventureJson,
            String linePlainText,
            String lineMarkdown,
            String lineAdventureJson
    ) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(minecraftPlayerId, "minecraftPlayerId");
        Objects.requireNonNull(classification, "classification");

        long now = this.clock.millis();
        String eventKey = eventId.toString();
        OutboundChatMessage base = new OutboundChatMessage(
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
                canonicalPlainText
        );
        return this.coordinator.publish(new RenderedOutboundChatMessage(
                base,
                bodyPlainText,
                bodyMarkdown,
                bodyAdventureJson,
                linePlainText,
                lineMarkdown,
                lineAdventureJson
        ));
    }
}
