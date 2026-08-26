package dev.rosewood.rosechat.api.staff;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record PrivateMessageContext(
        UUID messageId,
        UUID senderId,
        String senderName,
        Optional<UUID> recipientId,
        String recipientName,
        String message
) {
    public PrivateMessageContext {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(senderName, "senderName");
        recipientId = recipientId == null ? Optional.empty() : recipientId;
        Objects.requireNonNull(recipientName, "recipientName");
        Objects.requireNonNull(message, "message");
    }
}
