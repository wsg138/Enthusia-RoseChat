package dev.rosewood.rosechat.api.chatbridge;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Provider-neutral styled representation of one already-approved outbound chat event.
 *
 * <p>The base message always supplies the bounded plain-text fallback. Markdown preserves Discord-
 * representable text decorations, while Adventure JSON preserves resolved Minecraft styling such
 * as exact RGB/hex colors for downstream rendering decisions. Neither field contains JDA or
 * DiscordSRV types.</p>
 *
 * @param message canonical outbound chat identity and plain-text fallback
 * @param markdownText Discord-safe semantic text formatting where representable
 * @param adventureJson resolved Adventure component JSON including exact styling metadata
 */
public record RenderedOutboundChatMessage(
        OutboundChatMessage message,
        String markdownText,
        String adventureJson
) {

    public static final int MAX_MARKDOWN_LENGTH = 8_192;
    public static final int MAX_ADVENTURE_JSON_BYTES = 65_536;

    public RenderedOutboundChatMessage {
        message = Objects.requireNonNull(message, "message");
        markdownText = requireText(markdownText, "markdownText", MAX_MARKDOWN_LENGTH);
        adventureJson = requireJson(adventureJson);
    }

    public boolean isExpired(long nowEpochMillis) {
        return message.isExpired(nowEpochMillis);
    }

    private static String requireText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds " + maximumLength + " characters");
        }
        if (value.chars().anyMatch(character -> Character.isISOControl(character)
                && character != '\n' && character != '\t')) {
            throw new IllegalArgumentException(field + " contains unsupported control characters");
        }
        return value;
    }

    private static String requireJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("adventureJson must not be blank");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_ADVENTURE_JSON_BYTES) {
            throw new IllegalArgumentException("adventureJson exceeds maximum size");
        }
        return value;
    }
}
