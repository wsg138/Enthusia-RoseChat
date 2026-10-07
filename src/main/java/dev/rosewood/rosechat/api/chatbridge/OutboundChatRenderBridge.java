package dev.rosewood.rosechat.api.chatbridge;

/**
 * Optional provider-neutral sink for styled outbound chat renders.
 *
 * <p>Implementations must remain best-effort. Throwing from this boundary is contained by the
 * coordinator and must never block Minecraft chat.</p>
 */
@FunctionalInterface
public interface OutboundChatRenderBridge {
    void publish(RenderedOutboundChatMessage message);
}
