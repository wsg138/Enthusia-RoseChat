package dev.rosewood.rosechat.hook.channel.rosechat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rosewood.rosechat.message.MessageDirection;
import org.junit.jupiter.api.Test;

class RoseChatChannelOutboundBridgeTest {

    @Test
    void exportsOnlyCanonicalMinecraftDirections() {
        assertTrue(RoseChatChannel.shouldPublishOutboundBridge(
                MessageDirection.PLAYER_TO_SERVER, false));
        assertTrue(RoseChatChannel.shouldPublishOutboundBridge(
                MessageDirection.SERVER_TO_SERVER, true));

        assertFalse(RoseChatChannel.shouldPublishOutboundBridge(
                MessageDirection.SERVER_TO_SERVER, false));
        assertFalse(RoseChatChannel.shouldPublishOutboundBridge(
                MessageDirection.SERVER_TO_SERVER_RAW, true));
        assertFalse(RoseChatChannel.shouldPublishOutboundBridge(
                MessageDirection.MINECRAFT_TO_DISCORD, true));
        assertFalse(RoseChatChannel.shouldPublishOutboundBridge(
                MessageDirection.DISCORD_TO_MINECRAFT, true));
    }
}
