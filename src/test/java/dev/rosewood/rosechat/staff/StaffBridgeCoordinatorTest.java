package dev.rosewood.rosechat.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rosewood.rosechat.api.staff.BridgeRegistration;
import dev.rosewood.rosechat.api.staff.BroadcastContext;
import dev.rosewood.rosechat.api.staff.ChannelClassification;
import dev.rosewood.rosechat.api.staff.ChannelRecipientContext;
import dev.rosewood.rosechat.api.staff.MessageSource;
import dev.rosewood.rosechat.api.staff.MessageSurface;
import dev.rosewood.rosechat.api.staff.ModerationDecision;
import dev.rosewood.rosechat.api.staff.PresenceContext;
import dev.rosewood.rosechat.api.staff.PresenceType;
import dev.rosewood.rosechat.api.staff.RoseChatModerationBridge;
import dev.rosewood.rosechat.api.staff.StaffChannelConfiguration;
import dev.rosewood.rosechat.api.staff.TransmissionContext;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class StaffBridgeCoordinatorTest {
    private static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SENDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RECIPIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void registrationIsExclusiveAndStaleCloseCannotRemoveReplacement() {
        StaffBridgeCoordinator coordinator = coordinator();
        BridgeRegistration first = coordinator.install("first", configuration(), new RoseChatModerationBridge() { });

        assertTrue(first.isActive());
        assertEquals(Optional.of("first"), coordinator.owner());
        assertThrows(
                IllegalStateException.class,
                () -> coordinator.install("second", configuration(), new RoseChatModerationBridge() { })
        );

        first.close();
        BridgeRegistration second = coordinator.install("second", configuration(), new RoseChatModerationBridge() { });
        first.close();

        assertFalse(first.isActive());
        assertTrue(second.isActive());
        assertEquals(Optional.of("second"), coordinator.owner());
    }

    @Test
    void muteDecisionCanBeEvaluatedBeforeBroadcastCallback() {
        StaffBridgeCoordinator coordinator = coordinator();
        boolean[] broadcastCalled = {false};
        coordinator.install("owner", configuration(), new RoseChatModerationBridge() {
            @Override
            public ModerationDecision enforceMute(TransmissionContext context) {
                return ModerationDecision.block("muted");
            }

            @Override
            public ModerationDecision beforeBroadcast(BroadcastContext context) {
                broadcastCalled[0] = true;
                return ModerationDecision.allow();
            }
        });

        ModerationDecision decision = coordinator.enforceMute(transmission());

        assertEquals(ModerationDecision.Action.BLOCK, decision.action());
        assertEquals("muted", decision.feedback());
        assertFalse(broadcastCalled[0]);
    }

    @Test
    void callbackFailuresFailClosed() {
        StaffBridgeCoordinator coordinator = coordinator();
        coordinator.install("owner", configuration(), new RoseChatModerationBridge() {
            @Override
            public ModerationDecision beforeBroadcast(BroadcastContext context) {
                throw new IllegalStateException("unavailable");
            }

            @Override
            public boolean canReceiveChannelMessage(ChannelRecipientContext context) {
                throw new IllegalStateException("unavailable");
            }

            @Override
            public boolean canRenderPresence(PresenceContext context) {
                throw new IllegalStateException("unavailable");
            }
        });

        assertEquals(
                ModerationDecision.Action.BLOCK,
                coordinator.beforeBroadcast(broadcast()).action()
        );
        assertFalse(coordinator.canReceiveChannelMessage(new ChannelRecipientContext(
                MESSAGE_ID,
                Optional.of(SENDER_ID),
                RECIPIENT_ID,
                "global",
                ChannelClassification.PUBLIC
        )));
        assertFalse(coordinator.canRenderPresence(
                new PresenceContext(SENDER_ID, RECIPIENT_ID, PresenceType.JOIN)
        ));
    }

    @Test
    void configuredChannelsAreClassifiedCaseInsensitively() {
        StaffBridgeCoordinator coordinator = coordinator();
        coordinator.install("owner", configuration(), new RoseChatModerationBridge() { });

        assertEquals(ChannelClassification.STAFF, coordinator.classify("STAFF", "fallback"));
        assertEquals(ChannelClassification.PRIVATE, coordinator.classify("Reports", "fallback"));
        assertEquals(ChannelClassification.PUBLIC, coordinator.classify("global", "fallback"));
    }

    @Test
    void closedCoordinatorRejectsLateRegistration() {
        StaffBridgeCoordinator coordinator = coordinator();
        coordinator.close();

        assertThrows(
                IllegalStateException.class,
                () -> coordinator.install("owner", configuration(), new RoseChatModerationBridge() { })
        );
    }

    @Test
    void concurrentInstallAllowsExactlyOneOwner() throws InterruptedException {
        StaffBridgeCoordinator coordinator = coordinator();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        ConcurrentLinkedQueue<BridgeRegistration> registrations = new ConcurrentLinkedQueue<>();

        for (int index = 0; index < 16; index++) {
            String owner = "owner-" + index;
            executor.execute(() -> {
                try {
                    start.await();
                    registrations.add(coordinator.install(
                            owner,
                            configuration(),
                            new RoseChatModerationBridge() { }
                    ));
                    successes.incrementAndGet();
                } catch (IllegalStateException ignored) {
                    // Another owner won the registration race.
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, successes.get());
        assertEquals(1, registrations.stream().filter(BridgeRegistration::isActive).count());
    }

    @Test
    void rejectsOwnerTextThatCouldForgeLogLines() {
        StaffBridgeCoordinator coordinator = coordinator();

        assertThrows(
                IllegalArgumentException.class,
                () -> coordinator.install("owner\nforged", configuration(), new RoseChatModerationBridge() { })
        );
    }

    private static StaffBridgeCoordinator coordinator() {
        return new StaffBridgeCoordinator(Logger.getLogger(StaffBridgeCoordinatorTest.class.getName()));
    }

    private static StaffChannelConfiguration configuration() {
        return new StaffChannelConfiguration("staff", "global", Set.of("reports"));
    }

    private static TransmissionContext transmission() {
        return new TransmissionContext(
                SENDER_ID,
                "Sender",
                MessageSurface.CHANNEL,
                "global",
                "hello"
        );
    }

    private static BroadcastContext broadcast() {
        return new BroadcastContext(
                MESSAGE_ID,
                SENDER_ID,
                "Sender",
                "global",
                ChannelClassification.PUBLIC,
                "hello",
                MessageSource.PLAYER
        );
    }
}
