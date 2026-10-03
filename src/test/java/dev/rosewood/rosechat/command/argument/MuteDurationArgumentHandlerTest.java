package dev.rosewood.rosechat.command.argument;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.rosewood.rosechat.command.argument.MuteDuration.Unit;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class MuteDurationArgumentHandlerTest {

    @Test
    void unitKeysDoNotDependOnJvmLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("minute", MuteDurationArgumentHandler.unitKey(Unit.MINUTE));
        } finally {
            Locale.setDefault(original);
        }
    }
}
