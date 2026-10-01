package dev.rosewood.rosechat.api.staff;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AutomatedPublicMuteRequest(
        UUID targetId,
        String targetName,
        UUID moderationEventId,
        String category,
        int severity,
        int strikeCount,
        Duration muteDuration,
        String idempotencyKey,
        List<AutomatedModerationEvidence> evidence
) {
    private static final int MIN_STRIKE_COUNT = 1;

    public AutomatedPublicMuteRequest {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(moderationEventId, "moderationEventId");
        Objects.requireNonNull(muteDuration, "muteDuration");
        Objects.requireNonNull(evidence, "evidence");
        targetName = bounded(targetName, "targetName", 64);
        category = bounded(category, "category", 96);
        idempotencyKey = bounded(idempotencyKey, "idempotencyKey", 128);
        evidence = List.copyOf(evidence);
        validateSeverityAndStrikeCount(severity, strikeCount);
        validateDuration(muteDuration);
        validateEvidenceCount(evidence, strikeCount);
    }

    private static void validateSeverityAndStrikeCount(int severity, int strikeCount) {
        if (severity < 0 || severity > 100) {
            throw new IllegalArgumentException("invalid automated moderation severity");
        }
        if (strikeCount < MIN_STRIKE_COUNT) {
            throw new IllegalArgumentException("invalid automated moderation strike count");
        }
    }

    private static void validateDuration(Duration muteDuration) {
        if (muteDuration.isZero() || muteDuration.isNegative()) {
            throw new IllegalArgumentException("muteDuration must be positive");
        }
    }

    private static void validateEvidenceCount(List<AutomatedModerationEvidence> evidence, int strikeCount) {
        if (evidence.size() != strikeCount) {
            throw new IllegalArgumentException("evidence count must equal the enforcement strike count");
        }
    }

    private static String bounded(String value, String field, int maximum) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum || containsControlCharacter(normalized)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
