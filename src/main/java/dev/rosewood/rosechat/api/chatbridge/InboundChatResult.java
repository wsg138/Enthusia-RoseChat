package dev.rosewood.rosechat.api.chatbridge;

/** Result of RoseChat admission for one provider-neutral Discord message. */
public enum InboundChatResult {
    ACCEPTED(true),
    DUPLICATE(true),
    EXPIRED(false),
    POLICY_UNAVAILABLE(false),
    CHANNEL_NOT_FOUND(false),
    CHANNEL_NOT_PUBLIC(false),
    CHANNEL_MUTED(false),
    SATURATED(false),
    TOO_MANY_LINES(false),
    BLOCKED(false),
    EMPTY(false),
    FAILED(false);

    private final boolean acknowledged;

    InboundChatResult(boolean acknowledged) {
        this.acknowledged = acknowledged;
    }

    /**
     * @return true when an authenticated transport may ACK without retrying the message
     */
    public boolean acknowledged() {
        return this.acknowledged;
    }
}
