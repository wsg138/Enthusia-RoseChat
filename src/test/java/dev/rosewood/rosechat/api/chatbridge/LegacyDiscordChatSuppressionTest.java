package dev.rosewood.rosechat.api.chatbridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LegacyDiscordChatSuppressionTest {

    @Test
    void registrationSuppressesUntilOwningRegistrationCloses() {
        LegacyDiscordChatSuppression suppression = new LegacyDiscordChatSuppression();

        assertFalse(suppression.suppressed());
        LegacyDiscordChatSuppression.Registration registration = suppression.suppress();
        assertTrue(suppression.suppressed());

        assertThrows(IllegalStateException.class, suppression::suppress);

        registration.close();
        assertFalse(suppression.suppressed());
    }

    @Test
    void staleRegistrationCannotReleaseNewOwner() {
        LegacyDiscordChatSuppression suppression = new LegacyDiscordChatSuppression();
        LegacyDiscordChatSuppression.Registration first = suppression.suppress();
        first.close();

        LegacyDiscordChatSuppression.Registration second = suppression.suppress();
        first.close();
        assertTrue(suppression.suppressed());

        second.close();
        assertFalse(suppression.suppressed());
    }

    @Test
    void coordinatorCloseRestoresUnsuppressedState() {
        LegacyDiscordChatSuppression suppression = new LegacyDiscordChatSuppression();
        suppression.suppress();
        suppression.close();

        assertFalse(suppression.suppressed());
    }
}
