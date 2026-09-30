package dev.rosewood.rosechat.api.chatbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rosewood.rosechat.api.staff.ChannelClassification;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutboundChatBridgeCoordinatorTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    /** Verifies that eligible public Minecraft chat reaches the installed bridge. */
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

    /** Verifies that provider failure is contained and does not throw into Minecraft chat. */
    @Test
    void outageFailsOpenWithoutThrowingIntoChat() {
        OutboundChatBridgeCoordinator coordinator = coordinator();
        coordinator.install(message -> { throw new IllegalStateException("discord unavailable"); });

        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.FAILED,
                coordinator.publish(message(UUID.randomUUID(), ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT, "still visible in Minecraft")));
    }

    /** Verifies that an absent bridge is a no-op for the Minecraft chat path. */
    @Test
    void missingBridgeFailsOpen() {
        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.NO_BRIDGE,
                coordinator().publish(message(UUID.randomUUID(), ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT, "minecraft continues")));
    }

    /** Verifies that Discord-originated messages are never echoed back to Discord. */
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

    /** Verifies duplicate event IDs are delivered at most once in the dedupe window. */
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

    /** Verifies private and staff channels never reach the public bridge. */
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

    /** Verifies expiry and plain-text size bounds reject invalid export work. */
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

    /** Verifies sequential admission applies backpressure once dedupe state reaches its cap. */
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

    /** Verifies concurrent publishers cannot reserve more entries than the configured hard cap. */
    @Test
    void concurrentAdmissionCannotExceedDedupeCapacity() throws Exception {
        OutboundChatBridgeCoordinator coordinator = new OutboundChatBridgeCoordinator(
                CLOCK, Duration.ofSeconds(30), 1);
        AtomicInteger deliveries = new AtomicInteger();
        coordinator.install(message -> deliveries.incrementAndGet());

        int publisherCount = 16;
        ExecutorService executor = Executors.newFixedThreadPool(publisherCount);
        CountDownLatch ready = new CountDownLatch(publisherCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OutboundChatBridgeCoordinator.DispatchResult>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < publisherCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return coordinator.publish(message(UUID.randomUUID(), ChannelClassification.PUBLIC,
                            OutboundChatMessage.Origin.MINECRAFT, "concurrent"));
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int delivered = 0;
            int backpressured = 0;
            for (Future<OutboundChatBridgeCoordinator.DispatchResult> future : futures) {
                OutboundChatBridgeCoordinator.DispatchResult result = future.get(5, TimeUnit.SECONDS);
                if (result == OutboundChatBridgeCoordinator.DispatchResult.DELIVERED) {
                    delivered++;
                } else if (result == OutboundChatBridgeCoordinator.DispatchResult.BACKPRESSURE) {
                    backpressured++;
                }
            }

            assertEquals(1, delivered);
            assertEquals(publisherCount - 1, backpressured);
            assertEquals(1, deliveries.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /** Verifies registrations cannot replace an owner without first closing it. */
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

    /** Verifies a stale registration cannot remove a later installation of the same bridge instance. */
    @Test
    void staleRegistrationCannotRemoveReinstalledSameBridgeInstance() {
        OutboundChatBridgeCoordinator coordinator = coordinator();
        AtomicInteger deliveries = new AtomicInteger();
        OutboundChatBridge bridge = message -> deliveries.incrementAndGet();

        OutboundChatBridgeCoordinator.Registration oldRegistration = coordinator.install(bridge);
        oldRegistration.close();
        OutboundChatBridgeCoordinator.Registration currentRegistration = coordinator.install(bridge);

        oldRegistration.close();
        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.DELIVERED,
                coordinator.publish(message(UUID.randomUUID(), ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT, "new installation remains")));
        assertEquals(1, deliveries.get());

        currentRegistration.close();
        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.NO_BRIDGE,
                coordinator.publish(message(UUID.randomUUID(), ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT, "closed")));
    }

    /** Creates the default deterministic test coordinator. */
    private static OutboundChatBridgeCoordinator coordinator() {
        return new OutboundChatBridgeCoordinator(CLOCK, Duration.ofSeconds(30), 32);
    }

    /** Creates a valid outbound test message with a short lifetime. */
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
