package dev.rosewood.rosechat.api.staff;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface RoseChatModerationBridge {
    /** Async Discord ingress verification; legacy bridges retain their existing mute policy. */
    default CompletionStage<ModerationDecision> enforceDiscordMute(TransmissionContext context) {
        return CompletableFuture.completedFuture(enforceMute(context));
    }

    default ModerationDecision enforceMute(TransmissionContext context) {
        return ModerationDecision.allow();
    }

    default ModerationDecision beforeBroadcast(BroadcastContext context) {
        return ModerationDecision.allow();
    }

    default ModerationDecision beforePrivateMessage(PrivateMessageContext context) {
        return ModerationDecision.allow();
    }

    default void capturePrivateMessage(PrivateMessageContext context) {
    }

    default boolean canReceiveChannelMessage(ChannelRecipientContext context) {
        return true;
    }

    default boolean canRenderPresence(PresenceContext context) {
        return true;
    }
}
