package dev.rosewood.rosechat.chat.log;

import dev.rosewood.rosechat.config.Settings;
import dev.rosewood.rosechat.message.DeletableMessage;
import dev.rosewood.rosechat.message.MessageUtils;
import dev.rosewood.rosechat.message.tokenizer.composer.ChatComposer;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import net.md_5.bungee.api.chat.BaseComponent;

public class PlayerMessageLog extends ConsoleMessageLog {

    private UUID owner;
    private final List<DeletableMessage> deletableMessages;
    private int cleanupAmount;

    public PlayerMessageLog(UUID sender) {
        super();

        this.owner = sender;
        this.deletableMessages = Collections.synchronizedList(new LinkedList<>());
    }

    /**
     * Checks the current spam history without mutating it. This is used before remote AI moderation
     * so messages the local spam filter is already certain to reject do not consume API requests.
     */
    public boolean wouldMessageBeSpam(String messageToAdd) {
        this.cleanupAmount = Settings.SPAM_MESSAGE_COUNT.get();
        if (this.cleanupAmount <= 1) {
            return false;
        }
        synchronized (this.messages) {
            if (this.messages.size() < this.cleanupAmount - 1) {
                return false;
            }
            int similarMessages = 1; // The candidate would match itself after insertion.
            int checked = Math.min(this.cleanupAmount - 1, this.messages.size());
            for (int i = 0; i < checked; i++) {
                String message = this.messages.get((this.messages.size() - 1) - i);
                double difference = MessageUtils.getLevenshteinDistancePercent(message, messageToAdd);
                if ((1 - difference) <= (Settings.SPAM_FILTER_SENSITIVITY.get() / 100.0D)) {
                    similarMessages++;
                }
            }
            return similarMessages >= this.cleanupAmount;
        }
    }

    /**
     * @param messageToAdd The message that was sent.
     * @return True if it is seen as spam.
     */
    public boolean addMessageWithSpamCheck(String messageToAdd) {
        this.cleanupAmount = Settings.SPAM_MESSAGE_COUNT.get();

        synchronized (this.messages) {
            this.addMessage(messageToAdd);

            int similarMessages = 0;

            if (this.messages.size() > this.cleanupAmount - 1) {
                for (int i = 0; i < this.cleanupAmount; i++) {
                    String message = this.messages.get((this.messages.size() - 1) - i);
                    double difference = MessageUtils.getLevenshteinDistancePercent(message, messageToAdd);

                    if ((1 - difference) <= (Settings.SPAM_FILTER_SENSITIVITY.get() / 100.0D))
                        similarMessages++;
                }

                if (this.messages.size() > this.cleanupAmount * 2)
                    this.messages.subList(0, this.cleanupAmount).clear();

                return similarMessages >= this.cleanupAmount;
            }

            return false;
        }
    }

    public UUID getOwner() {
        return this.owner;
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
    }

    public List<DeletableMessage> getDeletableMessages() {
        return this.deletableMessages;
    }

    public DeletableMessage getDeletableMessage(String json) {
        if (this.deletableMessages.isEmpty())
            return null;

        BaseComponent[] one = ChatComposer.decorated().composeJson(json);
        if (one.length == 0)
            return null;
        BaseComponent componentOne = one[0];

        for (int i = this.deletableMessages.size() - 1; i >= 0; i--) {
            DeletableMessage message = this.deletableMessages.get(i);

            BaseComponent[] two = ChatComposer.decorated().composeJson(message.getJson());
            if (two.length == 0)
                continue;

            BaseComponent componentTwo = two[0];

            if (componentOne.toLegacyText().equalsIgnoreCase(componentTwo.toLegacyText()))
                return message;
        }

        return null;
    }

    public void addDeletableMessage(DeletableMessage deletableMessage) {
        if (this.deletableMessages.size() >= 100)
            this.deletableMessages.remove(0);

        this.deletableMessages.add(deletableMessage);
    }

    public int getCleanupAmount() {
        return this.cleanupAmount;
    }

    public void setCleanupAmount(int cleanupAmount) {
        this.cleanupAmount = cleanupAmount;
    }

}
