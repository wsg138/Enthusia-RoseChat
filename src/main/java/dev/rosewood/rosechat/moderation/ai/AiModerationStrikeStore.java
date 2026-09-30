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
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

final class AiModerationStrikeStore {
    private final Path file;
    private final Clock clock;
    private final Duration window;
    private final Logger logger;
    private final Map<UUID, Deque<Instant>> strikes = new HashMap<>();

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

    synchronized int record(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Instant now = clock.instant();
        pruneAll(now);
        Deque<Instant> playerStrikes = strikes.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        playerStrikes.addLast(now);
        save();
        return playerStrikes.size();
    }

    synchronized int count(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Instant now = clock.instant();
        pruneAll(now);
        Deque<Instant> playerStrikes = strikes.get(playerId);
        return playerStrikes == null ? 0 : playerStrikes.size();
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
                int separator = line.indexOf('\t');
                if (separator <= 0 || separator == line.length() - 1) {
                    continue;
                }
                UUID playerId;
                try {
                    playerId = UUID.fromString(line.substring(0, separator));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                Deque<Instant> loaded = new ArrayDeque<>();
                for (String rawTimestamp : line.substring(separator + 1).split(",")) {
                    try {
                        Instant timestamp = Instant.ofEpochMilli(Long.parseLong(rawTimestamp.trim()));
                        if (!timestamp.isAfter(now) && !timestamp.isBefore(now.minus(window))) {
                            loaded.addLast(timestamp);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // Ignore one corrupt timestamp instead of losing the rest of the ledger.
                    }
                }
                if (!loaded.isEmpty()) {
                    strikes.put(playerId, loaded);
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
        for (Map.Entry<UUID, Deque<Instant>> entry : strikes.entrySet()) {
            Deque<Instant> playerStrikes = entry.getValue();
            while (!playerStrikes.isEmpty() && playerStrikes.peekFirst().isBefore(cutoff)) {
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
            List<Map.Entry<UUID, Deque<Instant>>> entries = new ArrayList<>(strikes.entrySet());
            entries.sort(Comparator.comparing(entry -> entry.getKey().toString()));
            StringBuilder contents = new StringBuilder("# RoseChat AI moderation rolling strike timestamps (epoch milliseconds)\n");
            for (Map.Entry<UUID, Deque<Instant>> entry : entries) {
                contents.append(entry.getKey()).append('\t');
                boolean first = true;
                for (Instant timestamp : entry.getValue()) {
                    if (!first) {
                        contents.append(',');
                    }
                    first = false;
                    contents.append(timestamp.toEpochMilli());
                }
                contents.append('\n');
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
}
