package dev.rosewood.rosechat.api.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ModerationDecisionTest {

    @Test
    void factoriesExposeStableActionsAndFeedback() {
        ModerationDecision allow = ModerationDecision.allow();
        ModerationDecision block = ModerationDecision.block("reason");
        ModerationDecision staffOnly = ModerationDecision.staffOnly();

        assertEquals(ModerationDecision.Action.ALLOW, allow.action());
        assertEquals("", allow.feedback());
        assertEquals(ModerationDecision.Action.BLOCK, block.action());
        assertEquals("reason", block.feedback());
        assertEquals(ModerationDecision.Action.STAFF_ONLY, staffOnly.action());
        assertEquals("", staffOnly.feedback());
    }

    @Test
    void nullFeedbackNormalizesToEmptyString() {
        ModerationDecision decision = new ModerationDecision(ModerationDecision.Action.BLOCK, null);
        assertEquals("", decision.feedback());
    }

    @Test
    void nullActionIsRejected() {
        assertThrows(NullPointerException.class, () -> new ModerationDecision(null, "reason"));
    }
}
