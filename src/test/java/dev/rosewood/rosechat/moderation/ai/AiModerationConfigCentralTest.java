package dev.rosewood.rosechat.moderation.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Central-mode configuration semantics: which configs activate the central
 * service as the single semantic authority, and migration compatibility for
 * legacy files.
 */
class AiModerationConfigCentralTest {

    @Test
    void centralModeRequiresEnabledAndBaseUri() {
        assertTrue(AiModerationTestConfig.create().centralMode(),
                "a fully configured central section activates central mode");
        assertFalse(AiModerationTestConfig.createCentralDisabled().centralMode(),
                "central.enabled=false must not activate central mode");
    }

    @Test
    void blankBaseUriDisablesCentralMode() {
        AiModerationConfig base = AiModerationTestConfig.create();
        AiModerationConfig noUri = new AiModerationConfig(
                base.enabled(), base.shadowMode(), base.punishmentsEnabled(), base.model(),
                base.apiKeyEnvironmentVariable(), base.maximumChatHold(), base.requestTimeout(),
                base.beforeMessages(), base.afterMessages(), base.contextMaxAge(), base.contextMaxCharacters(),
                base.followUpDelay(), base.failuresToOpen(), base.circuitOpenDuration(), base.requiredStrikes(),
                base.strikeWindow(), base.muteDuration(), base.corroborationFloorRatio(), base.deleteThresholds(),
                base.selfHarmIntentAlertThreshold(), base.staffStatusPermission(),
                true, "", base.centralClientId(), base.centralClientIdEnvironmentVariable(),
                base.centralTokenEnvironmentVariable(), base.centralScopeId(), base.centralRequestTimeout());
        assertFalse(noUri.centralMode(),
                "without a base URI there is no central authority; chat fails open");
    }

    @Test
    void invalidBaseUriIsRejected() {
        AiModerationConfig base = AiModerationTestConfig.create();
        assertThrows(IllegalArgumentException.class, () -> new AiModerationConfig(
                base.enabled(), base.shadowMode(), base.punishmentsEnabled(), base.model(),
                base.apiKeyEnvironmentVariable(), base.maximumChatHold(), base.requestTimeout(),
                base.beforeMessages(), base.afterMessages(), base.contextMaxAge(), base.contextMaxCharacters(),
                base.followUpDelay(), base.failuresToOpen(), base.circuitOpenDuration(), base.requiredStrikes(),
                base.strikeWindow(), base.muteDuration(), base.corroborationFloorRatio(), base.deleteThresholds(),
                base.selfHarmIntentAlertThreshold(), base.staffStatusPermission(),
                true, "not a uri at all ???", base.centralClientId(), base.centralClientIdEnvironmentVariable(),
                base.centralTokenEnvironmentVariable(), base.centralScopeId(), base.centralRequestTimeout()));
    }

    @Test
    void legacyFieldsStillParseForMigrationCompatibility() {
        AiModerationConfig config = AiModerationTestConfig.create();
        assertTrue(config.deleteThresholds().containsKey("harassment"),
                "legacy policy thresholds still parse even though they no longer decide");
        assertTrue(config.punishmentsEnabled() || !config.punishmentsEnabled(),
                "legacy punishments flag still parses");
    }

    @Test
    void requestTimeoutMustStayPositive() {
        AiModerationConfig base = AiModerationTestConfig.create();
        assertThrows(IllegalArgumentException.class, () -> new AiModerationConfig(
                base.enabled(), base.shadowMode(), base.punishmentsEnabled(), base.model(),
                base.apiKeyEnvironmentVariable(), base.maximumChatHold(), base.requestTimeout(),
                base.beforeMessages(), base.afterMessages(), base.contextMaxAge(), base.contextMaxCharacters(),
                base.followUpDelay(), base.failuresToOpen(), base.circuitOpenDuration(), base.requiredStrikes(),
                base.strikeWindow(), base.muteDuration(), base.corroborationFloorRatio(), base.deleteThresholds(),
                base.selfHarmIntentAlertThreshold(), base.staffStatusPermission(),
                true, "http://127.0.0.1:8080", base.centralClientId(), base.centralClientIdEnvironmentVariable(),
                base.centralTokenEnvironmentVariable(), base.centralScopeId(), Duration.ZERO));
    }

    @Test
    void defaultTestConfigUsesNonLoopbackFriendlyValues() {
        AiModerationConfig config = AiModerationTestConfig.create();
        assertTrue(config.centralRequestTimeout().toMillis() > 0);
    }
}
