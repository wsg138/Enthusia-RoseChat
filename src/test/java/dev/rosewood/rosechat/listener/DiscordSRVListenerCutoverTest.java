package dev.rosewood.rosechat.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiscordSRVListenerCutoverTest {

    @Test
    void legacyInboundRequiresDiscordEnabledAndNoAuthoritativeSuppression() {
        assertTrue(DiscordSRVListener.legacyDiscordInboundAllowed(true, false));

        assertFalse(DiscordSRVListener.legacyDiscordInboundAllowed(false, false));
        assertFalse(DiscordSRVListener.legacyDiscordInboundAllowed(true, true));
        assertFalse(DiscordSRVListener.legacyDiscordInboundAllowed(false, true));
    }
}
