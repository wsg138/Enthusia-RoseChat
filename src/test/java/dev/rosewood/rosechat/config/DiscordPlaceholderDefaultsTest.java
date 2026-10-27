package dev.rosewood.rosechat.config;

import dev.rosewood.rosegarden.config.CommentedFileConfiguration;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiscordPlaceholderDefaultsTest {
    @TempDir Path directory;

    @Test void missingEntriesPersistWithoutChangingOtherValuesOrComments() {
        var installed = config("# Operator comment\ncustom:\n  text:\n    default: keep\n");
        assertTrue(DiscordPlaceholderDefaults.merge(installed, defaults()));
        Path path = directory.resolve("placeholders.yml");
        installed.save(path.toFile());
        var reloaded = CommentedFileConfiguration.loadConfiguration(path.toFile());
        assertEquals("keep", reloaded.getString("custom.text.default"));
        assertEquals("&6[D]&r ", reloaded.getString("from-discord.text.default"));
        assertEquals("{prefix}%user_nickname%", reloaded.getString("discord-player.text.true"));
        assertTrue(reloaded.saveToString().contains("Operator comment"));
        assertFalse(DiscordPlaceholderDefaults.merge(reloaded, defaults()));
    }

    @Test void untouchedLegacyTextUpgradesButCustomHoverIsPreserved() {
        var installed = config("""
                discord-player:
                  text:
                    default: '&8[%user_color%%user_role%&8] %user_color%%user_nickname%'
                  hover:
                    default: custom hover
                from-discord:
                  text:
                    default: '&dDiscord &7| '
                """);
        assertTrue(DiscordPlaceholderDefaults.merge(installed, defaults()));
        assertEquals("%discord_linked%", installed.getString("discord-player.text.condition"));
        assertEquals("custom hover", installed.getString("discord-player.hover.default"));
        assertEquals("&6[D]&r ", installed.getString("from-discord.text.default"));
    }

    @Test void customTextAndExplicitEmptyTextAreNotPartiallyMerged() {
        var installed = config("""
                discord-player:
                  text:
                    default: custom identity
                from-discord:
                  text: {}
                """);
        String before = installed.saveToString();
        assertFalse(DiscordPlaceholderDefaults.merge(installed, defaults()));
        assertEquals(before, installed.saveToString());
        assertFalse(installed.contains("discord-player.text.condition"));
    }

    @Test void missingTextPreservesOtherLocationsAndExtraConditionsPreventUpgrade() {
        var installed = config("""
                from-discord:
                  hover:
                    default: keep
                discord-player:
                  text:
                    default: '&8[%user_color%%user_role%&8] %user_color%%user_nickname%'
                    condition: '%custom%'
                    true: custom
                """);
        assertTrue(DiscordPlaceholderDefaults.merge(installed, defaults()));
        assertEquals("keep", installed.getString("from-discord.hover.default"));
        assertEquals("%custom%", installed.getString("discord-player.text.condition"));
        assertEquals("custom", installed.getString("discord-player.text.true"));
    }

    @Test void newInstallationDefaultsAreUnchanged() {
        var installed = defaults();
        String before = installed.saveToString();
        assertFalse(DiscordPlaceholderDefaults.merge(installed, defaults()));
        assertEquals(before, installed.saveToString());
    }

    private static CommentedFileConfiguration config(String yaml) {
        return CommentedFileConfiguration.loadConfiguration(new StringReader(yaml));
    }

    private static CommentedFileConfiguration defaults() {
        return CommentedFileConfiguration.loadConfiguration(new InputStreamReader(
                java.util.Objects.requireNonNull(DiscordPlaceholderDefaultsTest.class.getResourceAsStream(
                        "/custom-placeholders.yml")), StandardCharsets.UTF_8));
    }
}
