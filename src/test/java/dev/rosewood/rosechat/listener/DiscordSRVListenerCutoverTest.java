package dev.rosewood.rosechat.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rosewood.rosechat.api.chatbridge.LegacyDiscordChatPolicy;
import org.junit.jupiter.api.Test;

class DiscordSRVListenerCutoverTest {

    @Test
    void legacyInboundRequiresDiscordEnabledAndNoAuthoritativeSuppression() {
        assertTrue(LegacyDiscordChatPolicy.inboundAllowed(true, false));

        assertFalse(LegacyDiscordChatPolicy.inboundAllowed(false, false));
        assertFalse(LegacyDiscordChatPolicy.inboundAllowed(true, true));
        assertFalse(LegacyDiscordChatPolicy.inboundAllowed(false, true));
    }
}
