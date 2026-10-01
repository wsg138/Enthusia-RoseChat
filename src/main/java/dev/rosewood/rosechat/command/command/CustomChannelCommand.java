package dev.rosewood.rosechat.command.command;

import dev.rosewood.rosechat.api.RoseChatAPI;
import dev.rosewood.rosechat.chat.channel.Channel;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

public class CustomChannelCommand extends Command {

    public CustomChannelCommand(String name) {
        super(name);
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        RosePlayer player = new RosePlayer(sender);
        RoseChatAPI api = RoseChatAPI.getInstance();
        String commandName = this.channelLookupName();

        for (Channel channel : api.getChannels()) {
            if (!channel.getSettings().getCommands().contains(commandName))
                continue;

            if (!player.hasPermission("rosechat.channel." + channel.getId())) {
                player.sendLocaleMessage("no-permission");
                return false;
            }

            if (player.isPlayer()) {
                if (!channel.canJoinByCommand(player)) {
                    player.sendLocaleMessage("command-channel-not-joinable");
                    return false;
                }
            }

            // Switch channels if the player doesn't specify a message.
            if (args.length == 0) {
                if (player.isConsole()) {
                    player.sendLocaleMessage("only-player");
                    return false;
                }

                // Move the player to the default channel if they're attempting to switch to the channel they're in.
                if (channel.getId().equals(player.getPlayerData().getCurrentChannel().getId()))
                    channel = api.getDefaultChannel();

                player.switchChannel(channel);

                player.sendLocaleMessage("command-channel-joined",
                        StringPlaceholders.of("id", channel.getId()));
                return true;
            }

            String message = String.join(" ", args);
            player.quickChat(channel, message);
        }

        return false;
    }

    String channelLookupName() {
        return this.getName().toLowerCase(Locale.ROOT);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) throws IllegalArgumentException {
        return Collections.emptyList();
    }

}
