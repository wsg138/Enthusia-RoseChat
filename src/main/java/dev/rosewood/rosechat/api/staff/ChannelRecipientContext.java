package dev.rosewood.rosechat.api.staff;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ChannelRecipientContext(
        UUID messageId,
        Optional<UUID> senderId,
        UUID recipientId,
        String channelId,
        ChannelClassification classification
) {
    public ChannelRecipientContext {
        Objects.requireNonNull(messageId, "messageId");
        senderId = senderId == null ? Optional.empty() : senderId;
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(classification, "classification");
    }
}
