package dev.rosewood.rosechat.manager;

import dev.rosewood.rosegarden.config.CommentedFileConfiguration;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.plugin.PluginManager;

/**
 * Defines RoseChat's cooperative command-ownership contract with EnthusiaStaff.
 */
public final class EnthusiaStaffCommandCompatibility {
    private static final String STAFF_PLUGIN = "EnthusiaStaff";
    private static final String FALLBACK_PREFIX = "rosechat";
    private static final Set<String> STAFF_RESERVED = Set.of("mute", "unmute", "staff");
    private static final List<String> PRIORITY_COMMANDS = List.of("mute", "unmute");

    private EnthusiaStaffCommandCompatibility() {
    }

    public static boolean staffInstalled(PluginManager plugins) {
        return plugins.getPlugin(STAFF_PLUGIN) != null;
    }

    public static boolean shouldYield(boolean staffInstalled, String command) {
        return staffInstalled && command != null
                && STAFF_RESERVED.contains(command.toLowerCase(Locale.ROOT));
    }

    public static boolean defaultPriority(boolean staffInstalled, String command, boolean configuredDefault) {
        return shouldYield(staffInstalled, command) ? false : configuredDefault;
    }

    public static String fallbackPrefix() {
        return FALLBACK_PREFIX;
    }

    public static List<String> normalizePersistedPriorities(File dataFolder, boolean staffInstalled) {
        if (!staffInstalled) {
            return List.of();
        }
        List<String> changed = new ArrayList<>();
        for (String command : PRIORITY_COMMANDS) {
            if (disablePriority(dataFolder, command)) {
                changed.add(command);
            }
        }
        return List.copyOf(changed);
    }

    private static boolean disablePriority(File dataFolder, String command) {
        File commandFile = new File(new File(dataFolder, "commands"), command + ".yml");
        if (!commandFile.isFile()) {
            return false;
        }
        CommentedFileConfiguration configuration = CommentedFileConfiguration.loadConfiguration(commandFile);
        if (!configuration.getBoolean("priority", false)) {
            return false;
        }
        configuration.set("priority", false);
        configuration.save(commandFile);
        return true;
    }
}
