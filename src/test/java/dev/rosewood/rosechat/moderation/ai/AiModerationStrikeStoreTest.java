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
    void strikeWindowSurvivesRestartAndExpiresAfterAnHour() {
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
        assertEquals(1, first.record(playerId));

        AiModerationStrikeStore restarted = new AiModerationStrikeStore(
                file,
                firstClock,
                Duration.ofHours(1),
                Logger.getAnonymousLogger()
        );
        assertEquals(1, restarted.count(playerId));
        assertEquals(2, restarted.record(playerId));

        AiModerationStrikeStore expired = new AiModerationStrikeStore(
                file,
                Clock.fixed(start.plus(Duration.ofMinutes(61)), ZoneOffset.UTC),
                Duration.ofHours(1),
                Logger.getAnonymousLogger()
        );
        assertEquals(0, expired.count(playerId));
    }
}
