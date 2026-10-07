package dev.rosewood.rosechat.api.chatbridge;

/** Pure policy helpers for legacy Discord chat during provider-neutral authority transitions. */
public final class LegacyDiscordChatPolicy {

    private LegacyDiscordChatPolicy() {
    }

    /**
     * DiscordSRV post-process must be cancelled whenever RoseChat owns normal Discord handling or
     * an external authoritative transport suppresses the legacy path.
     *
     * @return whether RoseChat should cancel DiscordSRV's default post-process bridge
     */
    public static boolean cancelPostProcess(boolean useDiscord, boolean suppressed) {
        return useDiscord || suppressed;
    }

    /**
     * @return whether the legacy DiscordSRV inbound path may process a Discord message
     */
    public static boolean inboundAllowed(boolean useDiscord, boolean suppressed) {
        return useDiscord && !suppressed;
    }
}
