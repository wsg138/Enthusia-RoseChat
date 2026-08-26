package dev.rosewood.rosechat.api.staff;

import java.util.Objects;
import java.util.UUID;

public record TransmissionContext(
        UUID senderId,
        String senderName,
        MessageSurface surface,
        String destinationId,
        String message
) {
    public TransmissionContext {
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(senderName, "senderName");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(destinationId, "destinationId");
        Objects.requireNonNull(message, "message");
    }
}
