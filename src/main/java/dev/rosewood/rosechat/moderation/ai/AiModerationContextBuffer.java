package dev.rosewood.rosechat.moderation.ai;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AiModerationContextBuffer {
    private final Clock clock;
    private final AiModerationConfig config;
    private final Map<String, Deque<Entry>> channels = new HashMap<>();

    public AiModerationContextBuffer(Clock clock, AiModerationConfig config) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.config = Objects.requireNonNull(config, "config");
    }

    public synchronized Snapshot record(
            String channelId,
            UUID eventId,
            UUID senderId,
            String senderName,
            String message
    ) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(senderName, "senderName");
        Objects.requireNonNull(message, "message");
        Instant now = clock.instant();
        Deque<Entry> entries = channels.computeIfAbsent(channelId, ignored -> new ArrayDeque<>());
        trimExpired(entries, now);
        entries.addLast(new Entry(eventId, senderId, senderName, message, now));
        trimStorage(entries);
        return snapshot(channelId, eventId).orElseThrow();
    }

    public synchronized Optional<Snapshot> snapshot(String channelId, UUID eventId) {
        Deque<Entry> entries = channels.get(channelId);
        if (entries == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        trimExpired(entries, now);
        List<Entry> available = new ArrayList<>(entries);
        int targetAbsolute = indexOf(available, eventId);
        if (targetAbsolute < 0) {
            return Optional.empty();
        }
        int from = Math.max(0, targetAbsolute - config.beforeMessages());
        int to = Math.min(available.size(), targetAbsolute + config.afterMessages() + 1);
        List<Entry> bounded = new ArrayList<>(available.subList(from, to));
        int targetIndex = targetAbsolute - from;
        while (render(channelId, eventId, bounded, targetIndex).length() > config.contextMaxCharacters()
                && bounded.size() > 1) {
            if (targetIndex > 0) {
                bounded.remove(0);
                targetIndex--;
            } else if (bounded.size() > 1) {
                bounded.remove(bounded.size() - 1);
            }
        }
        Position position = position(targetIndex, bounded.size());
        return Optional.of(new Snapshot(
                channelId,
                eventId,
                targetIndex,
                position,
                bounded.size(),
                bounded.get(targetIndex).message(),
                render(channelId, eventId, bounded, targetIndex),
                hasAfter(targetIndex, bounded.size())
        ));
    }

    private void trimExpired(Deque<Entry> entries, Instant now) {
        Instant cutoff = now.minus(config.contextMaxAge());
        while (!entries.isEmpty() && entries.peekFirst().at().isBefore(cutoff)) {
            entries.removeFirst();
        }
    }

    private void trimStorage(Deque<Entry> entries) {
        int maximum = Math.max(12, (config.beforeMessages() + config.afterMessages() + 1) * 4);
        while (entries.size() > maximum) {
            entries.removeFirst();
        }
    }

    private static int indexOf(List<Entry> entries, UUID eventId) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).eventId().equals(eventId)) {
                return i;
            }
        }
        return -1;
    }

    private static String render(String channelId, UUID eventId, List<Entry> entries, int targetIndex) {
        StringBuilder result = new StringBuilder(512);
        result.append("Minecraft multiplayer public chat context.\n")
                .append("CHANNEL=").append(channelId).append('\n')
                .append("TARGET_EVENT_ID=").append(eventId).append('\n')
                .append("TARGET_INDEX=").append(targetIndex).append('\n')
                .append("TARGET_POSITION=").append(position(targetIndex, entries.size())).append('\n')
                .append("CONTEXT_SIZE=").append(entries.size()).append('\n')
                .append("Only judge the marked target message; neighboring lines are context.\n");
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (i == targetIndex) {
                result.append("<<< TARGET START >>>\n");
            }
            result.append('[').append(i).append("] ")
                    .append(safeName(entry.senderName()))
                    .append(": ")
                    .append(entry.message())
                    .append('\n');
            if (i == targetIndex) {
                result.append("<<< TARGET END >>>\n");
            }
        }
        return result.toString();
    }

    private static Position position(int targetIndex, int size) {
        if (size <= 1 || targetIndex == size - 1) {
            return Position.END;
        }
        if (targetIndex == 0) {
            return Position.START;
        }
        return Position.MIDDLE;
    }

    private static boolean hasAfter(int targetIndex, int size) {
        return targetIndex < size - 1;
    }

    private static String safeName(String input) {
        return input.replace('\n', ' ').replace('\r', ' ');
    }

    public enum Position {
        START,
        MIDDLE,
        END
    }

    public record Snapshot(
            String channelId,
            UUID eventId,
            int targetIndex,
            Position targetPosition,
            int contextSize,
            String targetMessage,
            String transcript,
            boolean hasAfterContext
    ) {
    }

    private record Entry(UUID eventId, UUID senderId, String senderName, String message, Instant at) {
    }
}
