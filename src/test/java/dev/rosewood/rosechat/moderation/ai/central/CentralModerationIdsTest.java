package dev.rosewood.rosechat.moderation.ai.central;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Required tests 11 (retry preserves external/canonical IDs) and 12 (mirrored
 * canonical ID is stable and provider-neutral).
 */
class CentralModerationIdsTest {

    @Test
    void externalIdIsStableForTheSameEvent() {
        UUID eventId = UUID.randomUUID();
        assertEquals(CentralModerationIds.externalMessageId(eventId),
                CentralModerationIds.externalMessageId(eventId),
                "retrying the same logical message must reuse the same external_message_id");
    }

    @Test
    void externalIdIsUniquePerEvent() {
        assertNotEquals(CentralModerationIds.externalMessageId(UUID.randomUUID()),
                CentralModerationIds.externalMessageId(UUID.randomUUID()));
    }

    @Test
    void canonicalIdIsStableForTheSameEvent() {
        UUID eventId = UUID.randomUUID();
        assertEquals(CentralModerationIds.canonicalMessageId(eventId),
                CentralModerationIds.canonicalMessageId(eventId));
    }

    @Test
    void eventIdRoundTripsThroughExternalId() {
        UUID eventId = UUID.randomUUID();
        assertEquals(eventId,
                CentralModerationIds.eventIdFromExternal(CentralModerationIds.externalMessageId(eventId)));
    }

    @Test
    void foreignExternalIdsDoNotRoundTrip() {
        assertNull(CentralModerationIds.eventIdFromExternal("discord-123"));
        assertNull(CentralModerationIds.eventIdFromExternal("rosechat-mc-not-a-uuid"));
        assertNull(CentralModerationIds.eventIdFromExternal(null));
    }

    @Test
    void mirrorAliasesShareOneCanonicalId() {
        CentralModerationIds.IdRegistry registry = new CentralModerationIds.IdRegistry();
        UUID eventId = UUID.randomUUID();
        String canonical = CentralModerationIds.canonicalMessageId(eventId);
        String minecraftExternal = CentralModerationIds.externalMessageId(eventId);
        String discordExternal = "discord-mirror-" + eventId;

        registry.registerAlias(canonical, minecraftExternal);
        registry.registerAlias(canonical, discordExternal);

        List<String> aliases = registry.aliasesFor(canonical);
        assertTrue(aliases.contains(minecraftExternal));
        assertTrue(aliases.contains(discordExternal));
        assertEquals(2, aliases.size(), "one canonical event, two platform copies, no duplicates");
    }

    @Test
    void publishedExternalIdResolvesToTheExactMessageUuid() {
        CentralModerationIds.IdRegistry registry = new CentralModerationIds.IdRegistry();
        String external = CentralModerationIds.externalMessageId(UUID.randomUUID());
        UUID messageId = UUID.randomUUID();

        assertNull(registry.resolveMessageId(external), "unknown IDs must not resolve to a guess");
        registry.registerPublished(external, messageId);
        assertEquals(messageId, registry.resolveMessageId(external));
    }

    @Test
    void registryIsBounded() {
        CentralModerationIds.IdRegistry registry = new CentralModerationIds.IdRegistry();
        for (int i = 0; i < 5_000; i++) {
            registry.registerPublished(CentralModerationIds.externalMessageId(UUID.randomUUID()), UUID.randomUUID());
        }
        assertTrue(registry.size() <= 4096, "registry must stay bounded, was " + registry.size());
    }
}
