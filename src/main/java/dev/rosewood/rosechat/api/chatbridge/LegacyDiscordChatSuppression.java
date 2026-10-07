package dev.rosewood.rosechat.api.chatbridge;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-owner lifecycle gate for suppressing RoseChat's legacy Discord provider during an
 * externally-authoritative chat cutover.
 *
 * <p>Suppression is reversible. Closing the returned registration restores legacy sends only when
 * that registration still owns the active suppression slot.</p>
 */
public final class LegacyDiscordChatSuppression implements AutoCloseable {

    private record Slot(Object identity) { }

    private final AtomicReference<Slot> slot = new AtomicReference<>();

    /**
     * @return {@code true} while an external transport owns Discord chat authority
     */
    public boolean suppressed() {
        return this.slot.get() != null;
    }

    /**
     * Acquires the single suppression slot.
     *
     * @return registration that releases only this acquisition
     * @throws IllegalStateException when another owner already suppresses legacy Discord chat
     */
    public Registration suppress() {
        Slot next = new Slot(new Object());
        if (!this.slot.compareAndSet(null, next)) {
            throw new IllegalStateException("legacy Discord chat is already suppressed");
        }
        return () -> this.slot.compareAndSet(next, null);
    }

    @Override
    public void close() {
        this.slot.set(null);
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
