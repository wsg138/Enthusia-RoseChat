package dev.rosewood.rosechat.message.tokenizer.filter;

import dev.rosewood.rosechat.api.RoseChatAPI;
import dev.rosewood.rosechat.chat.PlayerData;
import dev.rosewood.rosechat.chat.filter.Filter;
import dev.rosewood.rosechat.config.Settings;
import dev.rosewood.rosechat.manager.FilterManager;
import dev.rosewood.rosechat.manager.FilterManager.FilterPattern;
import dev.rosewood.rosechat.message.MessageDirection;
import dev.rosewood.rosechat.message.MessageUtils;
import dev.rosewood.rosechat.message.PermissionArea;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosechat.message.tokenizer.Token;
import dev.rosewood.rosechat.message.tokenizer.Token.PlayerInputState;
import dev.rosewood.rosechat.message.tokenizer.Tokenizer;
import dev.rosewood.rosechat.message.tokenizer.TokenizerParams;
import dev.rosewood.rosechat.message.tokenizer.TokenizerResult;
import dev.rosewood.rosechat.message.tokenizer.Tokenizers;
import dev.rosewood.rosechat.message.tokenizer.composer.ChatComposer;
import dev.rosewood.rosechat.message.tokenizer.decorator.FontDecorator;
import dev.rosewood.rosechat.message.tokenizer.decorator.HoverDecorator;
import dev.rosewood.rosegarden.utils.HexUtils;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class FilterTokenizer extends Tokenizer {

    private final FilterManager filterManager;

    public FilterTokenizer() {
        super("filter");

        this.filterManager = RoseChatAPI.getInstance().getFilterManager();
    }

    @Override
    public List<TokenizerResult> tokenize(TokenizerParams params) {
        if (params.getLocation() != PermissionArea.CHANNEL && !checkPermission(params, "rosechat.filters." + params.getLocationPermission(), false))
            return null;

        List<TokenizerResult> results = new ArrayList<>();

        Collection<Filter> filters = this.filterManager.getFilters().values();

        // Inline filters (such as markdown urls)
        for (Filter filter : filters) {
            if (filter.block() || params.getIgnoredFilters().contains(filter.id()))
                continue;

            if (filter.inlinePrefix() == null || filter.inlineSuffix() == null || filter.prefix() == null || filter.suffix() == null)
                continue;

            this.collectInlineMatches(filter, params, results);
        }

        // Prefix/suffix matches (such as <spoiler>content</spoiler>)
        for (Filter filter : filters) {
            if (filter.block() || filter.prefix() == null || params.getIgnoredFilters().contains(filter.id()))
                continue;

            if (filter.useRegex()) {
                this.collectPrefixRegexMatches(filter, params, results);
            } else {
                this.collectPrefixSuffixMatches(filter, params, results);
            }
        }

        // Handle normal matches
        for (Filter filter : filters) {
            if (filter.block() || filter.prefix() != null || filter.inlinePrefix() != null || params.getIgnoredFilters().contains(filter.id()))
                continue;

            if (filter.useRegex()) {
                this.collectRegexMatches(filter, params, results);
            } else {
                this.collectMatches(filter, params, results);
            }
        }

        return results;
    }

    private void collectInlineMatches(Filter filter, TokenizerParams params, List<TokenizerResult> results) {
        Pattern pattern = this.filterManager.getCompiledPattern(filter.id(), FilterPattern.INLINE_PREFIX_SUFFIX);
        if (pattern == null)
            return;

        String input = params.getInput();
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            String match = matcher.group();
            if (TokenizerResult.overlaps(results, start, end))
                continue;

            if (filter.escapable() && start > 0 && input.charAt(start - 1) == MessageUtils.ESCAPE_CHAR && checkPermission(params, "rosechat.escape")) {
                results.add(new TokenizerResult(Token.text(match), start - 1, match.length() + 1));
                continue;
            }

            String content = matcher.group(1);
            String inline = matcher.group(2);

            if (filter.useRegex()) {
                boolean matchesContent = false;
                boolean matchesInline = false;

                List<Pattern> contentPatterns = RoseChatAPI.getInstance().getFilterManager()
                        .getCompiledPatterns(filter.id(), FilterPattern.REGEX_MATCHES);
                if (!contentPatterns.isEmpty()) {
                    for (Pattern contentPattern : contentPatterns) {
                        Matcher contentMatcher = contentPattern.matcher(content);
                        if (!contentMatcher.find())
                            continue;

                        matchesContent = true;
                        break;
                    }
                } else {
                    matchesContent = true;
                }

                List<Pattern> inlinePatterns = RoseChatAPI.getInstance().getFilterManager().getCompiledPatterns(filter.id(), FilterPattern.INLINE_MATCHES);
                if (!inlinePatterns.isEmpty()) {
                    for (Pattern inlinePattern : inlinePatterns) {
                        Matcher inlineMatcher = inlinePattern.matcher(inline);
                        if (!inlineMatcher.find())
                            continue;

                        matchesInline = true;
                        break;
                    }
                } else {
                    matchesInline = true;
                }

                if (!matchesContent || !matchesInline)
                    continue;
            }

            if (!filter.hasPermission(params)) {
                results.addAll(this.validateRemoval(start, match));
                continue;
            }

            if (this.checkToggled(params, filter))
                continue;

            String replacement = this.getReplacementForDirection(params, filter);
            replacement = this.applySignFix(params, filter, replacement);

            if (filter.matchLength()) {
                String colorless = RoseChatAPI.getInstance().parse(params.getSender(), params.getReceiver(), content).build(ChatComposer.plain());
                replacement = this.matchContentLength(replacement, colorless.length());
            }

            if (filter.tagPlayers())
                if (!this.tagPlayers(params, filter, content))
                    continue;

            Token token = this.createFilterToken(params, filter, replacement)
                    .placeholder("group_0", match)
                    .placeholder("message", match)
                    .placeholder("group_1", content)
                    .placeholder("group_2", inline)
                    .encapsulate()
                    .build();

            results.add(new TokenizerResult(token, start, match.length()));
        }
    }

    private void collectPrefixRegexMatches(Filter filter, TokenizerParams params, List<TokenizerResult> results) {
        Pattern pattern = this.filterManager.getCompiledPattern(filter.id(), FilterPattern.PREFIX);
        if (pattern == null)
            return;

        String input = params.getInput();
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            int start = matcher.start();
            if (start != 0 && !Character.isSpaceChar(input.charAt(start - 1))) // Not a prefix if this isn't a match at the beginning of a word
                continue;

            int end = matcher.end();
            String match = matcher.group();
            if (TokenizerResult.overlaps(results, start, end))
                continue;

            if (filter.escapable() && start > 0 && input.charAt(start - 1) == MessageUtils.ESCAPE_CHAR && checkPermission(params, "rosechat.escape")) {
                results.add(new TokenizerResult(Token.text(match), start - 1, match.length() + 1));
                continue;
            }

            this.collectPrefixMatches(filter, params, results, start, end);
        }
    }

    private void collectPrefixMatches(Filter filter, TokenizerParams params, List<TokenizerResult> results, int start, int end) {
        String input = params.getInput();
        int endIndex = input.indexOf(" ", end);
        String match;
        String content;

        if (endIndex == -1)
            endIndex = input.length();

        match = input.substring(start, endIndex);
        content = input.substring(end, endIndex);

        if (!filter.hasPermission(params)) {
            results.addAll(this.validateRemoval(start, match));
            return;
        }

        if (this.checkToggled(params, filter))
            return;

        String replacement = this.getReplacementForDirection(params, filter);
        replacement = this.applySignFix(params, filter, replacement);

        if (filter.matchLength()) {
            String colorless = RoseChatAPI.getInstance().parse(params.getSender(), params.getReceiver(), content).build(ChatComposer.plain());
            replacement = this.matchContentLength(replacement, colorless.length());
        }

        if (filter.tagPlayers())
            if (!this.tagPlayers(params, filter, content))
                return;

        Token.Builder token = this.createFilterToken(params, filter, replacement)
                .placeholder("message", match)
                .placeholder("tagged", match)
                .placeholder("group_0", match)
                .placeholder("group_1", content);

        results.add(new TokenizerResult(token.build(), start, match.length()));
    }

    private void collectPrefixSuffixMatches(Filter filter, TokenizerParams params, List<TokenizerResult> results) {
        String input = params.getInput();

        List<SensitivityMatch> prefixMatches = this.sensitivityMatch(input, filter.prefix(), filter.sensitivity(), filter.matchType());
        if (prefixMatches.isEmpty())
            return;

        if (filter.suffix() == null) {
            for (SensitivityMatch sensitivityMatch : prefixMatches) {
                int start = sensitivityMatch.start();
                int end = sensitivityMatch.end();
                String match = input.substring(start, end);

                if (TokenizerResult.overlaps(results, start, end))
                    continue;

                if (filter.escapable() && start > 0 && input.charAt(start - 1) == MessageUtils.ESCAPE_CHAR && checkPermission(params, "rosechat.escape")) {
                    results.add(new TokenizerResult(Token.text(match), start - 1, match.length() + 1));
                    continue;
                }

                this.collectPrefixMatches(filter, params, results, start, end);
            }
            return;
        }

        List<SensitivityMatch> suffixMatches = this.sensitivityMatch(input, filter.suffix(), filter.sensitivity(), filter.matchType());
        if (suffixMatches.isEmpty())
            return;

        outer:
        for (SensitivityMatch prefix : prefixMatches) {
            int prefixStart = prefix.start();
            int prefixEnd = prefix.end();

            for (SensitivityMatch suffix : suffixMatches) {
                int suffixStart = suffix.start();
                int suffixEnd = suffix.end();

                if (suffixStart < prefixEnd)
                    continue;

                if (TokenizerResult.overlaps(results, prefixStart, suffixEnd))
                    continue;

                String match = input.substring(prefixStart, suffixEnd);

                if (filter.escapable() && prefixStart > 0 && input.charAt(prefixStart - 1) == MessageUtils.ESCAPE_CHAR && checkPermission(params, "rosechat.escape")) {
                    results.add(new TokenizerResult(Token.text(match), prefixStart - 1, match.length() + 1));
                    continue;
                }

                String content = match.substring(filter.prefix().length(), match.length() - filter.suffix().length());

                if (!filter.hasPermission(params)) {
                    results.addAll(this.validateRemoval(prefixStart, match));
                    continue;
                }

                if (this.checkToggled(params, filter))
                    continue;

                String replacement = this.getReplacementForDirection(params, filter);
                replacement = this.applySignFix(params, filter, replacement);

                if (filter.matchLength()) {
                    String colorless = RoseChatAPI.getInstance().parse(params.getSender(), params.getReceiver(), content).build(ChatComposer.plain());
                    replacement = this.matchContentLength(replacement, colorless.length());
                }

                if (filter.tagPlayers())
                    if (!this.tagPlayers(params, filter, content))
                        continue;

                Token.Builder token = this.createFilterToken(params, filter, replacement)
                        .placeholder("message", match)
                        .placeholder("group_0", match)
                        .placeholder("group_1", content)
                        .encapsulate();

                results.add(new TokenizerResult(token.build(), prefixStart, match.length()));

                continue outer; // Only allow matching once with the nearest suffix
            }
        }
    }

    private void collectRegexMatches(Filter filter, TokenizerParams params, List<TokenizerResult> results) {
        String input = params.getInput();

        List<Pattern> patterns = this.filterManager.getCompiledPatterns(filter.id(), FilterPattern.REGEX_MATCHES);
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(input);
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                String match = matcher.group();

                if (TokenizerResult.overlaps(results, start, end))
                    continue;

                if (!filter.hasPermission(params)) {
                    results.addAll(this.validateRemoval(start, match));
                    continue;
                }

                if (this.checkToggled(params, filter))
                    continue;

                String content = this.getReplacementForDirection(params, filter);
                content = this.applySignFix(params, filter, content);

                Token.Builder token = this.createFilterToken(params, filter, content);

                // Apply placeholders for regex matches.
                StringPlaceholders.Builder placeholders = StringPlaceholders.builder();
                int groups = Math.max(9, matcher.groupCount() + 1);
                for (int i = 0; i < groups; i++) {
                    String replacement = matcher.groupCount() < i || matcher.group(i) == null ?
                            matcher.group(0) : matcher.group(i);
                    content = content.replace("%group_" + i + "%", replacement);
                    placeholders.add("group_" + i, replacement);
                }

                token.placeholder("message", match)
                        .placeholder("extra", match)
                        .placeholder("tagged", "%group_1%")
                        .placeholders(placeholders.build())
                        .ignoreTokenizer(this);

                results.add(new TokenizerResult(token.build(), start, match.length()));
            }
        }
    }

    private void collectMatches(Filter filter, TokenizerParams params, List<TokenizerResult> results) {
        String input = params.getInput();

        for (String matchText : filter.matches()) {
            List<SensitivityMatch> matches = this.sensitivityMatch(input, matchText, filter.sensitivity(), filter.matchType());
            for (SensitivityMatch sensitivityMatch : matches) {
                int start = sensitivityMatch.start();
                int end = sensitivityMatch.end();
                String match = input.substring(start, end);

                if (TokenizerResult.overlaps(results, start, end))
                    continue;

                if (!filter.hasPermission(params)) {
                    results.addAll(this.validateRemoval(start, match));
                    continue;
                }

                if (this.checkToggled(params, filter))
                    continue;

                String content = this.getReplacementForDirection(params, filter);
                content = this.applySignFix(params, filter, content);

                Token.Builder token = this.createFilterToken(params, filter, content);
                results.add(new TokenizerResult(token.build(), start, match.length()));
            }
        }
    }

    private List<SensitivityMatch> sensitivityMatch(String input, String match, int sensitivity, Filter.MatchType type) {
        List<SensitivityMatch> matches = new ArrayList<>();
        int length = match.length();

        if (sensitivity == 0) {
            switch (type) {
                case ANYWHERE -> {
                    int index = 0;
                    while ((index = input.indexOf(match, index)) != -1) {
                        int end = index + length;
                        if (!SensitivityMatch.overlaps(matches, index, end))
                            matches.add(new SensitivityMatch(index, end));
                        index = end;
                    }
                }
                case WORD -> {
                    String[] words = input.split(" ");
                    int charIndex = 0;
                    for (String word : words) {
                        String strippedWord = stripQuotes(word);
                        if (strippedWord.equalsIgnoreCase(match)) {
                            int end = charIndex + word.length();
                            if (!SensitivityMatch.overlaps(matches, charIndex, end))
                                matches.add(new SensitivityMatch(charIndex, end));
                        }

                        charIndex += word.length() + 1;
                    }
                }
            }
            return matches;
        }

        double distanceThreshold = sensitivity / 100.0;
        String strippedMessage = ChatColor.stripColor(HexUtils.colorify(MessageUtils.stripAccents(input)));
        String[] words = input.split(" ");
        String[] strippedWords = strippedMessage.split(" ");

        int charIndex = 0;
        for (int i = 0; i < strippedWords.length; i++) {
            String word = words[i];
            String strippedWord = stripQuotes(strippedWords[i]);

            double difference = MessageUtils.getLevenshteinDistancePercent(strippedWord, match);
            if (1 - difference <= distanceThreshold) {
                int end = charIndex + word.length();
                if (!SensitivityMatch.overlaps(matches, charIndex, end))
                    matches.add(new SensitivityMatch(charIndex, end));
            }

            charIndex += word.length() + 1;
        }

        return matches;
    }

    private List<TokenizerResult> validateRemoval(int start, String match) {
        return Settings.REMOVE_FILTERS.get() ?
                List.of(new TokenizerResult(Token.empty(), start, match.length())) :
                List.of();
    }

    private static String stripQuotes(String word) {
        while (word.length() > 1 && isQuote(word.charAt(0)) && isQuote(word.charAt(word.length() - 1)))
            word = word.substring(1, word.length() - 1);
        return word;
    }

    private static boolean isQuote(char c) {
        return c == '\"' || c == '\'' || c == '\u201C' || c == '\u201D' || c == '\u2018' || c == '\u2019' || c == '\u00AB' || c == '\u00BB';
    }
    
    private boolean checkToggled(TokenizerParams params, Filter filter) {
        if (!filter.canToggle())
            return false;
        
        PlayerData data = RoseChatAPI.getInstance().getPlayerData(params.getSender().getUUID());
        return data != null && !data.hasEmojis();
    }

    private String getReplacementForDirection(TokenizerParams params, Filter filter) {
        return (params.getDirection() == MessageDirection.MINECRAFT_TO_DISCORD) && (filter.discordOutput() != null) ?
                filter.discordOutput() :
                filter.replacement();
    }

    private String matchContentLength(String content, int length) {
        return content.repeat(length);
    }

    private String applySignFix(TokenizerParams params, Filter filter, String content) {
        return params.getLocation() == PermissionArea.SIGN && filter.isEmoji() ?
                "&f" + content + "&r" : content;
    }

    private boolean tagPlayers(TokenizerParams params, Filter filter, String content) {
        if (content.isEmpty())
            return false;

        DetectedPlayer detectedPlayer = this.matchPartialPlayer(content, filter.id());
        if (detectedPlayer != null) {
            Player taggedPlayer = detectedPlayer.player;
            params.getOutputs().getTaggedPlayers().add(taggedPlayer.getUniqueId());
            params.getOutputs().setPlaceholderTarget(new RosePlayer(taggedPlayer));
        } else {
            params.getOutputs().setPlaceholderTarget(new RosePlayer(content, "default"));
        }
        return true;
    }

    private Token.Builder createFilterToken(TokenizerParams params, Filter filter, String content) {
        Token.Builder token = Token.group(content)
                .decorate(new FontDecorator(filter.font()))
                .playerInputState(PlayerInputState.NOT_PLAYER_INPUT)
                .ignoreTokenizer(Tokenizers.SHADER_COLORS)
                .ignoreFilter(filter);

        if (filter.hover() != null)
            token.decorate(new HoverDecorator(filter.hover()));

        if (!filter.colorRetention())
            token.encapsulate();

        if (filter.sound() != null)
            params.getOutputs().setSound(filter.sound());

        if (filter.message() != null)
            params.getOutputs().setMessage(filter.message());

        params.getOutputs().getServerCommands().addAll(filter.serverCommands());
        params.getOutputs().getPlayerCommands().addAll(filter.playerCommands());

        return token;
    }

    private DetectedPlayer matchPartialPlayer(String input, String id) {
        // Check display names first
        for (Player player : Bukkit.getOnlinePlayers()) {
            int matchLength = this.getMatchLength(input, ChatColor.stripColor(player.getDisplayName()), id);
            if (matchLength != -1)
                return new DetectedPlayer(player, matchLength);
        }

        // Then usernames
        for (Player player : Bukkit.getOnlinePlayers()) {
            int matchLength = this.getMatchLength(input, player.getName(), id);
            if (matchLength != -1)
                return new DetectedPlayer(player, matchLength);
        }

        return null;
    }

    /**
     * Tries to find a match for a player in the input string.
     * Skips over color codes in the player name.
     *
     * @param input The input string to search for a player
     * @param playerName The name of the player to match
     * @return The length of the content that matches, or -1 if a match wasn't found
     */
    private int getMatchLength(String input, String playerName, String id) {
        Pattern stopPattern;
        List<Pattern> stopPatterns = RoseChatAPI.getInstance().getFilterManager().getCompiledPatterns(id, FilterPattern.STOP);
        if (stopPatterns.isEmpty()) {
            stopPattern = MessageUtils.PUNCTUATION_REGEX;
        } else {
            stopPattern = stopPatterns.getFirst();
        }

        int matchLength = 0;
        for (int i = 0, j = 0; i < input.length() && j < playerName.length(); i++, j++) {
            int inputChar = Character.toUpperCase(input.codePointAt(i));
            int playerChar = Character.toUpperCase(playerName.codePointAt(j));
            if (inputChar == playerChar) {
                matchLength++;
            } else if (i > 0 && (Character.isSpaceChar(inputChar) ||  Pattern.matches(stopPattern.pattern(), String.valueOf(Character.toChars(inputChar))))) {
                return matchLength;
            } else {
                return -1;
            }
        }

        return matchLength;
    }

    private record DetectedPlayer(Player player, int consumedTextLength) { }

    private record SensitivityMatch(int start, int end) {
        public boolean overlaps(int start, int end) {
            return end > this.start && start < this.end;
        }

        public static boolean overlaps(List<SensitivityMatch> matches, int start, int end) {
            for (SensitivityMatch match : matches)
                if (match.overlaps(start, end))
                    return true;
            return false;
        }
    }

}
