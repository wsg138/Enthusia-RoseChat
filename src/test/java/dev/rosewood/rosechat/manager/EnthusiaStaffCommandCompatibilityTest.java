package dev.rosewood.rosechat.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rosewood.rosegarden.config.CommentedFileConfiguration;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnthusiaStaffCommandCompatibilityTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void yieldsOnlyReservedLabelsWhenStaffIsInstalled() {
        assertTrue(EnthusiaStaffCommandCompatibility.shouldYield(true, "mute"));
        assertTrue(EnthusiaStaffCommandCompatibility.shouldYield(true, "UNMUTE"));
        assertTrue(EnthusiaStaffCommandCompatibility.shouldYield(true, "staff"));
        assertFalse(EnthusiaStaffCommandCompatibility.shouldYield(true, "message"));
        assertFalse(EnthusiaStaffCommandCompatibility.shouldYield(false, "mute"));
    }

    @Test
    void priorityDefaultsStayUnchangedWithoutStaff() {
        assertTrue(EnthusiaStaffCommandCompatibility.defaultPriority(false, "mute", true));
        assertFalse(EnthusiaStaffCommandCompatibility.defaultPriority(false, "mute", false));
        assertFalse(EnthusiaStaffCommandCompatibility.defaultPriority(true, "mute", true));
        assertEquals("rosechat", EnthusiaStaffCommandCompatibility.fallbackPrefix());
    }

    @Test
    void persistedMutePrioritiesAreDemotedWhenStaffIsInstalled() {
        File commands = temporaryDirectory.resolve("commands").toFile();
        assertTrue(commands.mkdirs());
        writePriority(commands, "mute", true);
        writePriority(commands, "unmute", true);
        writePriority(commands, "message", true);

        List<String> changed = EnthusiaStaffCommandCompatibility.normalizePersistedPriorities(
                temporaryDirectory.toFile(), true);

        assertEquals(List.of("mute", "unmute"), changed);
        assertFalse(readPriority(commands, "mute"));
        assertFalse(readPriority(commands, "unmute"));
        assertTrue(readPriority(commands, "message"));
    }

    @Test
    void persistedPrioritiesAreUntouchedWithoutStaff() {
        File commands = temporaryDirectory.resolve("commands").toFile();
        assertTrue(commands.mkdirs());
        writePriority(commands, "mute", true);

        assertTrue(EnthusiaStaffCommandCompatibility.normalizePersistedPriorities(
                temporaryDirectory.toFile(), false).isEmpty());
        assertTrue(readPriority(commands, "mute"));
    }

    private static void writePriority(File directory, String command, boolean priority) {
        File file = new File(directory, command + ".yml");
        CommentedFileConfiguration configuration = CommentedFileConfiguration.loadConfiguration(file);
        configuration.set("priority", priority);
        configuration.save(file);
    }

    private static boolean readPriority(File directory, String command) {
        return CommentedFileConfiguration.loadConfiguration(new File(directory, command + ".yml"))
                .getBoolean("priority", false);
    }
}
