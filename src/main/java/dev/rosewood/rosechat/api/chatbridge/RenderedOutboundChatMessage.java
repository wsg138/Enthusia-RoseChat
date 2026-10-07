package dev.rosewood.rosechat.api.chatbridge;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Provider-neutral styled representation of one already-approved outbound chat event.
 *
 * <p>The render carries both message-body and full in-game-line forms. StaffBot can therefore use
 * the body for player-style webhook presentation or the full line for normal bot presentation
 * without reconstructing RoseChat formatting. Adventure JSON retains resolved RGB/hex/style
 * semantics; Markdown retains Discord-representable text decoration; plain text is the mandatory
 * formatting-free fallback.</p>
 *
 * @param message canonical outbound chat identity and policy metadata
 * @param bodyPlainText formatting-free player message body
 * @param bodyMarkdown player message body with Discord-representable text decorations
 * @param bodyAdventureJson player message body as resolved Adventure component JSON
 * @param linePlainText formatting-free full in-game chat line
 * @param lineMarkdown full in-game chat line with Discord-representable text decorations
 * @param lineAdventureJson full in-game chat line as resolved Adventure component JSON
 */
public record RenderedOutboundChatMessage(
        OutboundChatMessage message,
        String bodyPlainText,
        String bodyMarkdown,
        String bodyAdventureJson,
        String linePlainText,
        String lineMarkdown,
        String lineAdventureJson
) {

    public static final int MAX_PLAIN_LENGTH = 4_096;
    public static final int MAX_MARKDOWN_LENGTH = 8_192;
    public static final int MAX_ADVENTURE_JSON_BYTES = 65_536;

    public RenderedOutboundChatMessage {
        message = Objects.requireNonNull(message, "message");
        bodyPlainText = requireText(bodyPlainText, "bodyPlainText", MAX_PLAIN_LENGTH);
        bodyMarkdown = requireText(bodyMarkdown, "bodyMarkdown", MAX_MARKDOWN_LENGTH);
        bodyAdventureJson = requireJson(bodyAdventureJson, "bodyAdventureJson");
        linePlainText = requireText(linePlainText, "linePlainText", MAX_PLAIN_LENGTH);
        lineMarkdown = requireText(lineMarkdown, "lineMarkdown", MAX_MARKDOWN_LENGTH);
        lineAdventureJson = requireJson(lineAdventureJson, "lineAdventureJson");
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

    private static String requireJson(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_ADVENTURE_JSON_BYTES) {
            throw new IllegalArgumentException(field + " exceeds maximum size");
        }
        return value;
    }
}
