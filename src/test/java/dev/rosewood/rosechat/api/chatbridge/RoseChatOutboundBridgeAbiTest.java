package dev.rosewood.rosechat.api.chatbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.rosewood.rosechat.api.RoseChatAPI;
import dev.rosewood.rosechat.hook.channel.rosechat.RoseChatChannel;
import dev.rosewood.rosechat.message.MessageDirection;
import dev.rosewood.rosechat.message.RoseMessage;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** Exact ABI consumed by the deployed EnthusiaStaff Paper TEST plugin. */
class RoseChatOutboundBridgeAbiTest {
    @Test
    void deployedPaperOutboundAbiIsPublishedByRoseChat() throws Exception {
        Method install = RoseChatAPI.class.getMethod(
                "installOutboundChatBridge", OutboundChatBridge.class);
        assertEquals(OutboundChatBridgeCoordinator.Registration.class,
                install.getReturnType());
        Method publish = RoseChatAPI.class.getMethod(
                "publishOutboundChatBridge", OutboundChatMessage.class);
        assertEquals(OutboundChatBridgeCoordinator.DispatchResult.class,
                publish.getReturnType());

        Method acceptedMessage = RoseChatChannel.class.getDeclaredMethod(
                "publishAcceptedMinecraftChat", RoseMessage.class, MessageDirection.class);
        assertNotNull(acceptedMessage);
    }
}
