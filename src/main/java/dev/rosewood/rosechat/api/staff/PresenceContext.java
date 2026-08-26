package dev.rosewood.rosechat.api.staff;

import java.util.Objects;
import java.util.UUID;

public record PresenceContext(UUID subjectId, UUID viewerId, PresenceType type) {
    public PresenceContext {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(viewerId, "viewerId");
        Objects.requireNonNull(type, "type");
    }
}
