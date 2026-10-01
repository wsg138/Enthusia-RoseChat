package dev.rosewood.rosechat.command.argument;

import dev.rosewood.rosechat.api.RoseChatAPI;
import dev.rosewood.rosechat.message.MessageUtils;
import dev.rosewood.rosechat.staff.StaffVisibilityPolicy;
import dev.rosewood.rosegarden.command.framework.Argument;
import dev.rosewood.rosegarden.command.framework.ArgumentHandler;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.InputIterator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class OfflinePlayerArgumentHandler extends ArgumentHandler<String> {

    private final boolean withBungeePlayers;

    public OfflinePlayerArgumentHandler(boolean withBungeePlayers) {
        super(String.class);
        this.withBungeePlayers = withBungeePlayers;
    }

    @Override
    public String handle(CommandContext context, Argument argument, InputIterator inputIterator) throws HandledArgumentException {
        String input = inputIterator.next();
        if (MessageUtils.getPlayerExact(input) == null) {
            Player player = MessageUtils.getPlayer(input);
            if (player != null)
                return player.getName();
        }

        if (input.trim().isEmpty())
            throw new ArgumentHandler.HandledArgumentException("argument-handler-string");
        return input;
    }

    @Override
    public List<String> suggest(CommandContext context, Argument argument, String[] args) {
        List<String> suggestions = new ArrayList<>(Bukkit.getOnlinePlayers().stream()
                .filter(player -> StaffVisibilityPolicy.canSee(context.getSender(), player))
                .map(OfflinePlayerArgumentHandler::suggestionName)
                .toList());

        if (this.withBungeePlayers
                && !StaffVisibilityPolicy.hasCanonicalVisibility()
                && RoseChatAPI.getInstance().isBungee()) {
            addRemoteSuggestions(context, suggestions);
        }

        String prefix = args.length == 0 ? "" : args[args.length - 1];
        return suggestions.stream()
                .filter(suggestion -> matchesPrefix(suggestion, prefix))
                .distinct()
                .toList();
    }

    private static String suggestionName(Player player) {
        String displayName = player.getDisplayName();
        return displayName.contains(" ") ? player.getName() : ChatColor.stripColor(displayName);
    }

    private static void addRemoteSuggestions(CommandContext context, List<String> suggestions) {
        RoseChatAPI api = RoseChatAPI.getInstance();
        Collection<String> players = api.getBungeeManager().getBungeePlayers().get("ALL");
        for (String player : players) {
            if (!context.getSender().getName().equalsIgnoreCase(player))
                suggestions.add(player);
        }
    }

    static boolean matchesPrefix(String suggestion, String prefix) {
        return prefix.isEmpty() || suggestion.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
