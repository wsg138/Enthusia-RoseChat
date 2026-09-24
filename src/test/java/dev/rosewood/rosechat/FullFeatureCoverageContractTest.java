package dev.rosewood.rosechat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.List;
import org.junit.jupiter.api.Test;

class FullFeatureCoverageContractTest {

    private static final List<String> REQUIRED_TESTS = List.of(
            "dev.rosewood.rosechat.api.staff.ModerationDecisionTest",
            "dev.rosewood.rosechat.api.staff.StaffChannelConfigurationTest",
            "dev.rosewood.rosechat.api.staff.StaffContextContractTest",
            "dev.rosewood.rosechat.chat.FilterWarningTest",
            "dev.rosewood.rosechat.chat.channel.ChannelConcurrencyTest",
            "dev.rosewood.rosechat.manager.EnthusiaStaffCommandCompatibilityTest",
            "dev.rosewood.rosechat.placeholder.condition.OperatorTest",
            "dev.rosewood.rosechat.staff.StaffBridgeCoordinatorTest"
    );

    @Test
    void maintainedRegressionSuitesRemainPresent() {
        for (String className : REQUIRED_TESTS) {
            assertDoesNotThrow(
                    () -> Class.forName(className),
                    () -> "Required RoseChat regression suite is missing: " + className
            );
        }
    }
}
