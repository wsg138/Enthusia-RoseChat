package dev.rosewood.rosechat.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rosewood.rosechat.api.staff.BridgeRegistration;
import dev.rosewood.rosechat.api.staff.PresenceContext;
import dev.rosewood.rosechat.api.staff.PresenceType;
import dev.rosewood.rosechat.api.staff.RoseChatModerationBridge;
import dev.rosewood.rosechat.api.staff.StaffChannelConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class StaffPluginLoadOrderContractTest {
    private static final UUID SUBJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");

    @Test
    void descriptorDoesNotOrderRoseChatAfterEnthusiaStaff() throws IOException {
        String descriptor = Files.readString(Path.of("src/main/resources/plugin.yml"));
        boolean hasStaffDependency = descriptor.lines()
                .map(String::trim)
                .anyMatch("- EnthusiaStaff"::equals);

        assertFalse(
                hasStaffDependency,
                "RoseChat must not declare an EnthusiaStaff dependency edge; Staff registers through the service later"
        );
    }

    @Test
    void canonicalBridgeCanRegisterAfterProviderlessStartup() {
        StaffBridgeCoordinator coordinator = new StaffBridgeCoordinator(
                Logger.getLogger(StaffPluginLoadOrderContractTest.class.getName()),
                context -> true
        );
        PresenceContext presence = new PresenceContext(SUBJECT_ID, VIEWER_ID, PresenceType.JOIN);

        assertTrue(coordinator.owner().isEmpty());
        assertTrue(coordinator.canRenderPresence(presence));

        BridgeRegistration registration = coordinator.install(
                "EnthusiaStaff",
                new StaffChannelConfiguration("staff", "global", Set.of()),
                new RoseChatModerationBridge() {
                    @Override
                    public boolean canRenderPresence(PresenceContext context) {
                        return false;
                    }
                }
        );

        assertTrue(registration.isActive());
        assertEquals(Optional.of("EnthusiaStaff"), coordinator.owner());
        assertFalse(coordinator.canRenderPresence(presence));
    }
}
