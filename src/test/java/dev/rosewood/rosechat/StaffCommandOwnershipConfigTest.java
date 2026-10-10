package dev.rosewood.rosechat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StaffCommandOwnershipConfigTest {

    @Test
    void defaultStaffChannelDoesNotClaimBareStaffCommand() throws IOException {
        String channels = readChannelsConfiguration();

        assertTrue(channels.contains("\nstaff:\n"), "The default Staff channel must remain available through /c staff");
        assertFalse(
                channels.contains("# Creates a command alias, /staff")
                        || channels.contains("\n  commands:\n    - staff\n"),
                "RoseChat must not register bare /staff; EnthusiaStaff owns that command"
        );
    }

    private static String readChannelsConfiguration() throws IOException {
        try (InputStream stream = StaffCommandOwnershipConfigTest.class.getResourceAsStream("/channels.yml")) {
            if (stream == null) {
                throw new IOException("channels.yml is missing from test resources");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
