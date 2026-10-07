package dev.rosewood.rosechat.api.chatbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.rosewood.rosechat.api.staff.ChannelClassification;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OutboundChatRenderBridgeCoordinatorTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    @Test
    void deliversPublicMinecraftRenderAndPreservesStyledPayload() {
        OutboundChatRenderBridgeCoordinator coordinator = coordinator(32);
        AtomicReference<RenderedOutboundChatMessage> delivered = new AtomicReference<>();
        coordinator.install(delivered::set);
        RenderedOutboundChatMessage rendered = rendered(
                UUID.randomUUID(),
                ChannelClassification.PUBLIC,
                OutboundChatMessage.Origin.MINECRAFT,
                NOW,
                NOW + 30_000
        );

        assertEquals(
                OutboundChatRenderBridgeCoordinator.DispatchResult.DELIVERED,
                coordinator.publish(rendered)
        );
        assertEquals(rendered, delivered.get());
    }

    @Test
    void suppressesPrivacyLoopExpiryAndDuplicates() {
        OutboundChatRenderBridgeCoordinator coordinator = coordinator(32);
        AtomicInteger deliveries = new AtomicInteger();
        coordinator.install(message -> deliveries.incrementAndGet());

        assertEquals(
                OutboundChatRenderBridgeCoordinator.DispatchResult.NOT_PUBLIC,
                coordinator.publish(rendered(
                        UUID.randomUUID(),
                        ChannelClassification.PRIVATE,
                        OutboundChatMessage.Origin.MINECRAFT,
                        NOW,
                        NOW + 30_000
                ))
        );
        assertEquals(
                OutboundChatRenderBridgeCoordinator.DispatchResult.LOOP_SUPPRESSED,
                coordinator.publish(rendered(
                        UUID.randomUUID(),
                        ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.DISCORD,
                        NOW,
                        NOW + 30_000
                ))
        );
        assertEquals(
                OutboundChatRenderBridgeCoordinator.DispatchResult.EXPIRED,
                coordinator.publish(rendered(
                        UUID.randomUUID(),
                        ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT,
                        NOW - 30_000,
                        NOW - 1
                ))
        );

        RenderedOutboundChatMessage once = rendered(
                UUID.randomUUID(),
                ChannelClassification.PUBLIC,
                OutboundChatMessage.Origin.MINECRAFT,
                NOW,
                NOW + 30_000
        );
        assertEquals(
                OutboundChatRenderBridgeCoordinator.DispatchResult.DELIVERED,
                coordinator.publish(once)
        );
        assertEquals(
                OutboundChatRenderBridgeCoordinator.DispatchResult.DUPLICATE,
                coordinator.publish(once)
        );
        assertEquals(1, deliveries.get());
    }

    @Test
    void containsProviderFailureAndBoundsDedupeAdmission() {
        OutboundChatRenderBridgeCoordinator failing = coordinator(32);
        failing.install(message -> {
            throw new IllegalStateException("discord renderer unavailable");
        });
        assertEquals(
                OutboundChatRenderBridgeCoordinator.DispatchResult.FAILED,
                failing.publish(rendered(
                        UUID.randomUUID(),
                        ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT,
                        NOW,
                        NOW + 30_000
                ))
        );

        OutboundChatRenderBridgeCoordinator bounded = coordinator(1);
        bounded.install(message -> { });
        assertEquals(
                OutboundChatRenderBridgeCoordinator.DispatchResult.DELIVERED,
                bounded.publish(rendered(
                        UUID.randomUUID(),
                        ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT,
                        NOW,
                        NOW + 30_000
                ))
        );
        assertEquals(
                OutboundChatRenderBridgeCoordinator.DispatchResult.BACKPRESSURE,
                bounded.publish(rendered(
                        UUID.randomUUID(),
                        ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT,
                        NOW,
                        NOW + 30_000
                ))
        );
    }

    @Test
    void missingAndClosedBridgeFailOpen() {
        OutboundChatRenderBridgeCoordinator coordinator = coordinator(32);
        RenderedOutboundChatMessage rendered = rendered(
                UUID.randomUUID(),
                ChannelClassification.PUBLIC,
                OutboundChatMessage.Origin.MINECRAFT,
                NOW,
                NOW + 30_000
        );

        assertEquals(
                OutboundChatRenderBridgeCoordinator.DispatchResult.NO_BRIDGE,
                coordinator.publish(rendered)
        );
        coordinator.install(message -> { });
        coordinator.close();
        assertEquals(
                OutboundChatRenderBridgeCoordinator.DispatchResult.NO_BRIDGE,
                coordinator.publish(rendered(
                        UUID.randomUUID(),
                        ChannelClassification.PUBLIC,
                        OutboundChatMessage.Origin.MINECRAFT,
                        NOW,
                        NOW + 30_000
                ))
        );
    }

    private static OutboundChatRenderBridgeCoordinator coordinator(int maximumDedupeEntries) {
        return new OutboundChatRenderBridgeCoordinator(
                CLOCK,
                Duration.ofSeconds(30),
                maximumDedupeEntries
        );
    }

    private static RenderedOutboundChatMessage rendered(
            UUID eventId,
            ChannelClassification classification,
            OutboundChatMessage.Origin origin,
            long createdAt,
            long expiresAt
    ) {
        OutboundChatMessage base = new OutboundChatMessage(
                eventId,
                "rosechat-mc-" + eventId,
                "rosechat-canonical-" + eventId,
                createdAt,
                expiresAt,
                "global",
                classification,
                origin,
                UUID.randomUUID(),
                "Player",
                "hello"
        );
        return new RenderedOutboundChatMessage(
                base,
                "hello",
                "**hello**",
                "{\"text\":\"hello\",\"color\":\"#12ABEF\"}",
                "Player: hello",
                "**Player:** **hello**",
                "{\"text\":\"Player: hello\",\"color\":\"#12ABEF\"}"
        );
    }
}
