package dev.rosewood.rosechat.moderation.ai.central;

import java.util.Objects;

/**
 * A structured retroactive-deletion reference returned by the central service
 * in {@code related_messages}.
 *
 * <p>RoseChat acts only on references whose platform is {@code minecraft} and
 * whose {@code external_message_id} resolves to a message this RoseChat
 * instance actually published. Discord (or any foreign-platform) references are
 * ignored for local deletion; the Discord listener (W14) owns that surface.</p>
 */
public record RelatedMessageRef(
        String platform,
        String scopeId,
        String channelId,
        String externalMessageId
) {
    public RelatedMessageRef {
        platform = platform == null ? "" : platform;
        scopeId = scopeId == null ? "" : scopeId;
        channelId = channelId == null ? "" : channelId;
        externalMessageId = externalMessageId == null ? "" : externalMessageId;
    }

    /**
     * @return {@code true} when this reference names the Minecraft surface RoseChat owns.
     */
    public boolean isMinecraftSurface() {
        return "minecraft".equalsIgnoreCase(platform);
    }

    /**
     * @return {@code true} when the reference carries enough identity to resolve exactly.
     */
    public boolean isResolvable() {
        return !externalMessageId.isBlank();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RelatedMessageRef that)) {
            return false;
        }
        return Objects.equals(platform, that.platform)
                && Objects.equals(scopeId, that.scopeId)
                && Objects.equals(channelId, that.channelId)
                && Objects.equals(externalMessageId, that.externalMessageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(platform, scopeId, channelId, externalMessageId);
    }
}
