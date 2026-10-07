package dev.rosewood.rosechat.api.chatbridge;

/**
 * Result of RoseChat admission for one provider-neutral Discord message.
 *
 * <p>{@link #acknowledged()} means the transport must not retry this exact message. Terminal
 * policy/configuration rejections are acknowledged-and-dropped; only transient local
 * unavailability, saturation, or failure remain retryable within the message TTL.</p>
 */
public enum InboundChatResult {
    ACCEPTED(true),
    DUPLICATE(true),
    EXPIRED(true),
    POLICY_UNAVAILABLE(false),
    CHANNEL_NOT_FOUND(true),
    CHANNEL_NOT_PUBLIC(true),
    CHANNEL_MUTED(true),
    SATURATED(false),
    TOO_MANY_LINES(true),
    BLOCKED(true),
    EMPTY(true),
    FAILED(false);

    private final boolean acknowledged;

    InboundChatResult(boolean acknowledged) {
        this.acknowledged = acknowledged;
    }

    /**
     * @return true when the authenticated transport must consume the message without retry
     */
    public boolean acknowledged() {
        return this.acknowledged;
    }
}
