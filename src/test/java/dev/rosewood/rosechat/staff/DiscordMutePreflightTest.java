package dev.rosewood.rosechat.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.rosewood.rosechat.api.staff.MessageSurface;
import dev.rosewood.rosechat.api.staff.ModerationDecision;
import dev.rosewood.rosechat.api.staff.RoseChatModerationBridge;
import dev.rosewood.rosechat.api.staff.StaffChannelConfiguration;
import dev.rosewood.rosechat.api.staff.TransmissionContext;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class DiscordMutePreflightTest {
    private static final TransmissionContext CONTEXT = new TransmissionContext(
            new UUID(0, 42), "Offline", MessageSurface.CHANNEL, "global", "hello");

    @Test
    void waitsForAuthoritativeOfflineDecisionInsteadOfUsingOnlineCache() {
        var pending = new CompletableFuture<ModerationDecision>();
        var coordinator = coordinator();
        coordinator.install("Staff", channels(), new RoseChatModerationBridge() {
            @Override
            public ModerationDecision enforceMute(TransmissionContext context) {
                return ModerationDecision.block("no online cache");
            }
            @Override
            public CompletionStage<ModerationDecision> enforceDiscordMute(TransmissionContext context) {
                assertEquals(CONTEXT, context);
                return pending;
            }
        });
        var result = coordinator.enforceDiscordMute(CONTEXT).toCompletableFuture();
        assertFalse(result.isDone());
        pending.complete(ModerationDecision.allow());
        assertEquals(ModerationDecision.Action.ALLOW, result.join().action());
    }

    @Test
    void legacyBridgeStillEnforcesMute() {
        var coordinator = coordinator();
        coordinator.install("legacy", channels(), new RoseChatModerationBridge() {
            @Override
            public ModerationDecision enforceMute(TransmissionContext context) {
                return ModerationDecision.block("muted");
            }
        });
        assertEquals(ModerationDecision.Action.BLOCK,
                coordinator.enforceDiscordMute(CONTEXT).toCompletableFuture().join().action());
    }

    @Test
    void removedBridgeCannotAuthorizeDelayedMessage() {
        var pending = new CompletableFuture<ModerationDecision>();
        var coordinator = coordinator();
        var registration = coordinator.install("Staff", channels(), new RoseChatModerationBridge() {
            @Override
            public CompletionStage<ModerationDecision> enforceDiscordMute(TransmissionContext context) {
                return pending;
            }
        });
        var result = coordinator.enforceDiscordMute(CONTEXT).toCompletableFuture();
        long revision = coordinator.revision();
        registration.close();
        coordinator.install("replacement", channels(), new RoseChatModerationBridge() { });
        pending.complete(ModerationDecision.allow());
        assertNotEquals(revision, coordinator.revision());
        assertEquals(ModerationDecision.Action.BLOCK, result.join().action());
    }

    @Test
    void nullAndFailedAsyncDecisionsFailClosed() {
        for (boolean fail : new boolean[]{false, true}) {
            var coordinator = coordinator();
            coordinator.install("broken", channels(), new RoseChatModerationBridge() {
                @Override
                public CompletionStage<ModerationDecision> enforceDiscordMute(TransmissionContext context) {
                    return fail ? CompletableFuture.failedFuture(new IllegalStateException())
                            : CompletableFuture.completedFuture(null);
                }
            });
            assertEquals(ModerationDecision.Action.BLOCK,
                    coordinator.enforceDiscordMute(CONTEXT).toCompletableFuture().join().action());
        }
    }

    @Test
    void hungProviderTimesOutWithoutMutatingItsFutureOrAllowingLateDelivery() {
        var pending = new CompletableFuture<ModerationDecision>();
        var coordinator = coordinator();
        coordinator.install("hung", channels(), new RoseChatModerationBridge() {
            @Override
            public CompletionStage<ModerationDecision> enforceDiscordMute(TransmissionContext context) {
                return pending;
            }
        });
        var result = coordinator.enforceDiscordMute(CONTEXT).toCompletableFuture();
        assertEquals(ModerationDecision.Action.BLOCK, result.join().action());
        assertFalse(pending.isDone());
        pending.complete(ModerationDecision.allow());
        assertEquals(ModerationDecision.Action.BLOCK, result.join().action());
    }

    @Test
    void closedServiceRejectsEvenMessagesWithoutAnInstalledBridge() {
        var coordinator = coordinator();
        coordinator.close();
        assertEquals(ModerationDecision.Action.BLOCK,
                coordinator.enforceDiscordMute(CONTEXT).toCompletableFuture().join().action());
    }

    private static StaffBridgeCoordinator coordinator() {
        return new StaffBridgeCoordinator(Logger.getAnonymousLogger(), context -> true);
    }

    private static StaffChannelConfiguration channels() {
        return new StaffChannelConfiguration("staff", "global", Set.of("reports"));
    }
}
