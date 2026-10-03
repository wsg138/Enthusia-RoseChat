package dev.rosewood.rosechat.moderation.ai.central;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Identity strategy for central moderation requests.
 *
 * <ul>
 *   <li>{@code external_message_id} is stable for one logical RoseChat message
 *       across every retry of that message. It is derived once from the
 *       message's event UUID and never regenerated: a changed payload with the
 *       same key is a central-side conflict, not a new event.</li>
 *   <li>{@code canonical_message_id} groups platform mirrors of the same
 *       logical message. The Minecraft original and its Discord mirror share
 *       one canonical ID so the service stores a single moderation event and
 *       replays the decision to aliases.</li>
 * </ul>
 *
 * <p>Provider-neutral mirror contract: the moderation layer exposes the
 * canonical ID for a published RoseChat message UUID through
 * {@link IdRegistry}. A future Discord transport (W14) resolves the same
 * canonical ID for the mirror copy instead of inventing a second logical
 * message. No Discord transport is built or changed here.</p>
 */
public final class CentralModerationIds {
    private static final String EXTERNAL_PREFIX = "rosechat-mc-";
    private static final String CANONICAL_PREFIX = "rosechat-canonical-";

    private CentralModerationIds() {
    }

    /**
     * Derives the stable external message ID for one logical message.
     * The same {@code eventId} always yields the same ID; callers must pass
     * the message's original event UUID on retry rather than minting a new one.
     */
    public static String externalMessageId(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return EXTERNAL_PREFIX + eventId;
    }

    /**
     * Derives the canonical message ID shared by every platform mirror of one
     * logical message.
     */
    public static String canonicalMessageId(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return CANONICAL_PREFIX + eventId;
    }

    /**
     * Recovers the event UUID from an external message ID produced by
     * {@link #externalMessageId(UUID)}, or {@code null} when the ID was not
     * minted by this strategy.
     */
    public static UUID eventIdFromExternal(String externalMessageId) {
        if (externalMessageId == null || !externalMessageId.startsWith(EXTERNAL_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(externalMessageId.substring(EXTERNAL_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Bounded registry linking central IDs back to the exact RoseChat message
     * UUIDs this instance published. Used for exact late deletion and for
     * retroactive {@code related_messages} handling: only references that
     * resolve here are ever deleted, so RoseChat never guesses a message.
     */
    public static final class IdRegistry {
        private static final int MAX_ENTRIES = 4096;
        private static final int MAX_ALIASES_PER_CANONICAL = 8;

        private final Map<String, UUID> externalToMessage = new LinkedHashMap<>();
        private final Map<String, Deque<String>> canonicalToExternals = new LinkedHashMap<>();

        /**
         * Records that {@code externalMessageId} was published as the exact
         * RoseChat message {@code messageId}.
         */
        public synchronized void registerPublished(String externalMessageId, UUID messageId) {
            Objects.requireNonNull(externalMessageId, "externalMessageId");
            Objects.requireNonNull(messageId, "messageId");
            externalToMessage.put(externalMessageId, messageId);
            while (externalToMessage.size() > MAX_ENTRIES) {
                externalToMessage.remove(externalToMessage.keySet().iterator().next());
            }
        }

        /**
         * Records a platform alias (mirror copy) for a canonical message.
         */
        public synchronized void registerAlias(String canonicalMessageId, String externalMessageId) {
            Objects.requireNonNull(canonicalMessageId, "canonicalMessageId");
            Objects.requireNonNull(externalMessageId, "externalMessageId");
            Deque<String> aliases = canonicalToExternals.computeIfAbsent(canonicalMessageId, ignored -> new ArrayDeque<>());
            if (!aliases.contains(externalMessageId)) {
                aliases.addLast(externalMessageId);
                while (aliases.size() > MAX_ALIASES_PER_CANONICAL) {
                    aliases.removeFirst();
                }
            }
            while (canonicalToExternals.size() > MAX_ENTRIES) {
                canonicalToExternals.remove(canonicalToExternals.keySet().iterator().next());
            }
        }

        /**
         * Resolves a central external ID to the exact RoseChat message UUID,
         * or {@code null} when this instance never published it.
         */
        public synchronized UUID resolveMessageId(String externalMessageId) {
            return externalMessageId == null ? null : externalToMessage.get(externalMessageId);
        }

        /**
         * Lists every known platform alias for a canonical message.
         */
        public synchronized List<String> aliasesFor(String canonicalMessageId) {
            Deque<String> aliases = canonicalToExternals.get(canonicalMessageId);
            return aliases == null ? List.of() : new ArrayList<>(aliases);
        }

        synchronized int size() {
            return externalToMessage.size();
        }
    }
}
