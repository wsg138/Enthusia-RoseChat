package dev.rosewood.rosechat.api.staff;

import java.util.Objects;
import java.util.UUID;

public record BroadcastContext(
        UUID messageId,
        UUID senderId,
        String senderName,
        String channelId,
        ChannelClassification classification,
        String message,
        MessageSource source
) {
    public BroadcastContext {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(senderName, "senderName");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(source, "source");
    }
}
