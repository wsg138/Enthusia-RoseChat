package dev.rosewood.rosechat.api.chatbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rosewood.rosechat.api.RoseChatAPI;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Binary contract exercised by EnthusiaStaff Paper's optional rich Discord relay.
 * This must remain source/binary compatible independently of internal RoseChat changes.
 * No Discord credentials, Minecraft server or gateway session is needed to verify it.
 */
class StaffBotStyledOutboundApiCompatibilityTest {
    @Test
    void staffBotCanInstallStyledAndPlainOutboundBridgesByExactMethodSignature()
            throws ReflectiveOperationException {
        Method styled = RoseChatAPI.class.getMethod(
                "installOutboundChatRenderBridge", OutboundChatRenderBridge.class);
        assertEquals(OutboundChatRenderBridgeCoordinator.Registration.class,
                styled.getReturnType());

        Method plain = RoseChatAPI.class.getMethod(
                "installOutboundChatBridge", OutboundChatBridge.class);
        assertEquals(OutboundChatBridgeCoordinator.Registration.class,
                plain.getReturnType());
    }

    @Test
    void renderedRecordMatchesStaffBotCompilerContractWithoutChangingFieldOrder() {
        assertTrue(RenderedOutboundChatMessage.class.isRecord());
        RecordComponent[] fields = RenderedOutboundChatMessage.class.getRecordComponents();
        assertEquals(7, fields.length);
        assertEquals(
                Arrays.asList("message", "bodyPlainText", "bodyMarkdown", "bodyAdventureJson",
                        "linePlainText", "lineMarkdown", "lineAdventureJson"),
                Arrays.stream(fields).map(RecordComponent::getName).toList());
        assertEquals(OutboundChatMessage.class, fields[0].getType());
        for (int index = 1; index < fields.length; index++) {
            assertEquals(String.class, fields[index].getType());
        }
        assertEquals(void.class,
                Arrays.stream(OutboundChatRenderBridge.class.getMethods())
                        .filter(method -> method.getName().equals("publish"))
                        .findFirst().orElseThrow().getReturnType());
    }

    @Test
    void renderedPayloadPreservesCanonicalMessageIdentityAndPayloadContents() {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID sender = UUID.fromString("20000000-0000-0000-0000-000000000002");
        OutboundChatMessage base = new OutboundChatMessage(
                id, "rosechat-mc-" + id, "rosechat-canonical-" + id,
                10000L, 40000L, "global",
                dev.rosewood.rosechat.api.staff.ChannelClassification.PUBLIC,
                OutboundChatMessage.Origin.MINECRAFT, sender, "Player", "[item] hello");
        RenderedOutboundChatMessage rich = new RenderedOutboundChatMessage(
                base, "[item] hello", "**[item]** hello", "{\"text\":\"[item] hello\"}",
                "Player: [item] hello", "**Player:** [item] hello",
                "{\"text\":\"Player: [item] hello\"}");
        assertEquals(id, rich.message().eventId());
        assertEquals(sender, rich.message().minecraftPlayerId());
        assertEquals("global", rich.message().logicalChannelId());
        assertEquals("[item] hello", rich.bodyPlainText());
        assertEquals("Player: [item] hello", rich.linePlainText());
        assertFalse(rich.isExpired(10001L));
        assertNotNull(rich.message().canonicalMessageId());
    }
}
