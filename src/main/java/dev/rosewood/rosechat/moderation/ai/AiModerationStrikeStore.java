package dev.rosewood.rosechat.moderation.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * RETIRED as a production authority (W13 central migration).
 *
 * <p>The local strike ledger no longer records AI enforcement or escalates to
 * punishment requests: the central Policy-v1 service is the single semantic
 * authority, and EnthusiaStaff owns all punishment/case authority. This class
 * is retained, with its tests, as a migration artifact only.</p>
 */
@Deprecated
final class AiModerationStrikeStore {
    private final Path file;
    private final Clock clock;
    private final Duration window;
    private final Logger logger;
    private final Map<UUID, Deque<StrikeEvidence>> strikes = new HashMap<>();

    AiModerationStrikeStore(Path file, Clock clock, Duration window, Logger logger) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.window = Objects.requireNonNull(window, "window");
        this.logger = Objects.requireNonNull(logger, "logger");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("strike window must be positive");
        }
        load();
    }

    synchronized RecordResult record(
            UUID playerId,
            UUID eventId,
            String message,
            String category,
            double confidence,
            int severity
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(category, "category");
        Instant now = clock.instant();
        pruneAll(now);
        Deque<StrikeEvidence> playerStrikes = strikes.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        playerStrikes.addLast(new StrikeEvidence(now, eventId, message, category, confidence, severity));
        save();
        List<StrikeEvidence> enforcementEvidence = latestEnforcementEvidence(playerStrikes);
        return new RecordResult(enforcementEvidence.size(), enforcementEvidence);
    }

    synchronized int count(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Instant now = clock.instant();
        pruneAll(now);
        Deque<StrikeEvidence> playerStrikes = strikes.get(playerId);
        return playerStrikes == null ? 0 : playerStrikes.size();
    }

    synchronized List<StrikeEvidence> evidence(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Instant now = clock.instant();
        pruneAll(now);
        Deque<StrikeEvidence> playerStrikes = strikes.get(playerId);
        return playerStrikes == null ? List.of() : List.copyOf(playerStrikes);
    }

    private static List<StrikeEvidence> latestEnforcementEvidence(Deque<StrikeEvidence> playerStrikes) {
        List<StrikeEvidence> allEvidence = List.copyOf(playerStrikes);
        int first = Math.max(0, allEvidence.size() - AiModerationConfig.REQUIRED_AUTOMATIC_MUTE_STRIKES);
        return List.copyOf(allEvidence.subList(first, allEvidence.size()));
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Instant now = clock.instant();
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\\t", -1);
                // Intentionally ignore the old timestamp-only ledger format. Automatic mutes must
                // never be triggered by strikes that do not have reviewable message evidence.
                if (fields.length != 7) {
                    continue;
                }
                try {
                    UUID playerId = UUID.fromString(fields[0]);
                    Instant at = Instant.ofEpochMilli(Long.parseLong(fields[1]));
                    UUID eventId = UUID.fromString(fields[2]);
                    double confidence = Double.parseDouble(fields[3]);
                    int severity = Integer.parseInt(fields[4]);
                    String category = decoded(fields[5]);
                    String message = decoded(fields[6]);
                    if (at.isAfter(now) || at.isBefore(now.minus(window)) || message.isBlank()) {
                        continue;
                    }
                    strikes.computeIfAbsent(playerId, ignored -> new ArrayDeque<>())
                            .addLast(new StrikeEvidence(at, eventId, message, category, confidence, severity));
                } catch (RuntimeException ignored) {
                    // Ignore one corrupt row instead of discarding the rest of the ledger.
                }
            }
        } catch (IOException exception) {
            logger.warning("Could not load AI moderation strike ledger; starting with an empty in-memory window: "
                    + exception.getClass().getSimpleName());
        }
    }

    private void pruneAll(Instant now) {
        Instant cutoff = now.minus(window);
        List<UUID> empty = new ArrayList<>();
        for (Map.Entry<UUID, Deque<StrikeEvidence>> entry : strikes.entrySet()) {
            Deque<StrikeEvidence> playerStrikes = entry.getValue();
            while (!playerStrikes.isEmpty() && playerStrikes.peekFirst().at().isBefore(cutoff)) {
                playerStrikes.removeFirst();
            }
            if (playerStrikes.isEmpty()) {
                empty.add(entry.getKey());
            }
        }
        empty.forEach(strikes::remove);
    }

    private void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<Map.Entry<UUID, Deque<StrikeEvidence>>> entries = new ArrayList<>(strikes.entrySet());
            entries.sort(Comparator.comparing(entry -> entry.getKey().toString()));
            StringBuilder contents = new StringBuilder(
                    "# player_uuid\\tepoch_ms\\tevent_uuid\\tconfidence\\tseverity\\tcategory_b64\\tmessage_b64\n"
            );
            for (Map.Entry<UUID, Deque<StrikeEvidence>> entry : entries) {
                for (StrikeEvidence evidence : entry.getValue()) {
                    contents.append(entry.getKey()).append('\t')
                            .append(evidence.at().toEpochMilli()).append('\t')
                            .append(evidence.eventId()).append('\t')
                            .append(evidence.confidence()).append('\t')
                            .append(evidence.severity()).append('\t')
                            .append(encoded(evidence.category())).append('\t')
                            .append(encoded(evidence.message())).append('\n');
                }
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            logger.warning("Could not persist AI moderation strike ledger; the current in-memory window remains active: "
                    + exception.getClass().getSimpleName());
        }
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decoded(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    record StrikeEvidence(
            Instant at,
            UUID eventId,
            String message,
            String category,
            double confidence,
            int severity
    ) {
        StrikeEvidence {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(category, "category");
            if (!Double.isFinite(confidence) || confidence < 0.0D || confidence > 1.0D) {
                throw new IllegalArgumentException("confidence must be in [0, 1]");
            }
            if (severity < 0 || severity > 100) {
                throw new IllegalArgumentException("severity must be in [0, 100]");
            }
        }
    }

    record RecordResult(int count, List<StrikeEvidence> evidence) {
        RecordResult {
            evidence = List.copyOf(evidence);
        }
    }
}
