package dev.rosewood.rosechat.api.staff;

public interface RoseChatModerationBridge {
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
