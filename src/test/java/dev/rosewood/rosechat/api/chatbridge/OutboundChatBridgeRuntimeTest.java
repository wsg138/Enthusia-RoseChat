package dev.rosewood.rosechat.api.chatbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.rosewood.rosechat.api.staff.ChannelClassification;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OutboundChatBridgeRuntimeTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    @Test
    void buildsStableMinecraftAndCanonicalIds() {
        OutboundChatBridgeCoordinator coordinator = new OutboundChatBridgeCoordinator(
                CLOCK, Duration.ofSeconds(30), 32);
        AtomicReference<OutboundChatMessage> delivered = new AtomicReference<>();
        coordinator.install(delivered::set);
        OutboundChatBridgeRuntime runtime = new OutboundChatBridgeRuntime(
                coordinator, CLOCK, Duration.ofSeconds(15));
        UUID eventId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();

        assertEquals(
                OutboundChatBridgeCoordinator.DispatchResult.DELIVERED,
                runtime.publish(
                        eventId,
                        playerId,
                        "Player",
                        "hello",
                        "global",
                        ChannelClassification.PUBLIC
                )
        );

        OutboundChatMessage message = delivered.get();
        assertEquals(eventId, message.eventId());
        assertEquals("rosechat-mc-" + eventId, message.externalMessageId());
        assertEquals("rosechat-canonical-" + eventId, message.canonicalMessageId());
        assertEquals(NOW, message.createdAtEpochMillis());
        assertEquals(NOW + 15_000, message.expiresAtEpochMillis());
        assertEquals(playerId, message.minecraftPlayerId());
        assertEquals(OutboundChatMessage.Origin.MINECRAFT, message.origin());
    }

    @Test
    void preservesPrivacyClassificationForCoordinatorEnforcement() {
        OutboundChatBridgeCoordinator coordinator = new OutboundChatBridgeCoordinator(
                CLOCK, Duration.ofSeconds(30), 32);
        coordinator.install(message -> { });
        OutboundChatBridgeRuntime runtime = new OutboundChatBridgeRuntime(
                coordinator, CLOCK, Duration.ofSeconds(15));

        assertEquals(
                OutboundChatBridgeCoordinator.DispatchResult.NOT_PUBLIC,
                runtime.publish(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Staff",
                        "private",
                        "staff",
                        ChannelClassification.STAFF
                )
        );
    }
}
