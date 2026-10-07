package dev.rosewood.rosechat.api.chatbridge;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Provider-neutral Discord sender identity and presentation data.
 *
 * <p>The linked Minecraft UUID is the only field that may participate in Minecraft moderation.
 * Discord names, roles and colors are presentation-only and must never grant authority.</p>
 */
public record InboundChatSender(
        String discordUserId,
        UUID linkedMinecraftId,
        String displayName,
        String userName,
        String roleName,
        String colorHex,
        String userTag
) {
    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_NAME_LENGTH = 128;
    private static final Pattern COLOR = Pattern.compile("#?[0-9A-Fa-f]{6}");

    public InboundChatSender {
        discordUserId = requiredText(discordUserId, "discordUserId", MAX_ID_LENGTH);
        displayName = requiredText(displayName, "displayName", MAX_NAME_LENGTH);
        userName = optionalText(userName, "userName", MAX_NAME_LENGTH);
        roleName = optionalText(roleName, "roleName", MAX_NAME_LENGTH);
        userTag = optionalText(userTag, "userTag", MAX_NAME_LENGTH);
        colorHex = normalizeColor(colorHex);
    }

    private static String requiredText(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        validatePresentation(normalized, field, maximumLength);
        return normalized;
    }

    private static String optionalText(String value, String field, int maximumLength) {
        String normalized = value == null ? "" : value.trim();
        validatePresentation(normalized, field, maximumLength);
        return normalized;
    }

    private static void validatePresentation(String value, String field, int maximumLength) {
        if (value.length() > maximumLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " contains unsupported characters or is too long");
        }
    }

    private static String normalizeColor(String value) {
        String normalized = value == null || value.isBlank() ? "#FFFFFF" : value.trim();
        if (!COLOR.matcher(normalized).matches()) {
            throw new IllegalArgumentException("colorHex must be a six-digit RGB color");
        }
        return normalized.charAt(0) == '#' ? normalized.toUpperCase() : "#" + normalized.toUpperCase();
    }
}
