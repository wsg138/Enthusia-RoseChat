package dev.rosewood.rosechat.command.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class CustomChannelCommandTest {

    @Test
    void lookupUsesRegisteredNameInsteadOfDispatchAlias() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            CustomChannelCommand command = new CustomChannelCommand("STAFF");
            assertEquals("staff", command.channelLookupName());
        } finally {
            Locale.setDefault(original);
        }
    }
}
