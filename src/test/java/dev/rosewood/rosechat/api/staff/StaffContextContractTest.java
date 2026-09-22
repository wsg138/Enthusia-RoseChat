package dev.rosewood.rosechat.api.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StaffContextContractTest {

    @Test
    void broadcastContextPreservesRequiredAuditFields() {
        UUID messageId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        BroadcastContext context = new BroadcastContext(
                messageId,
                senderId,
                "Alice",
                "global",
                ChannelClassification.PUBLIC,
                "hello",
                MessageSource.MINECRAFT
        );

        assertEquals(messageId, context.messageId());
        assertEquals(senderId, context.senderId());
        assertEquals("Alice", context.senderName());
        assertEquals("global", context.channelId());
        assertEquals(ChannelClassification.PUBLIC, context.classification());
        assertEquals("hello", context.message());
        assertEquals(MessageSource.MINECRAFT, context.source());
    }

    @Test
    void broadcastContextRejectsMissingRequiredFields() {
        UUID id = UUID.randomUUID();
        assertThrows(NullPointerException.class,
                () -> new BroadcastContext(null, id, "Alice", "global", ChannelClassification.PUBLIC, "hi", MessageSource.MINECRAFT));
        assertThrows(NullPointerException.class,
                () -> new BroadcastContext(id, id, null, "global", ChannelClassification.PUBLIC, "hi", MessageSource.MINECRAFT));
        assertThrows(NullPointerException.class,
                () -> new BroadcastContext(id, id, "Alice", "global", null, "hi", MessageSource.MINECRAFT));
        assertThrows(NullPointerException.class,
                () -> new BroadcastContext(id, id, "Alice", "global", ChannelClassification.PUBLIC, null, MessageSource.MINECRAFT));
    }

    @Test
    void privateMessageNullRecipientIdNormalizesToEmptyOptional() {
        UUID messageId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();

        PrivateMessageContext context = new PrivateMessageContext(
                messageId,
                senderId,
                "Alice",
                null,
                "Bob",
                "hello"
        );

        assertTrue(context.recipientId().isEmpty());
        assertEquals("Bob", context.recipientName());
    }

    @Test
    void privateMessagePreservesKnownRecipientId() {
        UUID recipientId = UUID.randomUUID();
        PrivateMessageContext context = new PrivateMessageContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Alice",
                Optional.of(recipientId),
                "Bob",
                "hello"
        );

        assertEquals(Optional.of(recipientId), context.recipientId());
    }

    @Test
    void privateMessageRejectsMissingRequiredIdentityOrContent() {
        UUID id = UUID.randomUUID();
        assertThrows(NullPointerException.class,
                () -> new PrivateMessageContext(null, id, "Alice", Optional.empty(), "Bob", "hi"));
        assertThrows(NullPointerException.class,
                () -> new PrivateMessageContext(id, id, null, Optional.empty(), "Bob", "hi"));
        assertThrows(NullPointerException.class,
                () -> new PrivateMessageContext(id, id, "Alice", Optional.empty(), null, "hi"));
        assertThrows(NullPointerException.class,
                () -> new PrivateMessageContext(id, id, "Alice", Optional.empty(), "Bob", null));
    }
}
