package dev.rosewood.rosechat.api.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutomatedModerationContractTest {
    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FIRST_EVENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SECOND_EVENT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant FIRST_AT = Instant.parse("2026-09-30T12:00:00Z");
    private static final String CATEGORY = "harassment";
    private static final String MESSAGE = "message";

    @Test
    void evidencePreservesExactMessageAndNormalizesCategory() {
        AutomatedModerationEvidence evidence = new AutomatedModerationEvidence(
                FIRST_EVENT,
                FIRST_AT,
                "  exact message spacing  ",
                "  harassment  ",
                0.875D,
                82
        );

        assertEquals("  exact message spacing  ", evidence.message());
        assertEquals(CATEGORY, evidence.category());
        assertEquals(0.875D, evidence.confidence());
        assertEquals(82, evidence.severity());
    }

    @Test
    void evidenceRejectsControlCharactersAndInvalidScores() {
        assertThrows(IllegalArgumentException.class, () -> evidence("bad\nmessage", 0.5D, 50));
        assertThrows(IllegalArgumentException.class, () -> evidence(MESSAGE, Double.NaN, 50));
        assertThrows(IllegalArgumentException.class, () -> evidence(MESSAGE, 1.01D, 50));
        assertThrows(IllegalArgumentException.class, () -> evidence(MESSAGE, 0.5D, 101));
        assertThrows(IllegalArgumentException.class, () -> new AutomatedModerationEvidence(
                FIRST_EVENT, FIRST_AT, MESSAGE, "bad\tcategory", 0.5D, 50));
    }

    @Test
    void requestDefensivelyCopiesEvidenceAndRequiresExactCount() {
        List<AutomatedModerationEvidence> mutable = new ArrayList<>(List.of(
                evidence("first", 0.75D, 70),
                new AutomatedModerationEvidence(
                        SECOND_EVENT, FIRST_AT.plusSeconds(30), "second", CATEGORY, 0.9D, 90)
        ));

        AutomatedPublicMuteRequest request = request(2, mutable);
        mutable.clear();

        assertEquals(2, request.evidence().size());
        assertThrows(IllegalArgumentException.class, () -> request(1, request.evidence()));
        assertThrows(IllegalArgumentException.class, () -> request(3, request.evidence()));
    }

    private static AutomatedModerationEvidence evidence(String message, double confidence, int severity) {
        return new AutomatedModerationEvidence(
                FIRST_EVENT,
                FIRST_AT,
                message,
                CATEGORY,
                confidence,
                severity
        );
    }

    private static AutomatedPublicMuteRequest request(
            int strikeCount,
            List<AutomatedModerationEvidence> evidence
    ) {
        return new AutomatedPublicMuteRequest(
                TARGET_ID,
                "Player",
                SECOND_EVENT,
                CATEGORY,
                90,
                strikeCount,
                Duration.ofDays(30),
                "rosechat-ai:" + SECOND_EVENT,
                evidence
        );
    }
}
