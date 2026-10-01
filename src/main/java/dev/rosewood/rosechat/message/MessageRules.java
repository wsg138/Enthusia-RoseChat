package dev.rosewood.rosechat.message;

import dev.rosewood.rosechat.api.RoseChatAPI;
import dev.rosewood.rosechat.api.event.message.MessageBlockedEvent;
import dev.rosewood.rosechat.api.event.message.MessageFilteredEvent;
import dev.rosewood.rosechat.chat.FilterWarning;
import dev.rosewood.rosechat.chat.filter.Filter;
import dev.rosewood.rosechat.config.Settings;
import dev.rosewood.rosegarden.utils.HexUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

public class MessageRules {

    private boolean filterCaps;
    private boolean filterSpam;
    private boolean filterURLs;
    private boolean filterLanguage;
    private boolean ignoreMessageLogging;

    public MessageRules applyCapsFilter() {
        this.filterCaps = true;
        return this;
    }

    public MessageRules applySpamFilter() {
        this.filterSpam = true;
        return this;
    }

    public MessageRules applyURLFilter() {
        this.filterURLs = true;
        return this;
    }

    public MessageRules applyLanguageFilter() {
        this.filterLanguage = true;
        return this;
    }

    public MessageRules applyAllFilters() {
        this.filterCaps = true;
        this.filterSpam = true;
        this.filterURLs = true;
        this.filterLanguage = true;
        return this;
    }

    public MessageRules ignoreMessageLogging() {
        this.ignoreMessageLogging = true;
        return this;
    }

    private boolean isCaps(RoseMessage message, RuleOutputs outputs) {
        if (!Settings.CAPS_CHECKING_ENABLED.get())
            return false;

        if (message.getSender().hasPermission("rosechat.caps." + message.getLocationPermission()))
            return false;

        int caps = 0;
        for (char c : outputs.getFilteredMessage().toCharArray()) {
            if (Character.isAlphabetic(c) && c == Character.toUpperCase(c))
                caps++;
        }

        return caps > Settings.MAXIMUM_CAPS_ALLOWED.get();
    }

    private void filterCaps(RoseMessage message, RuleOutputs outputs) {
        if (!Settings.CAPS_CHECKING_ENABLED.get())
            return;

        if (!this.isCaps(message, outputs))
            return;

        if (Settings.WARN_ON_CAPS_SENT.get())
            outputs.setWarning(FilterWarning.CAPS);

        if (Settings.LOWERCASE_CAPS_ENABLED.get()) {
            outputs.transformMessage(String::toLowerCase);
            return;
        }

        outputs.setBlocked(true);
    }

    private void filterSpam(RoseMessage message, RuleOutputs outputs) {
        if (!Settings.SPAM_CHECKING_ENABLED.get() || this.ignoreMessageLogging)
            return;

        if (message.getSender().hasPermission("rosechat.spam." + message.getLocationPermission())
                || message.getSender().getPlayerData() == null)
            return;

        if (!message.getSender().getPlayerData().getMessageLog().addMessageWithSpamCheck(outputs.getFilteredMessage()))
            return;

        if (Settings.WARN_ON_SPAM_SENT.get())
            outputs.setWarning(FilterWarning.SPAM);

        outputs.setBlocked(true);
    }

    private void filterURLs(RoseMessage message, RuleOutputs outputs) {
        if (!Settings.URL_CHECKING_ENABLED.get())
            return;

        if (message.getSender().hasPermission("rosechat.links." + message.getLocationPermission()))
            return;

        boolean hasURL = false;
        Matcher matcher = MessageUtils.URL_PATTERN.matcher(outputs.getFilteredMessage());
        while (matcher.find()) {
            String url = outputs.getFilteredMessage().substring(matcher.start(), matcher.end());
            outputs.transformMessage(x -> x.replace(url, ChatColor.STRIKETHROUGH +
                    url.replace(".", " ") + ChatColor.RESET));
            hasURL = true;
        }

        if (!hasURL)
            return;

        if (Settings.WARN_ON_URL_SENT.get())
            outputs.setWarning(FilterWarning.URL);

        if (!Settings.URL_CENSORING_ENABLED.get())
            outputs.setBlocked(true);
    }

