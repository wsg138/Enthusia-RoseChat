package dev.rosewood.rosechat.api.chatbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Binary contract exercised by EnthusiaStaff Paper's optional rich Discord relay.
 * This must remain source/binary compatible independently of internal RoseChat changes.
 * No Discord credentials, Minecraft server or gateway session is needed to verify it.
 */
class StaffBotStyledOutboundApiCompatibilityTest {
    @Test
    void staffBotCanInstallStyledAndPlainOutboundBridgesByExactMethodSignature()
            throws IOException {
        // RoseChatAPI depends on Bukkit and cannot be class-loaded in a plain
        // CI unit-test JVM. Inspect its class-file method table instead.
        Set<String> signatures = compiledApiMethodSignatures();
        assertTrue(signatures.contains(
                "installOutboundChatRenderBridge:"
                + "(Ldev/rosewood/rosechat/api/chatbridge/OutboundChatRenderBridge;)"
                + "Ldev/rosewood/rosechat/api/chatbridge/OutboundChatRenderBridgeCoordinator$Registration;"));
        assertTrue(signatures.contains(
                "installOutboundChatBridge:"
                + "(Ldev/rosewood/rosechat/api/chatbridge/OutboundChatBridge;)"
                + "Ldev/rosewood/rosechat/api/chatbridge/OutboundChatBridgeCoordinator$Registration;"));
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

    private static Set<String> compiledApiMethodSignatures() throws IOException {
        String resource = "dev/rosewood/rosechat/api/RoseChatAPI.class";
        try (InputStream bytes = StaffBotStyledOutboundApiCompatibilityTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(bytes, "RoseChatAPI class file must be compiled before ABI tests");
            DataInputStream input = new DataInputStream(bytes);
            assertEquals(0xCAFEBABE, input.readInt());
            input.readUnsignedShort(); // minor
            input.readUnsignedShort(); // major
            int poolCount = input.readUnsignedShort();
            String[] pool = new String[poolCount];
            for (int i = 1; i < poolCount; i++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> pool[i] = input.readUTF();
                    case 3, 4 -> input.skipNBytes(4);
                    case 5, 6 -> {
                        input.skipNBytes(8);
                        i++; // double-width constant pool slot
                    }
                    case 7, 8, 16, 19, 20 -> input.skipNBytes(2);
                    case 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                    case 15 -> input.skipNBytes(3);
                    default -> throw new IOException("unsupported class file constant type");
                }
            }
            input.skipNBytes(6); // class access flags, this_class, super_class
            int interfaces = input.readUnsignedShort();
            input.skipNBytes(2L * interfaces);
            int fields = input.readUnsignedShort();
            for (int i = 0; i < fields; i++) {
                skipMember(input);
            }
            int methods = input.readUnsignedShort();
            Set<String> signatures = new HashSet<>();
            for (int i = 0; i < methods; i++) {
                input.readUnsignedShort(); // access flags
                int name = input.readUnsignedShort();
                int descriptor = input.readUnsignedShort();
                signatures.add(pool[name] + ":" + pool[descriptor]);
                skipAttributes(input);
            }
            return signatures;
        }
    }

    private static void skipMember(DataInputStream input) throws IOException {
        input.skipNBytes(6); // access, name, descriptor
        skipAttributes(input);
    }

    private static void skipAttributes(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            input.readUnsignedShort(); // attribute name constant
            long length = Integer.toUnsignedLong(input.readInt());
            input.skipNBytes(length);
        }
    }
}
