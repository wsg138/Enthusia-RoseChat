package dev.rosewood.rosechat.api.chatbridge;

import dev.rosewood.rosechat.api.staff.ChannelClassification;
import java.util.UUID;

/**
 * A policy-approved Minecraft chat message offered to an external chat bridge.
 *
 * <p>This DTO deliberately contains no DiscordSRV or JDA types. Rich InteractiveChat
 * rendering is a later stage and must retain {@link #plainText()} as its fallback.</p>
 *
 * @param eventId stable id used for duplicate suppression across the bridge
 * @param createdAtEpochMillis creation time of this export
 * @param expiresAtEpochMillis hard expiry for best-effort delivery; expired chat must not be replayed
 * @param logicalChannelId RoseChat logical channel id used for explicit routing
 * @param classification privacy classification evaluated before public export
 * @param origin source of the message, used to suppress Discord-to-Minecraft echo
 * @param minecraftPlayerId Minecraft sender UUID when a player identity exists, otherwise {@code null}
 * @param displayName safe presentation name for the sender
 * @param plainText bounded canonical fallback text
 */
public record OutboundChatMessage(
        UUID eventId,
        long createdAtEpochMillis,
        long expiresAtEpochMillis,
        String logicalChannelId,
        ChannelClassification classification,
        Origin origin,
        UUID minecraftPlayerId,
        String displayName,
        String plainText
) {

    /** Source transport of the message. */
    public enum Origin {
        MINECRAFT,
        DISCORD
    }

    /** Validates the immutable bridge payload. */
    public OutboundChatMessage {
        if (eventId == null
                || logicalChannelId == null || logicalChannelId.isBlank()
                || classification == null
                || origin == null
                || displayName == null || displayName.isBlank()
                || plainText == null
                || createdAtEpochMillis < 0
                || expiresAtEpochMillis < createdAtEpochMillis) {
            throw new IllegalArgumentException("outbound chat message is invalid");
        }
    }

    /**
     * Checks whether this message is too old for best-effort bridge delivery.
     *
     * @param nowEpochMillis current epoch time in milliseconds
     * @return {@code true} when the delivery deadline has passed
     */
    public boolean isExpired(long nowEpochMillis) {
        return nowEpochMillis > this.expiresAtEpochMillis;
    }
}
