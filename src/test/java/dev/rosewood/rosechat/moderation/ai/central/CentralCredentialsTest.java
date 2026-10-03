package dev.rosewood.rosechat.moderation.ai.central;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rosewood.rosechat.moderation.ai.AiModerationConfig;
import dev.rosewood.rosechat.moderation.ai.AiModerationTestConfig;
import org.junit.jupiter.api.Test;

/**
 * Required test 18 (credentials are never logged), credential-resolution half.
 */
class CentralCredentialsTest {

    @Test
    void resolvesClientIdFromConfigAndTokenFromEnv() {
        AiModerationConfig config = AiModerationTestConfig.create();
        CentralCredentials credentials = CentralCredentials.resolve(config,
                name -> "ROSECHAT_MODERATION_TOKEN".equals(name) ? "super-secret-token" : null);

        assertTrue(credentials.complete());
        assertEquals("rosechat-test", credentials.clientId());
        assertEquals("super-secret-token", credentials.token());
    }

    @Test
    void resolvesClientIdFromEnvWhenConfigIsBlank() {
        AiModerationConfig config = AiModerationTestConfig.createCentralDisabled();
        CentralCredentials credentials = CentralCredentials.resolve(config, name -> switch (name) {
            case "ROSECHAT_MODERATION_CLIENT_ID" -> "env-client";
            case "ROSECHAT_MODERATION_TOKEN" -> "env-token";
            default -> null;
        });

        assertTrue(credentials.complete());
        assertEquals("env-client", credentials.clientId());
    }

    @Test
    void incompleteWhenTokenMissing() {
        AiModerationConfig config = AiModerationTestConfig.create();
        CentralCredentials credentials = CentralCredentials.resolve(config, name -> null);

        assertFalse(credentials.complete());
        assertTrue(credentials.safeSummary().contains("MISSING"));
    }

    @Test
    void safeSummaryNeverContainsSecretValues() {
        AiModerationConfig config = AiModerationTestConfig.create();
        CentralCredentials credentials = CentralCredentials.resolve(config,
                name -> "ROSECHAT_MODERATION_TOKEN".equals(name) ? "super-secret-token-abc123" : null);

        String summary = credentials.safeSummary();
        assertFalse(summary.contains("super-secret-token-abc123"),
                "credential values must never appear in diagnostics: " + summary);
        assertTrue(summary.contains("env:ROSECHAT_MODERATION_TOKEN"),
                "diagnostics must describe the credential source instead");
    }

    @Test
    void tokenIsNeverReadFromYaml() {
        AiModerationConfig config = AiModerationTestConfig.create();
        CentralCredentials credentials = CentralCredentials.resolve(config, name -> null);
        assertFalse(credentials.complete(),
                "with no environment token there is no fallback to any file value");
    }
}
