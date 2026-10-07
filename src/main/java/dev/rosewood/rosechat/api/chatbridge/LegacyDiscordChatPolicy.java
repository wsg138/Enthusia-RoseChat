package dev.rosewood.rosechat.api.chatbridge;

/** Pure policy helpers for legacy Discord chat during provider-neutral authority transitions. */
public final class LegacyDiscordChatPolicy {

    private LegacyDiscordChatPolicy() {
    }

    /**
     * @return whether the legacy DiscordSRV inbound path may process a Discord message
     */
    public static boolean inboundAllowed(boolean useDiscord, boolean suppressed) {
        return useDiscord && !suppressed;
    }
}
