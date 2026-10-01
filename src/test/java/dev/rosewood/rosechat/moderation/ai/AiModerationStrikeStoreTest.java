package dev.rosewood.rosechat.moderation.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiModerationStrikeStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void strikeWindowSurvivesRestartAndProjectsLatestTwoForStaffMuteRetries() {
        UUID playerId = UUID.randomUUID();
        Path file = tempDir.resolve("strikes.tsv");
        Instant start = Instant.parse("2026-09-28T20:00:00Z");
        Clock firstClock = Clock.fixed(start, ZoneOffset.UTC);

        AiModerationStrikeStore first = new AiModerationStrikeStore(
                file,
                firstClock,
                Duration.ofHours(1),
                Logger.getAnonymousLogger()
        );
        UUID firstEvent = UUID.randomUUID();
        AiModerationStrikeStore.RecordResult firstResult = first.record(
                playerId, firstEvent, "exact first message", "harassment", 0.94D, 100
        );
        assertEquals(1, firstResult.count());
        assertEquals("exact first message", firstResult.evidence().getFirst().message());

        AiModerationStrikeStore restarted = new AiModerationStrikeStore(
                file,
                firstClock,
                Duration.ofHours(1),
                Logger.getAnonymousLogger()
        );
        assertEquals(1, restarted.count(playerId));
        assertEquals(firstEvent, restarted.evidence(playerId).getFirst().eventId());
        assertEquals("exact first message", restarted.evidence(playerId).getFirst().message());

        AiModerationStrikeStore.RecordResult secondResult = restarted.record(
                playerId, UUID.randomUUID(), "exact second message", "hate", 0.90D, 100
        );
        assertEquals(2, secondResult.count());
        assertEquals("exact second message", secondResult.evidence().get(1).message());

        AiModerationStrikeStore.RecordResult retryResult = restarted.record(
                playerId, UUID.randomUUID(), "exact third message", "harassment", 0.91D, 95
        );
        assertEquals(2, retryResult.count());
        assertEquals(2, retryResult.evidence().size());
        assertEquals("exact second message", retryResult.evidence().getFirst().message());
        assertEquals("exact third message", retryResult.evidence().getLast().message());
        assertEquals(3, restarted.count(playerId));
        assertEquals(3, restarted.evidence(playerId).size());

        AiModerationStrikeStore expired = new AiModerationStrikeStore(
                file,
                Clock.fixed(start.plus(Duration.ofMinutes(61)), ZoneOffset.UTC),
                Duration.ofHours(1),
                Logger.getAnonymousLogger()
        );
        assertEquals(0, expired.count(playerId));
    }

    @Test
    void legacyTimestampOnlyRowsCannotTriggerEvidenceBackedMute() throws Exception {
        UUID playerId = UUID.randomUUID();
        Path file = tempDir.resolve("legacy.tsv");
        Instant start = Instant.parse("2026-09-28T20:00:00Z");
        java.nio.file.Files.writeString(file, playerId + "\t" + start.toEpochMilli() + "\n");

        AiModerationStrikeStore store = new AiModerationStrikeStore(
                file,
                Clock.fixed(start, ZoneOffset.UTC),
                Duration.ofHours(1),
                Logger.getAnonymousLogger()
        );
        assertEquals(0, store.count(playerId));
    }
}
