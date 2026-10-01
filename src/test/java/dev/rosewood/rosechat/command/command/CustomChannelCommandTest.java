package dev.rosewood.rosechat.command.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CustomChannelCommandTest {

    @Test
    void lookupUsesRegisteredNameInsteadOfDispatchAlias() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/rosewood/rosechat/command/command/CustomChannelCommand.java"));
        assertTrue(source.contains("String commandName = this.channelLookupName()"));
        assertTrue(source.contains("return this.getName().toLowerCase(Locale.ROOT)"));
        assertFalse(source.contains("contains(label.toLowerCase"));
    }
}
