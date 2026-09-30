package dev.rosewood.rosechat.api.chatbridge;

import dev.rosewood.rosechat.api.staff.ChannelClassification;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Safety boundary between RoseChat and an external public-chat transport.
 *
 * <p>The coordinator intentionally owns no retry queue. Delivery is best-effort:
 * outages, renderer/transport failures, or local pressure are reported to the caller
 * without throwing into the Minecraft chat path.</p>
 */
public final class OutboundChatBridgeCoordinator {

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

    private final AtomicReference<OutboundChatBridge> bridge = new AtomicReference<>();
    private final Map<UUID, Long> dedupeUntil = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration dedupeTtl;
    private final int maximumDedupeEntries;

    public OutboundChatBridgeCoordinator() {
        this(Clock.systemUTC(), DEFAULT_DEDUPE_TTL, DEFAULT_MAX_DEDUPE_ENTRIES);
    }

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
     * @return a registration that removes only the bridge installed by this call
     */
    public Registration install(OutboundChatBridge outboundBridge) {
        if (outboundBridge == null) {
            throw new IllegalArgumentException("outbound chat bridge is required");
        }
        if (!this.bridge.compareAndSet(null, outboundBridge)) {
            throw new IllegalStateException("an outbound chat bridge is already installed");
        }
        return () -> this.bridge.compareAndSet(outboundBridge, null);
    }

    /**
     * Offers a message to the bridge without allowing bridge failure to affect Minecraft chat.
     */
    public DispatchResult publish(OutboundChatMessage message) {
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

        OutboundChatBridge currentBridge = this.bridge.get();
        if (currentBridge == null) {
            return DispatchResult.NO_BRIDGE;
        }

        this.pruneExpired(now);
        if (this.dedupeUntil.size() >= this.maximumDedupeEntries) {
            return DispatchResult.BACKPRESSURE;
        }

        long dedupeExpiry = Math.max(message.expiresAtEpochMillis(), now + this.dedupeTtl.toMillis());
        Long existing = this.dedupeUntil.putIfAbsent(message.eventId(), dedupeExpiry);
        if (existing != null && existing >= now) {
            return DispatchResult.DUPLICATE;
        }
        if (existing != null) {
            this.dedupeUntil.replace(message.eventId(), existing, dedupeExpiry);
        }

        try {
            currentBridge.publish(message);
            return DispatchResult.DELIVERED;
        } catch (RuntimeException exception) {
            return DispatchResult.FAILED;
        }
    }

    private void pruneExpired(long now) {
        this.dedupeUntil.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
