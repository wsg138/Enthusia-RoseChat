package dev.rosewood.rosechat.api.chatbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboundDiscordChatMessageTest {
    private static final long CREATED = 1_800_000_000_000L;
    private static final UUID LINKED_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000123");

    @Test
    void acceptsBoundedMessageAndDefensivelyCopiesAttachments() {
        List<String> attachments = new ArrayList<>();
        attachments.add("https://cdn.discordapp.com/attachments/1/2/image.png");

        InboundDiscordChatMessage message = new InboundDiscordChatMessage(
                "123456789012345678",
                CREATED,
                CREATED + 30_000L,
                "global",
                sender(),
                "hello",
                attachments
        );
        attachments.clear();

        assertEquals("global", message.logicalChannelId());
        assertEquals(1, message.attachmentUrls().size());
        assertFalse(message.isExpired(CREATED + 30_000L));
        assertTrue(message.isExpired(CREATED + 30_001L));
        assertFalse(message.isFutureDated(CREATED));
        assertTrue(message.isFutureDated(CREATED - InboundDiscordChatMessage.MAX_FUTURE_SKEW_MILLIS - 1L));
        assertThrows(UnsupportedOperationException.class,
                () -> message.attachmentUrls().add("https://example.com/extra"));
    }

    @Test
    void allowsAttachmentOnlyMessageButRejectsEmptyContent() {
        InboundDiscordChatMessage attachmentOnly = new InboundDiscordChatMessage(
                "discord-1",
                CREATED,
                CREATED + 30_000L,
                "global",
                sender(),
                "",
                List.of("https://cdn.discordapp.com/attachments/1/2/file.txt")
        );

        assertTrue(attachmentOnly.plainText().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new InboundDiscordChatMessage(
                "discord-2",
                CREATED,
                CREATED + 30_000L,
                "global",
                sender(),
                "   ",
                List.of()
        ));
    }

    @Test
    void rejectsUnsafeAttachmentAndUnboundedLifetime() {
        assertThrows(IllegalArgumentException.class, () -> message(
                List.of("http://example.com/file.png"), 30_000L));
        assertThrows(IllegalArgumentException.class, () -> message(
                List.of("https://user:pass@example.com/file.png"), 30_000L));
        assertThrows(IllegalArgumentException.class, () -> message(
                List.of(), InboundDiscordChatMessage.MAX_LIFETIME_MILLIS + 1L));
    }

    @Test
    void rejectsInvalidRouteAndOversizedPayloads() {
        assertThrows(IllegalArgumentException.class, () -> new InboundDiscordChatMessage(
                "discord-3",
                CREATED,
                CREATED + 30_000L,
                "staff channel",
                sender(),
                "hello",
                List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new InboundDiscordChatMessage(
                "discord-4",
                CREATED,
                CREATED + 30_000L,
                "global",
                sender(),
                "x".repeat(InboundDiscordChatMessage.MAX_PLAIN_TEXT_LENGTH + 1),
                List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new InboundDiscordChatMessage(
                "discord-5",
                CREATED,
                CREATED + 30_000L,
                "global",
                sender(),
                "hello",
                java.util.Collections.nCopies(
                        InboundDiscordChatMessage.MAX_ATTACHMENT_COUNT + 1,
                        "https://example.com/file.png")
        ));
    }

    @Test
    void senderKeepsAuthorityIdentitySeparateFromPresentation() {
        InboundChatSender sender = new InboundChatSender(
                "987654321",
                LINKED_PLAYER,
                "Display Name",
                "discord_user",
                "Moderator",
                "a1b2c3",
                "0001"
        );

        assertEquals(LINKED_PLAYER, sender.linkedMinecraftId());
        assertEquals("#A1B2C3", sender.colorHex());
        assertEquals("Moderator", sender.roleName());
        assertThrows(IllegalArgumentException.class, () -> new InboundChatSender(
                "987654321",
                LINKED_PLAYER,
                "bad\nname",
                "discord_user",
                "",
                "#FFFFFF",
                ""
        ));
    }

    @Test
    void onlyTransientLocalFailuresRemainRetryable() {
        assertTrue(InboundChatResult.ACCEPTED.acknowledged());
        assertTrue(InboundChatResult.DUPLICATE.acknowledged());
        assertTrue(InboundChatResult.BLOCKED.acknowledged());
        assertTrue(InboundChatResult.CHANNEL_NOT_PUBLIC.acknowledged());
        assertTrue(InboundChatResult.EXPIRED.acknowledged());
        assertTrue(InboundChatResult.INVALID_TIME.acknowledged());
        assertTrue(InboundChatResult.FAILED.acknowledged());

        assertFalse(InboundChatResult.POLICY_UNAVAILABLE.acknowledged());
        assertFalse(InboundChatResult.SATURATED.acknowledged());
    }

    private static InboundDiscordChatMessage message(List<String> attachments, long lifetime) {
        return new InboundDiscordChatMessage(
                "discord-message",
                CREATED,
                CREATED + lifetime,
                "global",
                sender(),
                "hello",
                attachments
        );
    }

    private static InboundChatSender sender() {
        return new InboundChatSender(
                "987654321",
                LINKED_PLAYER,
                "Display Name",
                "discord_user",
                "",
                "#FFFFFF",
                ""
        );
    }
}
