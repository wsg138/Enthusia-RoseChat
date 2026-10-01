package dev.rosewood.rosechat.command.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rosewood.rosechat.staff.StaffVisibilityPolicy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MessageCommandVisibilityTest {

    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Test
    void canonicalVisibilityUsesSubjectAndViewerInCorrectOrder() {
        assertFalse(StaffVisibilityPolicy.canSee(
                VIEWER,
                SUBJECT,
                false,
                true,
                true,
                (subjectId, viewerId) -> !subjectId.equals(SUBJECT) || !viewerId.equals(VIEWER)
        ));
    }

    @Test
    void authorizedViewerCanAddressVanishedSubject() {
        assertTrue(StaffVisibilityPolicy.canSee(VIEWER, SUBJECT, true, true, true,
                (subjectId, viewerId) -> true));
    }

    @Test
    void visibilityIsReevaluatedAfterVanishStateChanges() {
        AtomicBoolean visible = new AtomicBoolean(true);
        assertTrue(StaffVisibilityPolicy.canSee(VIEWER, SUBJECT, false, true, true,
                (subjectId, viewerId) -> visible.get()));
        visible.set(false);
        assertFalse(StaffVisibilityPolicy.canSee(VIEWER, SUBJECT, false, true, true,
                (subjectId, viewerId) -> visible.get()));
    }

    @Test
    void legacyMetadataRemainsFallbackWithoutStaffProvider() {
        assertFalse(StaffVisibilityPolicy.canSee(VIEWER, SUBJECT, true, false, false,
                (subjectId, viewerId) -> true));
        assertTrue(StaffVisibilityPolicy.canSee(VIEWER, SUBJECT, false, false, false,
                (subjectId, viewerId) -> false));
    }

    @Test
    void installedStaffProviderWithoutBridgeFailsClosed() {
        assertFalse(StaffVisibilityPolicy.canSee(VIEWER, SUBJECT, false, true, false,
                (subjectId, viewerId) -> true));
        assertFalse(StaffVisibilityPolicy.canSee(null, SUBJECT, false, true, false,
                (subjectId, viewerId) -> true));
    }

    @Test
    void subjectCanAlwaysAddressSelf() {
        assertTrue(StaffVisibilityPolicy.canSee(SUBJECT, SUBJECT, true, true, false,
                (subjectId, viewerId) -> false));
    }

    @Test
    void unverifiableRemoteViewerFailsClosedWithCanonicalVisibility() {
        assertFalse(StaffVisibilityPolicy.canSee(null, SUBJECT, false, true, true,
                (subjectId, viewerId) -> true));
    }

    @Test
    void unverifiableRemoteViewerUsesLegacyFallbackWithoutStaffProvider() {
        assertTrue(StaffVisibilityPolicy.canSee(null, SUBJECT, false, false, false,
                (subjectId, viewerId) -> false));
        assertFalse(StaffVisibilityPolicy.canSee(null, SUBJECT, true, false, false,
                (subjectId, viewerId) -> true));
    }
}
