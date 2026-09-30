package dev.rosewood.rosechat.api.chatbridge;

import dev.rosewood.rosechat.api.staff.ChannelClassification;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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

    /** Result of attempting to export one message. */
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

    private final AtomicReference<BridgeInstallation> bridge = new AtomicReference<>();
    private final Map<UUID, Long> dedupeUntil = new HashMap<>();
    private final Object dedupeLock = new Object();
    private final Clock clock;
    private final Duration dedupeTtl;
    private final int maximumDedupeEntries;

    /** Creates a coordinator with the standard bounded dedupe window. */
    public OutboundChatBridgeCoordinator() {
        this(Clock.systemUTC(), DEFAULT_DEDUPE_TTL, DEFAULT_MAX_DEDUPE_ENTRIES);
    }

    /**
     * Creates a coordinator with injectable timing and capacity for deterministic tests.
     *
     * @param clock clock used for expiry checks
     * @param dedupeTtl minimum duration an admitted event id remains reserved
     * @param maximumDedupeEntries maximum number of distinct event ids retained at once
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
     * @param outboundBridge provider-neutral outbound bridge
     * @return a registration token that removes only this specific installation
     */
    public Registration install(OutboundChatBridge outboundBridge) {
        if (outboundBridge == null) {
            throw new IllegalArgumentException("outbound chat bridge is required");
        }

        BridgeInstallation installation = new BridgeInstallation(outboundBridge);
        if (!this.bridge.compareAndSet(null, installation)) {
            throw new IllegalStateException("an outbound chat bridge is already installed");
        }
        return () -> this.bridge.compareAndSet(installation, null);
    }

    /**
     * Offers a message to the bridge without allowing bridge failure to affect Minecraft chat.
     *
     * @param message policy-approved outbound message
     * @return dispatch outcome; failures are represented as values rather than propagated
     */
    public DispatchResult publish(OutboundChatMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("outbound chat message is required");
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

        BridgeInstallation installation = this.bridge.get();
        if (installation == null) {
            return DispatchResult.NO_BRIDGE;
        }

        DispatchResult admissionResult = this.reserveEventId(message, now);
        if (admissionResult != null) {
            return admissionResult;
        }

        try {
            installation.bridge().publish(message);
            return DispatchResult.DELIVERED;
        } catch (RuntimeException exception) {
            return DispatchResult.FAILED;
        }
    }

    /**
     * Atomically prunes, checks capacity, and reserves an event id.
     *
     * @return a rejection result, or {@code null} when the event was admitted
     */
    private DispatchResult reserveEventId(OutboundChatMessage message, long now) {
        synchronized (this.dedupeLock) {
            this.dedupeUntil.entrySet().removeIf(entry -> entry.getValue() < now);

            Long existing = this.dedupeUntil.get(message.eventId());
            if (existing != null && existing >= now) {
                return DispatchResult.DUPLICATE;
            }
            if (this.dedupeUntil.size() >= this.maximumDedupeEntries) {
                return DispatchResult.BACKPRESSURE;
            }

            long dedupeExpiry = Math.max(message.expiresAtEpochMillis(), now + this.dedupeTtl.toMillis());
            this.dedupeUntil.put(message.eventId(), dedupeExpiry);
            return null;
        }
    }

    /** Handle used by the installing owner to unregister exactly its installation. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private record BridgeInstallation(OutboundChatBridge bridge) {
    }
}
