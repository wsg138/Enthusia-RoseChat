package dev.rosewood.rosechat.command.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageCommandVisibilityTest {

    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Test
    void hiddenTargetUsesVisibilityWithSubjectAndViewerInTheCorrectOrder() {
        assertTrue(MessageCommand.targetHidden(
                VIEWER,
                SUBJECT,
                (subjectId, viewerId) -> !subjectId.equals(SUBJECT) || !viewerId.equals(VIEWER)
        ));
    }

    @Test
    void visibleTargetRemainsAddressable() {
        assertFalse(MessageCommand.targetHidden(VIEWER, SUBJECT, (subjectId, viewerId) -> true));
    }
}
