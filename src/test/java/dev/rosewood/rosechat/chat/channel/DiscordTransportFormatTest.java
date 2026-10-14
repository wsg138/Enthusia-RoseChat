package dev.rosewood.rosechat.chat.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class DiscordTransportFormatTest {
    @Test
    void dedicatedTransportFormatPreservesMinecraftAndLegacyDiscordFormats() {
        ChannelSettings settings = new ChannelSettings();
        settings.getFormats().put("chat", "{prefix}{player}{message}");
        settings.getFormats().put("minecraft-to-discord", "{discord}");
        String clean = "[%enthusiarep_score%] [{discord-rank}] %player_name%: {message}";
        settings.getFormats().put("discord-transport", clean);

        assertEquals(clean, settings.getDiscordTransportFormat("{prefix}{player}{message}"));
        assertEquals("{prefix}{player}{message}", settings.getFormats().get("chat"));
        assertEquals("{discord}", settings.getFormats().get("minecraft-to-discord"));
    }

    @Test
    void missingOrBlankOverrideKeepsCallerFormatIncludingNull() {
        ChannelSettings settings = new ChannelSettings();
        assertEquals("old", settings.getDiscordTransportFormat("old"));
        assertNull(settings.getDiscordTransportFormat(null));
        settings.getFormats().put("discord-transport", "  ");
        assertEquals("old", settings.getDiscordTransportFormat("old"));
    }
}
