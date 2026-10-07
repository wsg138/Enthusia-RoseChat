package dev.rosewood.rosechat.api.chatbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboundChatMessageTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    void preservesStableProviderNeutralIdentity() {
        UUID eventId = UUID.randomUUID();
        InboundChatMessage message = new InboundChatMessage(
                eventId,
                "discord-123456789",
                "chat-" + eventId,
                NOW,
                NOW + 30_000,
                "global",
                "DiscordUser",
                "hello from Discord"
        );

        assertEquals(eventId, message.eventId());
        assertEquals("discord-123456789", message.externalMessageId());
        assertEquals("chat-" + eventId, message.canonicalMessageId());
        assertEquals("global", message.logicalChannelId());
        assertEquals("DiscordUser", message.displayName());
        assertEquals("hello from Discord", message.plainText());
        assertFalse(message.isExpired(NOW + 30_000));
        assertTrue(message.isExpired(NOW + 30_001));
    }

    @Test
    void rejectsInvalidIdentityTextAndLifetime() {
        UUID eventId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new InboundChatMessage(
                null, "discord-1", "canonical-1", NOW, NOW + 1_000,
                "global", "User", "hello"));
        assertThrows(IllegalArgumentException.class, () -> new InboundChatMessage(
                eventId, "discord-1", "canonical-1", NOW, NOW + 1_000,
                "global", "User", "x".repeat(InboundChatMessage.MAX_PLAIN_TEXT_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class, () -> new InboundChatMessage(
                eventId, "discord-1", "canonical-1", NOW, NOW + 1_000,
                "global", "User", "bad\ntext"));
        assertThrows(IllegalArgumentException.class, () -> new InboundChatMessage(
                eventId, "discord-1", "canonical-1", NOW, NOW + InboundChatMessage.MAX_LIFETIME_MILLIS + 1,
                "global", "User", "hello"));
    }
}
