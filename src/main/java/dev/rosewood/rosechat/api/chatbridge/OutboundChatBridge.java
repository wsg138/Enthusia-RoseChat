package dev.rosewood.rosechat.api.chatbridge;

/**
 * Provider-neutral sink for policy-approved public Minecraft chat.
 *
 * <p>Implementations must be best-effort and bounded. They must not block or fail
 * Minecraft chat when Discord or the remote bridge is unavailable.</p>
 */
@FunctionalInterface
public interface OutboundChatBridge {

    void publish(OutboundChatMessage message);

}
