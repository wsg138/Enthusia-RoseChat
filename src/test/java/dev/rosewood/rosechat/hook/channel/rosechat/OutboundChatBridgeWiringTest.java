package dev.rosewood.rosechat.hook.channel.rosechat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OutboundChatBridgeWiringTest {

    private static final Path CHANNEL_SOURCE = Path.of(
            "src/main/java/dev/rosewood/rosechat/hook/channel/rosechat/RoseChatChannel.java");

    @Test
    void outboundBridgeRunsAfterStaffPolicyAndBeforeLegacyDiscordSrvDelivery() throws IOException {
        String source = Files.readString(CHANNEL_SOURCE);

        int staffPolicy = source.indexOf(".allowChannelDispatch(message, this.getId(), direction)");
        int bridgePublish = source.indexOf("this.publishToOutboundBridge(message, direction);");
        int legacyDiscord = source.indexOf("this.sendToDiscord(message, direction);");

        assertTrue(staffPolicy >= 0, "staff policy dispatch gate must remain present");
        assertTrue(bridgePublish > staffPolicy, "provider-neutral export must run only after policy acceptance");
        assertTrue(legacyDiscord > bridgePublish, "DiscordSRV delivery must remain after the migration export");
    }

    @Test
    void outboundBridgeSkipsNetworkRelaysAndUsesDefenseInDepthClassification() throws IOException {
        String source = Files.readString(CHANNEL_SOURCE);

        assertTrue(source.contains("direction != MessageDirection.PLAYER_TO_SERVER"));
        assertTrue(source.contains("direction != MessageDirection.DISCORD_TO_MINECRAFT"));
        assertTrue(source.contains("plugin.getStaffService().classifyChannel(this.getId())"));
        assertTrue(source.contains("? OutboundChatMessage.Origin.DISCORD"));
        assertTrue(source.contains("\"rosechat-mc-\" + eventId"));
        assertTrue(source.contains("\"rosechat-canonical-\" + eventId"));
    }
}
