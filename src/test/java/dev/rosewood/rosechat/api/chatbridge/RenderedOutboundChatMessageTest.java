package dev.rosewood.rosechat.api.chatbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rosewood.rosechat.api.staff.ChannelClassification;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RenderedOutboundChatMessageTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    void preservesBodyAndFullLineRepresentations() {
        OutboundChatMessage base = base(UUID.randomUUID(), OutboundChatMessage.Origin.MINECRAFT);
        RenderedOutboundChatMessage rendered = new RenderedOutboundChatMessage(
                base,
                "hello",
                "**hello**",
                "{\"text\":\"hello\",\"color\":\"#12ABEF\"}",
                "Player: hello",
                "**Player:** **hello**",
                "{\"text\":\"Player: hello\",\"color\":\"#12ABEF\"}"
        );

        assertEquals("hello", rendered.bodyPlainText());
        assertEquals("**hello**", rendered.bodyMarkdown());
        assertTrue(rendered.bodyAdventureJson().contains("#12ABEF"));
        assertEquals("Player: hello", rendered.linePlainText());
        assertEquals(base.eventId(), rendered.message().eventId());
    }

    @Test
    void rejectsOversizeAndUnsafeRepresentations() {
        OutboundChatMessage base = base(UUID.randomUUID(), OutboundChatMessage.Origin.MINECRAFT);

        assertThrows(IllegalArgumentException.class, () -> new RenderedOutboundChatMessage(
                base,
                "hello",
                "x".repeat(RenderedOutboundChatMessage.MAX_MARKDOWN_LENGTH + 1),
                "{}",
                "Player: hello",
                "Player: hello",
                "{}"
        ));
        assertThrows(IllegalArgumentException.class, () -> new RenderedOutboundChatMessage(
                base,
                "hello",
                "hello",
                "\"\"" + "x".repeat(RenderedOutboundChatMessage.MAX_ADVENTURE_JSON_BYTES),
                "Player: hello",
                "Player: hello",
                "{}"
        ));
        assertThrows(IllegalArgumentException.class, () -> new RenderedOutboundChatMessage(
                base,
                "bad\u0000text",
                "hello",
                "{}",
                "Player: hello",
                "Player: hello",
                "{}"
        ));
    }

    @Test
    void jsonBoundIsMeasuredAsUtf8Bytes() {
        OutboundChatMessage base = base(UUID.randomUUID(), OutboundChatMessage.Origin.MINECRAFT);
        String multibyte = "\"" + "😀".repeat(RenderedOutboundChatMessage.MAX_ADVENTURE_JSON_BYTES / 2) + "\"";
        assertTrue(multibyte.getBytes(StandardCharsets.UTF_8).length
                > RenderedOutboundChatMessage.MAX_ADVENTURE_JSON_BYTES);
        assertThrows(IllegalArgumentException.class, () -> new RenderedOutboundChatMessage(
                base,
                "hello",
                "hello",
                multibyte,
                "Player: hello",
                "Player: hello",
                "{}"
        ));
    }

    private static OutboundChatMessage base(UUID eventId, OutboundChatMessage.Origin origin) {
        return new OutboundChatMessage(
                eventId,
                "rosechat-mc-" + eventId,
                "rosechat-canonical-" + eventId,
                NOW,
                NOW + 30_000,
                "global",
                ChannelClassification.PUBLIC,
                origin,
                UUID.randomUUID(),
                "Player",
                "hello"
        );
    }
}
