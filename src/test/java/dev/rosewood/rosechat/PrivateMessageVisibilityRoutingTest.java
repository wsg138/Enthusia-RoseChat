package dev.rosewood.rosechat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PrivateMessageVisibilityRoutingTest {

    @Test
    void replyPathRechecksRecipientVisibility() throws IOException {
        String source = readSource("command/command/ReplyCommand.java");
        assertTrue(
                source.contains("StaffVisibilityPolicy.canSee(context.getSender(), target)"),
                "/reply must re-check current Staff visibility before local delivery"
        );
    }

    @Test
    void receivingBackendRechecksCrossServerRecipientVisibility() throws IOException {
        String source = readSource("listener/BungeeListener.java");
        assertTrue(
                source.contains("StaffVisibilityPolicy.canSee(senderUUID, target)"),
                "Cross-server PM delivery must re-check visibility on the recipient backend"
        );
    }

    @Test
    void finalDeliveryRechecksVisibilityAfterAsyncWork() throws IOException {
        String source = readSource("listener/PlayerListener.java");
        assertTrue(
                source.contains("event instanceof PlayerReceiveMessageEvent receiveEvent")
                        && source.contains("StaffVisibilityPolicy.canSee(sender.getUUID(), receiver.asPlayer())"),
                "Private-message delivery must re-check current visibility at the receive event boundary"
        );
    }

    @Test
    void completionFailsClosedWhenCanonicalStaffVisibilityIsRequired() throws IOException {
        String source = readSource("command/argument/OfflinePlayerArgumentHandler.java");
        assertTrue(
                source.contains("!StaffVisibilityPolicy.isCanonicalVisibilityRequired()"),
                "Bungee completion must stay disabled while the Staff provider requires canonical visibility"
        );
    }

    private static String readSource(String relativePath) throws IOException {
        return Files.readString(Path.of("src/main/java/dev/rosewood/rosechat").resolve(relativePath));
    }
}
