package dev.rosewood.rosechat.command.argument;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OfflinePlayerArgumentHandlerTest {

    @Test
    void partialNameMatchingIsCaseInsensitiveAndPrefixOnly() {
        assertTrue(OfflinePlayerArgumentHandler.matchesPrefix("FainNeito", "fain"));
        assertTrue(OfflinePlayerArgumentHandler.matchesPrefix("Lincoln", ""));
        assertFalse(OfflinePlayerArgumentHandler.matchesPrefix("FainNeito", "neito"));
        assertFalse(OfflinePlayerArgumentHandler.matchesPrefix("Owner", "ownera"));
    }
}
