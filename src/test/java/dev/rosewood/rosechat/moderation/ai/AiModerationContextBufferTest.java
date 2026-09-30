package dev.rosewood.rosechat.moderation.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiModerationContextBufferTest {
    @Test
    void targetStartsAtEndAndMovesIntoMiddleWhenRepliesArrive() {
        AiModerationContextBuffer buffer = new AiModerationContextBuffer(
                Clock.fixed(Instant.parse("2026-09-28T20:00:00Z"), ZoneOffset.UTC),
                AiModerationTestConfig.create()
        );
        String channel = "global";
        buffer.record(channel, UUID.randomUUID(), UUID.randomUUID(), "Alice", "where are you");
        buffer.record(channel, UUID.randomUUID(), UUID.randomUUID(), "Bob", "at spawn");
        UUID targetId = UUID.randomUUID();

        AiModerationContextBuffer.Snapshot initial = buffer.record(
                channel, targetId, UUID.randomUUID(), "Alice", "fight me"
        );

        assertEquals(AiModerationContextBuffer.Position.END, initial.targetPosition());
        assertFalse(initial.hasAfterContext());
        assertTrue(initial.transcript().contains("TARGET_EVENT_ID=" + targetId));
        assertTrue(initial.transcript().contains("TARGET_POSITION=END"));
        assertTrue(initial.transcript().contains("<<< TARGET START >>>"));
        assertTrue(initial.transcript().contains("<<< TARGET END >>>"));

        buffer.record(channel, UUID.randomUUID(), UUID.randomUUID(), "Bob", "bet");
        buffer.record(channel, UUID.randomUUID(), UUID.randomUUID(), "Alice", "come to spawn");
        AiModerationContextBuffer.Snapshot later = buffer.snapshot(channel, targetId).orElseThrow();

        assertEquals(AiModerationContextBuffer.Position.MIDDLE, later.targetPosition());
        assertTrue(later.hasAfterContext());
        assertTrue(later.transcript().contains("TARGET_POSITION=MIDDLE"));
        assertTrue(later.transcript().indexOf("fight me") < later.transcript().indexOf("come to spawn"));
    }
}
