package dev.rosewood.rosechat.api.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

class StaffChannelConfigurationTest {

    @Test
    void normalizesPrivateChannelIds() {
        StaffChannelConfiguration configuration = new StaffChannelConfiguration(
                " staff ",
                " global ",
                Set.of(" Reports ", "MOD")
        );

        assertEquals("staff", configuration.staffChannelId());
        assertEquals("global", configuration.globalChannelId());
        assertEquals(Set.of("reports", "mod"), configuration.privateChannelIds());
    }

    @Test
    void rejectsConflictingStaffAndGlobalChannels() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StaffChannelConfiguration("Staff", "staff", Set.of())
        );
    }

    @Test
    void rejectsBlankPrivateChannelIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StaffChannelConfiguration("staff", "global", Set.of(" "))
        );
    }
}
