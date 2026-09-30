package dev.rosewood.rosechat.api.chatbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.rosewood.rosechat.api.staff.ChannelClassification;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutboundChatBridgeCoordinatorTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    @Test
    void deliversEligiblePublicMinecraftChat() {
        OutboundChatBridgeCoordinator coordinator = coordinator();
        AtomicInteger deliveries = new AtomicInteger();
        coordinator.install(message -> deliveries.incrementAndGet());

        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.DELIVERED,
                coordinator.publish(message(UUID.randomUUID(), ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT, "hello")));
        assertEquals(1, deliveries.get());
    }

    @Test
    void outageFailsOpenWithoutThrowingIntoChat() {
        OutboundChatBridgeCoordinator coordinator = coordinator();
        coordinator.install(message -> { throw new IllegalStateException("discord unavailable"); });

        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.FAILED,
                coordinator.publish(message(UUID.randomUUID(), ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT, "still visible in Minecraft")));
    }

    @Test
    void missingBridgeFailsOpen() {
        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.NO_BRIDGE,
                coordinator().publish(message(UUID.randomUUID(), ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT, "minecraft continues")));
    }

    @Test
    void suppressesDiscordEcho() {
        OutboundChatBridgeCoordinator coordinator = coordinator();
        AtomicInteger deliveries = new AtomicInteger();
        coordinator.install(message -> deliveries.incrementAndGet());

        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.LOOP_SUPPRESSED,
                coordinator.publish(message(UUID.randomUUID(), ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.DISCORD, "do not echo")));
        assertEquals(0, deliveries.get());
    }

    @Test
    void suppressesDuplicateEventIds() {
        OutboundChatBridgeCoordinator coordinator = coordinator();
        AtomicInteger deliveries = new AtomicInteger();
        coordinator.install(message -> deliveries.incrementAndGet());
        UUID eventId = UUID.randomUUID();
        OutboundChatMessage message = message(eventId, ChannelClassification.PUBLIC,
                OutboundChatMessage.Origin.MINECRAFT, "once");

        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.DELIVERED, coordinator.publish(message));
        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.DUPLICATE, coordinator.publish(message));
        assertEquals(1, deliveries.get());
    }

    @Test
    void neverExportsPrivateOrStaffChat() {
        OutboundChatBridgeCoordinator coordinator = coordinator();
        AtomicInteger deliveries = new AtomicInteger();
        coordinator.install(message -> deliveries.incrementAndGet());

        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.NOT_PUBLIC,
                coordinator.publish(message(UUID.randomUUID(), ChannelClassification.PRIVATE,
                        OutboundChatMessage.Origin.MINECRAFT, "private")));
        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.NOT_PUBLIC,
                coordinator.publish(message(UUID.randomUUID(), ChannelClassification.STAFF,
                        OutboundChatMessage.Origin.MINECRAFT, "staff")));
        assertEquals(0, deliveries.get());
    }

    @Test
    void rejectsExpiredAndOversizePayloads() {
        OutboundChatBridgeCoordinator coordinator = coordinator();
        coordinator.install(message -> { });
        OutboundChatMessage expired = new OutboundChatMessage(
                UUID.randomUUID(), NOW - 2_000, NOW - 1_000, "global", ChannelClassification.PUBLIC,
                OutboundChatMessage.Origin.MINECRAFT, UUID.randomUUID(), "Player", "old");

        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.EXPIRED, coordinator.publish(expired));
        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.OVERSIZE,
                coordinator.publish(message(UUID.randomUUID(), ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT,
                        "x".repeat(OutboundChatBridgeCoordinator.MAX_PLAIN_TEXT_LENGTH + 1))));
    }

    @Test
    void boundsDuplicateStateInsteadOfGrowingUnbounded() {
        OutboundChatBridgeCoordinator coordinator = new OutboundChatBridgeCoordinator(
                CLOCK, Duration.ofSeconds(30), 1);
        coordinator.install(message -> { });

        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.DELIVERED,
                coordinator.publish(message(UUID.randomUUID(), ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT, "first")));
        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.BACKPRESSURE,
                coordinator.publish(message(UUID.randomUUID(), ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT, "second")));
    }

    @Test
    void registrationCannotBeAccidentallyReplacedAndCloseIsOwnerSafe() {
        OutboundChatBridgeCoordinator coordinator = coordinator();
        OutboundChatBridgeCoordinator.Registration registration = coordinator.install(message -> { });
        assertThrows(IllegalStateException.class, () -> coordinator.install(message -> { }));

        registration.close();
        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.NO_BRIDGE,
                coordinator.publish(message(UUID.randomUUID(), ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT, "after shutdown")));
    }

    private static OutboundChatBridgeCoordinator coordinator() {
        return new OutboundChatBridgeCoordinator(CLOCK, Duration.ofSeconds(30), 32);
    }

    private static OutboundChatMessage message(
            UUID eventId,
            ChannelClassification classification,
            OutboundChatMessage.Origin origin,
            String text
    ) {
        return new OutboundChatMessage(
                eventId,
                NOW,
                NOW + 5_000,
                "global",
                classification,
                origin,
                UUID.randomUUID(),
                "Player",
                text
        );
    }
}
