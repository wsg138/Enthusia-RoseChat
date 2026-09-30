package dev.rosewood.rosechat.moderation.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AiModerationPolicyTest {
    private final AiModerationPolicy policy = new AiModerationPolicy(AiModerationTestConfig.create());

    @Test
    void genericMinecraftViolenceDoesNotDeleteOrRequestFollowUp() {
        AiModerationPolicy.Verdict verdict = policy.evaluate(batch(
                true,
                Map.of("violence", 0.999),
                Map.of("violence", 0.999)
        ));

        assertEquals(AiModerationPolicy.Action.ALLOW, verdict.action());
        assertEquals("violence", verdict.category());
        assertFalse(verdict.followUpUseful());
    }

    @Test
    void threateningHarassmentDeletes() {
        AiModerationPolicy.Verdict verdict = policy.evaluate(batch(
                true,
                Map.of("harassment/threatening", 0.91),
                Map.of("harassment/threatening", 0.92)
        ));

        assertEquals(AiModerationPolicy.Action.DELETE, verdict.action());
        assertEquals("harassment/threatening", verdict.category());
        assertFalse(verdict.followUpUseful());
    }

    @Test
    void toxicNeighborsCannotConvictCleanTarget() {
        AiModerationPolicy.Verdict verdict = policy.evaluate(batch(
                false,
                Map.of("harassment/threatening", 0.10),
                Map.of("harassment/threatening", 0.99)
        ));

        assertEquals(AiModerationPolicy.Action.ALLOW, verdict.action());
        assertFalse(verdict.followUpUseful());
    }

    @Test
    void borderlineEnforceableCategoryRequestsFollowUpContext() {
        AiModerationPolicy.Verdict verdict = policy.evaluate(batch(
                false,
                Map.of("harassment/threatening", 0.50),
                Map.of("harassment/threatening", 0.50)
        ));

        assertEquals(AiModerationPolicy.Action.ALLOW, verdict.action());
        assertTrue(verdict.followUpUseful());
    }

    @Test
    void contextCanCorroborateAlreadyBorderlineTarget() {
        AiModerationPolicy.Verdict verdict = policy.evaluate(batch(
                true,
                Map.of("harassment/threatening", 0.65),
                Map.of("harassment/threatening", 0.90)
        ));

        assertEquals(AiModerationPolicy.Action.DELETE, verdict.action());
    }

    @Test
    void selfHarmIntentAlertsInsteadOfSilentlyDeleting() {
        AiModerationPolicy.Verdict verdict = policy.evaluate(batch(
                true,
                Map.of("self-harm/intent", 0.80),
                Map.of("self-harm/intent", 0.85)
        ));

        assertEquals(AiModerationPolicy.Action.ALERT_ONLY, verdict.action());
        assertEquals("self-harm/intent", verdict.category());
    }

    private static OpenAiModerationClient.BatchResult batch(
            boolean flagged,
            Map<String, Double> target,
            Map<String, Double> context
    ) {
        return new OpenAiModerationClient.BatchResult(
                new ModerationScores(flagged, Map.of(), target),
                new ModerationScores(flagged, Map.of(), context)
        );
    }
}
