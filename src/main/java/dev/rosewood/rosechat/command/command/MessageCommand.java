package dev.rosewood.rosechat.command.command;

import dev.rosewood.rosechat.chat.PlayerData;
import dev.rosewood.rosechat.command.RoseChatCommand;
import dev.rosewood.rosechat.command.argument.OfflinePlayerArgumentHandler;
import dev.rosewood.rosechat.config.Settings;
import dev.rosewood.rosechat.message.MessageUtils;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.command.argument.ArgumentHandlers;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import org.bukkit.entity.Player;

public class MessageCommand extends RoseChatCommand {

    public MessageCommand(RosePlugin rosePlugin) {
        super(rosePlugin);
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("message")
                .aliases(
                        "msg",
                        "m",
                        "pm",
                        "whisper",
                        "w",
                        "tell",
                        "t"
                )
                .permission("rosechat.message")
                .descriptionKey("command-message-description")
                .arguments(ArgumentsDefinition.builder()
                        .required("player", new OfflinePlayerArgumentHandler(Settings.ALLOW_BUNGEECORD_MESSAGES.get()))
                        .required("message", ArgumentHandlers.GREEDY_STRING)
                        .build())
                .build();
    }

    @Override
    protected boolean hasPriority() {
        return true;
    }

    @RoseExecutable
    public void execute(CommandContext context, String targetName, String message) {
        RosePlayer player = new RosePlayer(context.getSender());
        Player target = MessageUtils.getPlayerExact(targetName);
        RosePlayer messagePlayer = target == null
                ? new RosePlayer(targetName, "default")
                : new RosePlayer(target);

        if (MessageUtils.isMessageEmpty(message)) {
            player.sendLocaleMessage("message-blank");
            return;
        }

        MessageUtils.sendPrivateMessage(player, messagePlayer.getRealName(), message);

        if (player.isPlayer()) {
            player.getPlayerData().setReplyTo(messagePlayer.getRealName());
            player.getPlayerData().save();
        }

        if (this.getAPI().isBungee())
            this.getAPI().getBungeeManager().sendUpdateReply(player.getRealName(), messagePlayer.getRealName());

        if (target == null)
            return;

        PlayerData targetData = this.getAPI().getPlayerData(target.getUniqueId());
        if (targetData == null)
            return;

        targetData.setReplyTo(player.getRealName());
        targetData.save();
    }

}
