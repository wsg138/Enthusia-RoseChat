package dev.rosewood.rosechat.moderation.ai.central;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.rosewood.rosechat.api.staff.ChannelClassification;
import org.junit.jupiter.api.Test;

/**
 * Required tests 16 (private/public profile mapping) and 17 (staff/exempt
 * channel bypass).
 */
class ChannelProfileTest {

    @Test
    void publicChannelsMapToMinecraftPublic() {
        assertEquals(ChannelProfile.MINECRAFT_PUBLIC,
                ChannelProfile.forClassification(ChannelClassification.PUBLIC));
        assertEquals("minecraft_public", ChannelProfile.MINECRAFT_PUBLIC.centralName());
    }

    @Test
    void privateChannelsMapToMinecraftPrivate() {
        assertEquals(ChannelProfile.MINECRAFT_PRIVATE,
                ChannelProfile.forClassification(ChannelClassification.PRIVATE));
        assertEquals("minecraft_private", ChannelProfile.MINECRAFT_PRIVATE.centralName());
    }

    @Test
    void staffChannelsAreExemptAndBypassed() {
        assertEquals(ChannelProfile.EXEMPT,
                ChannelProfile.forClassification(ChannelClassification.STAFF));
        assertNull(ChannelProfile.EXEMPT.centralName(),
                "exempt surfaces must not emit a central profile string");
    }

    @Test
    void exemptProfileCannotBuildARequest() {
        assertThrows(IllegalArgumentException.class, () -> new CentralModerationRequest(
                ChannelProfile.EXEMPT,
                "scope",
                "channel",
                "",
                "ext-1",
                "canon-1",
                java.util.UUID.randomUUID(),
                java.util.List.of(),
                java.time.Instant.now(),
                "staff only text",
                ""
        ), "exempt text must never be submitted to the central service");
    }

    @Test
    void classificationMappingRejectsNull() {
        assertThrows(NullPointerException.class, () -> ChannelProfile.forClassification(null));
    }
}
