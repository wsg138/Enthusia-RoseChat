package dev.rosewood.rosechat.moderation.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiModerationAuditStoreTest {
    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path tempDir;

    @Test
    void longRunningStorePrunesExpiredAuditFilesWhenUtcDayChanges() throws Exception {
        Instant start = Instant.parse("2026-09-01T12:00:00Z");
        MutableClock clock = new MutableClock(start);
        AiModerationAuditStore store = new AiModerationAuditStore(
                tempDir,
                clock,
                Logger.getAnonymousLogger()
        );

        store.record(entry(start));
        Path expired = tempDir.resolve("ai-moderation-audit-2026-09-01.tsv");
        Path retainedBoundary = tempDir.resolve("ai-moderation-audit-2026-09-02.tsv");
        Files.writeString(retainedBoundary, "boundary\n");
        assertTrue(Files.exists(expired));

        clock.advance(Duration.ofDays(30));
        store.record(entry(clock.instant()));

        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(retainedBoundary));
        assertTrue(Files.exists(tempDir.resolve("ai-moderation-audit-2026-10-01.tsv")));
    }

    private static AiModerationAuditStore.Entry entry(Instant at) {
        return new AiModerationAuditStore.Entry(
                at,
                UUID.randomUUID(),
                PLAYER_ID,
                "Player",
                "global",
                "exact message",
                "ALLOW",
                "none",
                0.0D,
                0,
                25L
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant) {
            this(instant, ZoneOffset.UTC);
        }

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
