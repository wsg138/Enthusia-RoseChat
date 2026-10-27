package dev.rosewood.rosechat.config;

import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/** Narrow upgrade of missing or untouched bundled Discord text defaults. */
public final class DiscordPlaceholderDefaults {

    private static final Map<String, String> LEGACY_TEXT = Map.of(
            "discord-player", "&8[%user_color%%user_role%&8] %user_color%%user_nickname%",
            "from-discord", "&dDiscord &7| ");

    private DiscordPlaceholderDefaults() { }

    public static boolean merge(ConfigurationSection installed, ConfigurationSection bundled) {
        boolean changed = false;
        for (Map.Entry<String, String> entry : LEGACY_TEXT.entrySet()) {
            String id = entry.getKey();
            ConfigurationSection defaults = bundled.getConfigurationSection(id);
            if (defaults == null)
                throw new IllegalArgumentException("Missing bundled Discord placeholder: " + id);

            if (!installed.contains(id)) {
                copy(defaults, installed.createSection(id));
                changed = true;
                continue;
            }

            ConfigurationSection existing = installed.getConfigurationSection(id);
            // Explicit scalar/empty/custom text is operator-owned, not a missing default.
            if (existing == null)
                continue;
            if (!existing.contains("text")) {
                copy(defaults.getConfigurationSection("text"), existing.createSection("text"));
                changed = true;
                continue;
            }

            ConfigurationSection text = existing.getConfigurationSection("text");
            if (text != null && text.getKeys(false).equals(java.util.Set.of("default"))
                    && entry.getValue().equals(text.getString("default"))) {
                copy(defaults.getConfigurationSection("text"), text);
                changed = true;
            }
        }
        return changed;
    }

    private static void copy(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            ConfigurationSection child = source.getConfigurationSection(key);
            if (child == null)
                target.set(key, source.get(key));
            else
                copy(child, target.createSection(key));
        }
    }
}
