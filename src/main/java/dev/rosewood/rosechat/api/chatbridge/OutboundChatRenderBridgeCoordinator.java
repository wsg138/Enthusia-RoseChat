package dev.rosewood.rosechat.api.chatbridge;

import dev.rosewood.rosechat.api.staff.ChannelClassification;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Safety boundary for optional styled outbound chat rendering.
 *
 * <p>This lane is intentionally independent from the plain-text V1 bridge. A renderer can be
 * installed during migration while the existing plain-text path remains available as fallback.
 * No retry queue is owned here.</p>
 */
public final class OutboundChatRenderBridgeCoordinator implements AutoCloseable {

    public static final int DEFAULT_MAX_DEDUPE_ENTRIES = 4_096;
    public static final Duration DEFAULT_DEDUPE_TTL = Duration.ofSeconds(30);

    public enum DispatchResult {
        DELIVERED,
        NO_BRIDGE,
        NOT_PUBLIC,
        LOOP_SUPPRESSED,
        DUPLICATE,
        EXPIRED,
        BACKPRESSURE,
        FAILED
    }

    private record BridgeSlot(OutboundChatRenderBridge bridge) { }

    private final AtomicReference<BridgeSlot> bridge = new AtomicReference<>();
    private final Map<UUID, Long> dedupeUntil = new HashMap<>();
    private final Object dedupeLock = new Object();
    private final Clock clock;
    private final Duration dedupeTtl;
    private final int maximumDedupeEntries;

    public OutboundChatRenderBridgeCoordinator() {
        this(Clock.systemUTC(), DEFAULT_DEDUPE_TTL, DEFAULT_MAX_DEDUPE_ENTRIES);
    }

    OutboundChatRenderBridgeCoordinator(
            Clock clock,
            Duration dedupeTtl,
            int maximumDedupeEntries
    ) {
        if (clock == null || dedupeTtl == null || dedupeTtl.isNegative() || dedupeTtl.isZero()
                || maximumDedupeEntries < 1) {
            throw new IllegalArgumentException("render bridge coordinator configuration is invalid");
        }
        this.clock = clock;
        this.dedupeTtl = dedupeTtl;
        this.maximumDedupeEntries = maximumDedupeEntries;
    }

    public boolean installed() {
        return this.bridge.get() != null;
    }

    public Registration install(OutboundChatRenderBridge outboundBridge) {
        if (outboundBridge == null) {
            throw new IllegalArgumentException("outbound render bridge is required");
        }
        BridgeSlot slot = new BridgeSlot(outboundBridge);
        if (!this.bridge.compareAndSet(null, slot)) {
            throw new IllegalStateException("an outbound render bridge is already installed");
        }
        return () -> this.bridge.compareAndSet(slot, null);
    }

    public DispatchResult publish(RenderedOutboundChatMessage rendered) {
        if (rendered == null) {
            throw new IllegalArgumentException("rendered outbound chat message is required");
        }
        OutboundChatMessage message = rendered.message();
        if (message.origin() != OutboundChatMessage.Origin.MINECRAFT) {
            return DispatchResult.LOOP_SUPPRESSED;
        }
        if (message.classification() != ChannelClassification.PUBLIC) {
            return DispatchResult.NOT_PUBLIC;
        }

        long now = this.clock.millis();
        if (rendered.isExpired(now)) {
            return DispatchResult.EXPIRED;
        }

        BridgeSlot current = this.bridge.get();
        if (current == null) {
            return DispatchResult.NO_BRIDGE;
        }

        DispatchResult admission = this.reserve(message, now);
        if (admission != null) {
            return admission;
        }

        try {
            current.bridge().publish(rendered);
            return DispatchResult.DELIVERED;
        } catch (RuntimeException failure) {
            return DispatchResult.FAILED;
        }
    }

    private DispatchResult reserve(OutboundChatMessage message, long now) {
        synchronized (this.dedupeLock) {
            Long existing = this.dedupeUntil.get(message.eventId());
            if (existing != null && existing >= now) {
                return DispatchResult.DUPLICATE;
            }
            if (existing != null) {
                this.dedupeUntil.remove(message.eventId());
            }
            this.dedupeUntil.entrySet().removeIf(entry -> entry.getValue() < now);
            if (this.dedupeUntil.size() >= this.maximumDedupeEntries) {
                return DispatchResult.BACKPRESSURE;
            }
            long expiresAt = Math.max(message.expiresAtEpochMillis(), now + this.dedupeTtl.toMillis());
            this.dedupeUntil.put(message.eventId(), expiresAt);
            return null;
        }
    }

    @Override
    public void close() {
        this.bridge.set(null);
        synchronized (this.dedupeLock) {
            this.dedupeUntil.clear();
        }
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
