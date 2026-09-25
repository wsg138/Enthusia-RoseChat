package dev.rosewood.rosechat.command.command;

import dev.rosewood.rosechat.command.RoseChatCommand;
import dev.rosewood.rosechat.command.argument.MuteDuration;
import dev.rosewood.rosechat.command.argument.RoseChatArgumentHandlers;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import dev.rosewood.rosegarden.RosePlugin;

public class MuteCommand extends RoseChatCommand {

    public MuteCommand(RosePlugin rosePlugin) {
        super(rosePlugin);
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("mute")
                .descriptionKey("command-mute-description")
                .permission("rosechat.mute")
                .arguments(ArgumentsDefinition.builder()
                        .required("player", RoseChatArgumentHandlers.ROSE_PLAYER)
                        .optional("duration", RoseChatArgumentHandlers.MUTE_DURATION)
                        .build())
                .build();
    }

    @Override
    protected boolean hasPriority() {
        return true;
    }

    @RoseExecutable
    public void execute(CommandContext context, RosePlayer target) {
        RosePlayer player = new RosePlayer(context.getSender());
        if (target.isMuted()) {
            target.unmute();
            player.sendLocaleMessage("command-unmute-success",
                    StringPlaceholders.of("player", target.getName()));
            target.sendLocaleMessage("command-mute-unmuted");
            return;
        }

        target.mute();
        player.sendLocaleMessage("command-mute-indefinite",
                StringPlaceholders.of("player", target.getName()));
        target.sendLocaleMessage("command-mute-muted");
    }

    @RoseExecutable
    public void execute(CommandContext context, RosePlayer target, MuteDuration duration) {
        RosePlayer player = new RosePlayer(context.getSender());
        if (duration.indefinite()) {
            target.mute();
            player.sendLocaleMessage("command-mute-indefinite",
                    StringPlaceholders.of("player", target.getName()));
        } else {
            target.mute(duration.seconds());
            player.sendLocaleMessage("command-mute-success",
                    StringPlaceholders.of(
                            "player", target.getName(),
                            "time", duration.amount(),
                            "scale", this.getLocaleManager().getLocaleMessage(
                                    "command-mute-" + duration.displayUnit())
                    ));
        }
        target.sendLocaleMessage("command-mute-muted");
    }
}
