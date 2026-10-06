package dev.rosewood.rosechat.api.chatbridge;

import dev.rosewood.rosechat.api.staff.ChannelClassification;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Safety boundary between RoseChat and an external public-chat transport.
 *
 * <p>The coordinator intentionally owns no retry queue. Delivery is best-effort:
 * outages, renderer/transport failures, or local pressure are reported to the caller
 * without throwing into the Minecraft chat path.</p>
 */
public final class OutboundChatBridgeCoordinator implements AutoCloseable {

    public static final int MAX_PLAIN_TEXT_LENGTH = 2_000;
    public static final int DEFAULT_MAX_DEDUPE_ENTRIES = 4_096;
    public static final Duration DEFAULT_DEDUPE_TTL = Duration.ofSeconds(30);

    public enum DispatchResult {
        DELIVERED,
        NO_BRIDGE,
        NOT_PUBLIC,
        LOOP_SUPPRESSED,
        DUPLICATE,
        EXPIRED,
        OVERSIZE,
        BACKPRESSURE,
        FAILED
    }

    private final AtomicReference<BridgeSlot> bridge = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<UUID, Long> dedupeUntil = new HashMap<>();
    private final Object dedupeLock = new Object();
    private final Clock clock;
    private final Duration dedupeTtl;
    private final int maximumDedupeEntries;

    /**
     * Creates a coordinator with the production clock and default bounded dedupe settings.
     */
    public OutboundChatBridgeCoordinator() {
        this(Clock.systemUTC(), DEFAULT_DEDUPE_TTL, DEFAULT_MAX_DEDUPE_ENTRIES);
    }

    /**
     * Creates a coordinator with explicit clock and dedupe settings for deterministic testing.
     *
     * @param clock time source used for expiry decisions
     * @param dedupeTtl minimum time an admitted event ID remains deduplicated
     * @param maximumDedupeEntries hard cap on in-memory dedupe entries
     */
    OutboundChatBridgeCoordinator(Clock clock, Duration dedupeTtl, int maximumDedupeEntries) {
        if (clock == null || dedupeTtl == null || dedupeTtl.isNegative() || dedupeTtl.isZero()
                || maximumDedupeEntries < 1) {
            throw new IllegalArgumentException("outbound chat bridge coordinator configuration is invalid");
        }
        this.clock = clock;
        this.dedupeTtl = dedupeTtl;
        this.maximumDedupeEntries = maximumDedupeEntries;
    }

    /**
     * Installs the single active outbound bridge.
     *
     * @param outboundBridge provider to receive eligible messages
     * @return a registration that removes only the installation created by this call
     */
    public synchronized Registration install(OutboundChatBridge outboundBridge) {
        if (outboundBridge == null) {
            throw new IllegalArgumentException("outbound chat bridge is required");
        }
        if (this.closed.get()) {
            throw new IllegalStateException("outbound chat bridge coordinator is closed");
        }

        BridgeSlot slot = new BridgeSlot(outboundBridge);
        if (!this.bridge.compareAndSet(null, slot)) {
            throw new IllegalStateException("an outbound chat bridge is already installed");
        }
        return () -> this.bridge.compareAndSet(slot, null);
    }

    /**
     * Offers a message to the bridge without allowing bridge failure to affect Minecraft chat.
     *
     * @param message policy-approved outbound candidate
     * @return the dispatch decision made by this safety boundary
     */
    public DispatchResult publish(OutboundChatMessage message) {
        if (this.closed.get()) {
            return DispatchResult.NO_BRIDGE;
        }
        if (message.origin() != OutboundChatMessage.Origin.MINECRAFT) {
            return DispatchResult.LOOP_SUPPRESSED;
        }
        if (message.classification() != ChannelClassification.PUBLIC) {
            return DispatchResult.NOT_PUBLIC;
        }
        if (message.plainText().length() > MAX_PLAIN_TEXT_LENGTH) {
            return DispatchResult.OVERSIZE;
        }

        long now = this.clock.millis();
        if (message.isExpired(now)) {
            return DispatchResult.EXPIRED;
        }

        BridgeSlot currentSlot = this.bridge.get();
        if (currentSlot == null) {
            return DispatchResult.NO_BRIDGE;
        }

        DispatchResult admissionResult = this.reserveEventId(message, now);
        if (admissionResult != null) {
            return admissionResult;
        }

        try {
            currentSlot.bridge().publish(message);
            return DispatchResult.DELIVERED;
        } catch (RuntimeException exception) {
            return DispatchResult.FAILED;
        }
    }

    /**
     * Atomically prunes, checks capacity, and reserves an event ID.
     *
     * @param message candidate being admitted
     * @param now current time in epoch milliseconds
     * @return a rejection result, or {@code null} when the event was reserved
     */
    private DispatchResult reserveEventId(OutboundChatMessage message, long now) {
        synchronized (this.dedupeLock) {
            Long existing = this.dedupeUntil.get(message.eventId());
            if (existing != null && existing >= now) {
                return DispatchResult.DUPLICATE;
            }
            if (existing != null) {
                this.dedupeUntil.remove(message.eventId());
            }

            this.pruneExpired(now);
            if (this.dedupeUntil.size() >= this.maximumDedupeEntries) {
                return DispatchResult.BACKPRESSURE;
            }

            long dedupeExpiry = Math.max(message.expiresAtEpochMillis(), now + this.dedupeTtl.toMillis());
            this.dedupeUntil.put(message.eventId(), dedupeExpiry);
            return null;
        }
    }

    /**
     * Removes expired entries while the caller holds {@link #dedupeLock}.
     *
     * @param now current time in epoch milliseconds
     */
    private void pruneExpired(long now) {
        this.dedupeUntil.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    /**
     * Permanently closes this coordinator and releases its installed bridge and dedupe state.
     */
    @Override
    public synchronized void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        this.bridge.set(null);
        synchronized (this.dedupeLock) {
            this.dedupeUntil.clear();
        }
    }

    private record BridgeSlot(OutboundChatBridge bridge) { }

    /**
     * Handle for releasing one specific bridge installation.
     */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        /**
         * Releases this installation if it is still the active owner.
         */
        @Override
        void close();
    }
}
