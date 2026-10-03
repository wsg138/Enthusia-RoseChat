package dev.rosewood.rosechat.api.staff;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoseChatModerationBridgeDefaultsTest {

    @Test
    void visibilityDefaultsFailClosedUntilProviderImplementsAuthority() {
        RoseChatModerationBridge bridge = new RoseChatModerationBridge() { };
        PresenceContext context = new PresenceContext(
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                UUID.fromString("00000000-0000-0000-0000-000000000202"),
                PresenceType.JOIN
        );

        assertFalse(bridge.canRenderPresence(context));
    }
}
