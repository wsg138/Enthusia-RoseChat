package dev.rosewood.rosechat.api.chatbridge;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Bounded provider-neutral Discord message accepted by RoseChat.
 *
 * <p>Transport authentication and Discord channel-to-server routing happen before this API.
 * RoseChat still validates the logical channel, privacy classification, mute/filter policy,
 * expiry and duplicate identity before dispatching to Minecraft recipients.</p>
 */
public record InboundDiscordChatMessage(
        String externalMessageId,
        long createdAtEpochMillis,
        long expiresAtEpochMillis,
        String logicalChannelId,
        InboundChatSender sender,
        String plainText,
        List<String> attachmentUrls
) {
    public static final int MAX_PLAIN_TEXT_LENGTH = 2_000;
    public static final int MAX_ATTACHMENT_COUNT = 10;
    public static final int MAX_ATTACHMENT_URL_LENGTH = 2_048;
    public static final long MAX_LIFETIME_MILLIS = 60_000L;

    private static final Pattern MESSAGE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern CHANNEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public InboundDiscordChatMessage {
        externalMessageId = token(externalMessageId, "externalMessageId", MESSAGE_ID);
        logicalChannelId = token(logicalChannelId, "logicalChannelId", CHANNEL_ID);
        sender = Objects.requireNonNull(sender, "sender");
        plainText = Objects.requireNonNull(plainText, "plainText");
        if (plainText.length() > MAX_PLAIN_TEXT_LENGTH || plainText.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("plainText is too long or contains unsupported characters");
        }
        if (createdAtEpochMillis < 0L
                || expiresAtEpochMillis < createdAtEpochMillis
                || expiresAtEpochMillis - createdAtEpochMillis > MAX_LIFETIME_MILLIS) {
            throw new IllegalArgumentException("message timestamps are invalid or exceed the maximum lifetime");
        }
        attachmentUrls = validateAttachments(attachmentUrls);
        if (plainText.isBlank() && attachmentUrls.isEmpty()) {
            throw new IllegalArgumentException("message must contain text or an attachment");
        }
    }

    public boolean isExpired(long nowEpochMillis) {
        return nowEpochMillis > expiresAtEpochMillis;
    }

    private static String token(String value, String field, Pattern pattern) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static List<String> validateAttachments(List<String> values) {
        Objects.requireNonNull(values, "attachmentUrls");
        if (values.size() > MAX_ATTACHMENT_COUNT) {
            throw new IllegalArgumentException("too many attachment URLs");
        }
        List<String> validated = new ArrayList<>(values.size());
        for (String value : values) {
            Objects.requireNonNull(value, "attachmentUrl");
            String normalized = value.trim();
            if (normalized.length() > MAX_ATTACHMENT_URL_LENGTH) {
                throw new IllegalArgumentException("attachment URL is too long");
            }
            try {
                URI uri = new URI(normalized);
                if (!"https".equalsIgnoreCase(uri.getScheme())
                        || uri.getHost() == null
                        || uri.getHost().isBlank()
                        || uri.getUserInfo() != null) {
                    throw new IllegalArgumentException("attachment URLs must use HTTPS without user info");
                }
            } catch (URISyntaxException exception) {
                throw new IllegalArgumentException("attachment URL is invalid", exception);
            }
            validated.add(normalized);
        }
        return List.copyOf(validated);
    }
}
