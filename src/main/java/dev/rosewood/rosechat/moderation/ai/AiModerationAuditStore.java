package dev.rosewood.rosechat.moderation.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

final class AiModerationAuditStore {
    private static final String PREFIX = "ai-moderation-audit-";
    private static final String SUFFIX = ".tsv";
    private static final int RETENTION_DAYS = 30;

    private final Path directory;
    private final Clock clock;
    private final Logger logger;
    private LocalDate lastCleanupDay = LocalDate.MIN;

    AiModerationAuditStore(Path directory, Clock clock, Logger logger) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        cleanupIfNeeded();
    }

    synchronized void record(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        cleanupIfNeeded();
        try {
            Files.createDirectories(directory);
            LocalDate day = LocalDate.ofInstant(entry.at(), ZoneOffset.UTC);
            Path file = directory.resolve(PREFIX + day + SUFFIX);
            boolean newFile = !Files.exists(file);
            StringBuilder output = new StringBuilder();
            if (newFile) {
                output.append("# at\tevent_id\tplayer_id\tplayer_name_b64\tchannel_b64\toutcome\tcategory_b64\tconfidence\tseverity\tlatency_ms\tmessage_b64\n");
            }
            output.append(entry.at().toEpochMilli()).append('\t')
                    .append(entry.eventId()).append('\t')
                    .append(entry.playerId()).append('\t')
                    .append(encoded(entry.playerName())).append('\t')
                    .append(encoded(entry.channel())).append('\t')
                    .append(entry.outcome()).append('\t')
                    .append(encoded(entry.category())).append('\t')
                    .append(entry.confidence()).append('\t')
                    .append(entry.severity()).append('\t')
                    .append(entry.latencyMs()).append('\t')
                    .append(encoded(entry.message())).append('\n');
            Files.writeString(file, output.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            logger.warning("Could not persist AI moderation audit entry: " + exception.getClass().getSimpleName());
        }
    }

    private void cleanupIfNeeded() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(lastCleanupDay) && cleanupOldFiles(today)) {
            lastCleanupDay = today;
        }
    }

    private boolean cleanupOldFiles(LocalDate today) {
        if (!Files.isDirectory(directory)) {
            return true;
        }
        LocalDate earliestRetained = today.minusDays(RETENTION_DAYS - 1L);
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(path -> deleteIfExpired(path, earliestRetained));
            return true;
        } catch (IOException exception) {
            logger.warning("Could not prune old AI moderation audit files: " + exception.getClass().getSimpleName());
            return false;
        }
    }

    private void deleteIfExpired(Path path, LocalDate earliestRetained) {
        String name = path.getFileName().toString();
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) {
            return;
        }
        String datePart = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        try {
            if (LocalDate.parse(datePart).isBefore(earliestRetained)) {
                Files.deleteIfExists(path);
            }
        } catch (RuntimeException | IOException ignored) {
            // Ignore one malformed or undeletable audit file.
        }
    }

    private static String encoded(String value) {
        String safe = value == null ? "" : value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    record Entry(
            Instant at,
            UUID eventId,
            UUID playerId,
            String playerName,
            String channel,
            String message,
            String outcome,
            String category,
            double confidence,
            int severity,
            long latencyMs
    ) {
        Entry {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(outcome, "outcome");
            playerName = playerName == null ? "" : playerName;
            channel = channel == null ? "" : channel;
            message = message == null ? "" : message;
            category = category == null ? "none" : category;
        }
    }
}