    /**
     * Lenient deterministic fallback used alongside AI moderation. This deliberately avoids fuzzy
     * edit-distance matching, which caused innocent words such as "reward" and "restart" to collide
     * with configured blocked terms. Punctuation inside a token is removed so obvious bypasses such
     * as f.u.c.k still match an explicitly configured word.
     */
    private void filterLanguage(RoseMessage message, RuleOutputs outputs) {
        List<String> messageTokens = normalizedTokens(outputs.getFilteredMessage());
        if (messageTokens.isEmpty())
            return;

        for (Filter filter : RoseChatAPI.getInstance().getFilters()) {
            if (!filter.block() || !filter.hasPermission(message.getSender()))
                continue;

            for (String blocked : filter.matches()) {
                List<String> blockedTokens = normalizedTokens(blocked);
                if (blockedTokens.isEmpty() || !containsSequence(messageTokens, blockedTokens))
                    continue;

                if (filter.message() != null)
                    outputs.setWarningMessage(filter.message());

                if (filter.notifyStaff())
                    outputs.setNotifyStaff(true);

                outputs.setBlocked(true);
                outputs.getServerCommands().addAll(filter.serverCommands());
                outputs.getPlayerCommands().addAll(filter.playerCommands());
                return;
            }
        }
    }

    private static List<String> normalizedTokens(String input) {
        String stripped = ChatColor.stripColor(HexUtils.colorify(
                MessageUtils.stripAccents(input.toLowerCase())));
        List<String> result = new ArrayList<>();
        for (String raw : stripped.split("\\s+")) {
            String normalized = raw.replaceAll("[^a-z0-9]", "");
            if (!normalized.isBlank())
                result.add(normalized);
        }
        return result;
    }

    private static boolean containsSequence(List<String> input, List<String> wanted) {
        if (wanted.size() > input.size())
            return false;

        for (int start = 0; start <= input.size() - wanted.size(); start++) {
            boolean matches = true;
            for (int offset = 0; offset < wanted.size(); offset++) {
                if (!input.get(start + offset).equals(wanted.get(offset))) {
                    matches = false;
                    break;
                }
            }
            if (matches)
                return true;
        }
        return false;
    }

    public RuleOutputs apply(RoseMessage message, String originalMessage) {
        RuleOutputs outputs = new RuleOutputs(originalMessage);
        if (this.filterSpam)
            this.filterSpam(message, outputs);

        if (this.filterCaps)
            this.filterCaps(message, outputs);

        if (this.filterURLs)
            this.filterURLs(message, outputs);

        if (this.filterLanguage)
            this.filterLanguage(message, outputs);

        if (outputs.blocked) {
            MessageBlockedEvent messageBlockedEvent = new MessageBlockedEvent(message, originalMessage, outputs);
            Bukkit.getPluginManager().callEvent(messageBlockedEvent);
        }

        if (!outputs.getFilteredMessage().equals(originalMessage)) {
            MessageFilteredEvent messageFilteredEvent = new MessageFilteredEvent(message, originalMessage, outputs);
            Bukkit.getPluginManager().callEvent(messageFilteredEvent);
        }

        return outputs;
    }

    public RuleOutputs apply(RosePlayer rosePlayer, PermissionArea messageLocation, String originalMessage) {
        return this.apply(RoseMessage.forLocation(rosePlayer, messageLocation), originalMessage);
    }

    public boolean isIgnoringMessageLogging() {
        return this.ignoreMessageLogging;
    }

    public static class RuleOutputs {
        private boolean blocked;
        private FilterWarning warning;
        private String warningMessage;
        private String message;
        private boolean notifyStaff;
        private final List<String> serverCommands;
        private final List<String> playerCommands;

        public RuleOutputs(String message) {
            this.message = message;
            this.serverCommands = new ArrayList<>();
            this.playerCommands = new ArrayList<>();
        }

        public boolean isBlocked() {
            return this.blocked;
        }

        public void setBlocked(boolean blocked) {
            this.blocked = blocked;
        }

        public FilterWarning getWarning() {
            return this.warning;
        }

        public void setWarning(FilterWarning warning) {
            this.warning = warning;
        }

        public String getWarningMessage() {
            return this.warningMessage;
        }

        public void setWarningMessage(String warningMessage) {
            this.warningMessage = warningMessage;
        }

        public boolean shouldNotifyStaff() {
            return this.notifyStaff;
        }

        public void setNotifyStaff(boolean notifyStaff) {
            this.notifyStaff = notifyStaff;
        }

        public String getFilteredMessage() {
            return this.message;
        }

        public List<String> getServerCommands() {
            return this.serverCommands;
        }

        public List<String> getPlayerCommands() {
            return this.playerCommands;
        }

        public void transformMessage(Function<String, String> transformer) {
            this.message = transformer.apply(this.message);
        }
    }
}
