package dev.rosewood.rosechat.listener;

import dev.rosewood.rosegarden.utils.StringPlaceholders;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertTrue(yaml.contains("from-discord:\n  text:\n    default: \"&6[D]&r \""));
            assertTrue(yaml.contains("discord-player:\n  text:\n    condition: \"%discord_linked%\"\n"
                    + "    true: \"{prefix}%user_nickname%\"\n    default: \"&7%user_nickname%\""));
        }
    }
}
