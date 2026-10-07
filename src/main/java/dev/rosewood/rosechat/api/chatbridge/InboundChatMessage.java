package dev.rosewood.rosechat.api.chatbridge;

import java.util.UUID;

/**
 * Provider-neutral Discord-origin chat accepted by RoseChat's canonical inbound path.
 *
 * <p>The transport owner is responsible for authenticating and explicitly routing the message
 * before constructing this DTO. RoseChat still applies its normal Staff preflight and marks the
 * message as Discord-origin so it cannot echo back through the outbound bridge.</p>
 *
 * @param eventId stable event UUID used for duplicate suppression across the transport
 * @param externalMessageId stable Discord-side message identifier
 * @param canonicalMessageId stable logical identifier shared with any mirrored representation
 * @param createdAtEpochMillis creation time of this inbound event
 * @param expiresAtEpochMillis hard expiry for best-effort delivery
 * @param logicalChannelId exact RoseChat channel id selected by the authenticated route
 * @param displayName safe Discord presentation name
 * @param plainText bounded canonical text to deliver to Minecraft
 */
public record InboundChatMessage(
        UUID eventId,
        String externalMessageId,
        String canonicalMessageId,
        long createdAtEpochMillis,
        long expiresAtEpochMillis,
        String logicalChannelId,
        String displayName,
        String plainText
) {

    public static final int MAX_PLAIN_TEXT_LENGTH = 2_000;
    public static final long MAX_LIFETIME_MILLIS = 60_000L;

    /**
     * Validates the immutable inbound message contract.
     */
    public InboundChatMessage {
        if (eventId == null) {
            throw new IllegalArgumentException("inbound chat event id is required");
        }
        externalMessageId = safeToken(externalMessageId, "externalMessageId", 128);
        canonicalMessageId = safeToken(canonicalMessageId, "canonicalMessageId", 128);
        logicalChannelId = safeToken(logicalChannelId, "logicalChannelId", 64);
        displayName = safeText(displayName, "displayName", 128, true);
        plainText = safeText(plainText, "plainText", MAX_PLAIN_TEXT_LENGTH, false);
        if (createdAtEpochMillis < 0
                || expiresAtEpochMillis <= createdAtEpochMillis
                || expiresAtEpochMillis - createdAtEpochMillis > MAX_LIFETIME_MILLIS) {
            throw new IllegalArgumentException("inbound chat message lifetime is invalid");
        }
    }

    /**
     * @param nowEpochMillis current wall-clock time
     * @return true once this best-effort event must no longer be delivered
     */
    public boolean isExpired(long nowEpochMillis) {
        return nowEpochMillis > this.expiresAtEpochMillis;
    }

    private static String safeToken(String value, String field, int maximumLength) {
        return safeText(value, field, maximumLength, true);
    }

    private static String safeText(String value, String field, int maximumLength, boolean trim) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = trim ? value.trim() : value;
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds " + maximumLength + " characters");
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " contains control characters");
        }
        return normalized;
    }
}
