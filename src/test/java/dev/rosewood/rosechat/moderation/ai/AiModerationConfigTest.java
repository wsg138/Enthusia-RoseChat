package dev.rosewood.rosechat.moderation.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiModerationConfigTest {

    @Test
    void acceptsTheStaffAutomaticMuteContract() {
        assertDoesNotThrow(() -> config(
                2,
                Duration.ofHours(1),
                Duration.ofDays(30)
        ));
    }

    @Test
    void acceptsPunishmentDisabledRollout() {
        assertDoesNotThrow(() -> new AiModerationConfig(
                true,
                false,
                false,
                "omni-moderation-2024-09-26",
                "OPENAI_API_KEY",
                Duration.ofMillis(200),
                Duration.ofSeconds(2),
                6,
                3,
                Duration.ofSeconds(45),
                3500,
                Duration.ofMillis(1500),
                3,
                Duration.ofSeconds(60),
                2,
                Duration.ofHours(1),
                Duration.ofDays(30),
                0.75D,
                Map.of("harassment", 0.92D),
                0.55D,
                "rosechat.seeblocked",
                true,
                "http://127.0.0.1:8080",
                "rosechat-test",
                "ROSECHAT_MODERATION_CLIENT_ID",
                "ROSECHAT_MODERATION_TOKEN",
                "test-scope",
                Duration.ofSeconds(2)
        ));
    }

    @Test
    void rejectsAChangedStrikeCount() {
        assertThrows(IllegalArgumentException.class, () -> config(
                3,
                Duration.ofHours(1),
                Duration.ofDays(30)
        ));
    }

    @Test
    void rejectsAChangedStrikeWindow() {
        assertThrows(IllegalArgumentException.class, () -> config(
                2,
                Duration.ofHours(2),
                Duration.ofDays(30)
        ));
    }

    @Test
    void rejectsAChangedMuteDuration() {
        assertThrows(IllegalArgumentException.class, () -> config(
                2,
                Duration.ofHours(1),
                Duration.ofDays(7)
        ));
    }

    private static AiModerationConfig config(
            int requiredStrikes,
            Duration strikeWindow,
            Duration muteDuration
    ) {
        return new AiModerationConfig(
                true,
                false,
                true,
                "omni-moderation-2024-09-26",
                "OPENAI_API_KEY",
                Duration.ofMillis(200),
                Duration.ofSeconds(2),
                6,
                3,
                Duration.ofSeconds(45),
                3500,
                Duration.ofMillis(1500),
                3,
                Duration.ofSeconds(60),
                requiredStrikes,
                strikeWindow,
                muteDuration,
                0.75D,
                Map.of("harassment", 0.92D),
                0.55D,
                "rosechat.seeblocked",
                true,
                "http://127.0.0.1:8080",
                "rosechat-test",
                "ROSECHAT_MODERATION_CLIENT_ID",
                "ROSECHAT_MODERATION_TOKEN",
                "test-scope",
                Duration.ofSeconds(2)
        );
    }
}
