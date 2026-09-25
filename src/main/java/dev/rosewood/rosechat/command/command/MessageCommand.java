package dev.rosewood.rosechat.command.command;

import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.api.staff.PresenceType;
import dev.rosewood.rosechat.chat.PlayerData;
import dev.rosewood.rosechat.command.RoseChatCommand;
import dev.rosewood.rosechat.command.argument.OfflinePlayerArgumentHandler;
import dev.rosewood.rosechat.config.Settings;
import dev.rosewood.rosechat.message.MessageUtils;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosechat.staff.RoseChatStaffServiceImpl;
import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.command.argument.ArgumentHandlers;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import java.util.UUID;
import java.util.function.BiPredicate;
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
        if (target != null && this.targetHiddenFromSender(player, target)) {
            this.sendUnavailablePlayer(player);
            return;
        }

        RosePlayer messagePlayer = target == null
                ? new RosePlayer(targetName, "default")
                : new RosePlayer(target);

        if (MessageUtils.isMessageEmpty(message)) {
            player.sendLocaleMessage("message-blank");
            return;
        }

        // Cross-server delivery acknowledgements currently route back through ForwardToPlayer.
        // Non-player senders have no valid return player, so keep them on the local-message path.
        if (!player.isPlayer()
                && target == null
                && this.getAPI().isBungee()
                && this.getAPI().getBungeeManager().getAllPlayers().contains(messagePlayer.getRealName())) {
            this.sendUnavailablePlayer(player);
            return;
        }

        // Capture the sender's data before asynchronous cross-server delivery completes. A player
        // may disconnect before the acknowledgement arrives, at which point RosePlayer#isPlayer()
        // becomes false even though the successfully delivered message should still update /r.
        PlayerData senderData = player.isPlayer() ? player.getPlayerData() : null;

        MessageUtils.sendPrivateMessage(player, messagePlayer.getRealName(), message, success -> {
            if (!success)
                return;

            if (senderData != null) {
                senderData.setReplyTo(messagePlayer.getRealName());
                senderData.save();
            }

            if (target == null) {
                if (this.getAPI().isBungee()
                        && this.getAPI().getBungeeManager().getAllPlayers().contains(messagePlayer.getRealName())) {
                    this.getAPI().getBungeeManager().sendUpdateReply(player.getRealName(), messagePlayer.getRealName());
                }
                return;
            }

            PlayerData targetData = this.getAPI().getPlayerData(target.getUniqueId());
            if (targetData == null)
                return;

            targetData.setReplyTo(player.getRealName());
            targetData.save();
        });
    }

    private boolean targetHiddenFromSender(RosePlayer sender, Player target) {
        if (!sender.isPlayer() || sender.getUUID() == null)
            return false;
        RoseChatStaffServiceImpl staffService = RoseChat.getInstance().getStaffService();
        if (staffService == null)
            return false;
        return targetHidden(
                sender.getUUID(),
                target.getUniqueId(),
                (subjectId, viewerId) -> staffService.canRenderPresence(subjectId, viewerId, PresenceType.JOIN)
        );
    }

    static boolean targetHidden(
            UUID viewerId,
            UUID subjectId,
            BiPredicate<UUID, UUID> canRenderPresence
    ) {
        return !canRenderPresence.test(subjectId, viewerId);
    }

    private void sendUnavailablePlayer(RosePlayer player) {
        player.sendLocaleMessage("invalid-argument",
                StringPlaceholders.of("message",
                        this.getAPI().getLocaleManager().getLocaleMessage("argument-handler-player")));
    }
}
