package dev.rosewood.rosechat.listener;

import dev.rosewood.rosegarden.utils.StringPlaceholders;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import dev.rosewood.rosegarden.config.CommentedFileConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordIdentityPlaceholdersTest {
    @Test void linkedSenderRetainsNicknameAndContext() {
        StringPlaceholders placeholders = DiscordIdentityPlaceholders.add(
                StringPlaceholders.builder().add("user_name", "DiscordName"), "FainNeito", true).build();
        assertEquals("true/FainNeito/DiscordName", placeholders.apply("%discord_linked%/%user_nickname%/%user_name%"));
    }

    @Test void unlinkedSenderCannotClaimMinecraftIdentity() {
        StringPlaceholders placeholders = DiscordIdentityPlaceholders.add(
                StringPlaceholders.builder(), "OG++", false).build();
        assertEquals("false/OG++", placeholders.apply("%discord_linked%/%user_nickname%"));
    }

    @Test void reusedBuilderDoesNotRetainPreviousLinkStatus() {
        StringPlaceholders.Builder builder = StringPlaceholders.builder();
        DiscordIdentityPlaceholders.add(builder, "Linked", true);
        assertEquals("false/Unlinked", DiscordIdentityPlaceholders.add(builder, "Unlinked", false)
                .build().apply("%discord_linked%/%user_nickname%"));
    }

    @Test void defaultFormatUsesMinecraftPrefixOnlyForLinkedAccounts() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/custom-placeholders.yml")) {
            assertNotNull(input);
            var yaml = CommentedFileConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
            assertEquals("&6[D]&r ", yaml.getString("from-discord.text.default"));
            assertEquals("%discord_linked%", yaml.getString("discord-player.text.condition"));
            assertEquals("{prefix}%user_nickname%", yaml.getString("discord-player.text.true"));
            assertEquals("&7%user_nickname%", yaml.getString("discord-player.text.default"));
        }
    }
}
