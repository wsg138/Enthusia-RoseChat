package dev.rosewood.rosechat.api.staff;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AutomatedModerationEvidence(
        UUID moderationEventId,
        Instant occurredAt,
        String message,
        String category,
        double confidence,
        int severity
) {
    public AutomatedModerationEvidence {
        Objects.requireNonNull(moderationEventId, "moderationEventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        message = validatedText(message, "message", 1_024, false);
        category = validatedText(category, "category", 96, true);
        validateConfidence(confidence);
        validateSeverity(severity);
    }

    private static String validatedText(String value, String field, int maximum, boolean trim) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        String normalized = trim ? value.trim() : value;
        if (normalized.isBlank() || normalized.length() > maximum || containsControlCharacter(normalized)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static void validateConfidence(double confidence) {
        if (!Double.isFinite(confidence)) {
            throw new IllegalArgumentException("confidence must be finite");
        }
        if (confidence < 0.0D || confidence > 1.0D) {
            throw new IllegalArgumentException("confidence must be in [0, 1]");
        }
    }

    private static void validateSeverity(int severity) {
        if (severity < 0 || severity > 100) {
            throw new IllegalArgumentException("severity must be in [0, 100]");
        }
    }
}
