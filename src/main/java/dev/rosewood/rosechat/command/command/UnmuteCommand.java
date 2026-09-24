package dev.rosewood.rosechat.command.command;

import dev.rosewood.rosechat.command.RoseChatCommand;
import dev.rosewood.rosechat.command.argument.RoseChatArgumentHandlers;
import dev.rosewood.rosechat.manager.EnthusiaStaffCommandCompatibility;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import dev.rosewood.rosegarden.utils.StringPlaceholders;

public class UnmuteCommand extends RoseChatCommand {

    public UnmuteCommand(RosePlugin rosePlugin) {
        super(rosePlugin);
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("unmute")
                .descriptionKey("command-unmute-description")
                .permission("rosechat.unmute")
                .arguments(ArgumentsDefinition.builder()
                        .required("player", RoseChatArgumentHandlers.ROSE_PLAYER)
                        .build())
                .build();
    }

    @Override
    protected boolean hasPriority() {
        boolean staffInstalled = EnthusiaStaffCommandCompatibility.staffInstalled(
                this.rosePlugin.getServer().getPluginManager());
        return EnthusiaStaffCommandCompatibility.defaultPriority(staffInstalled, "unmute", true);
    }

    @RoseExecutable
    public void execute(CommandContext context, RosePlayer target) {
        RosePlayer player = new RosePlayer(context.getSender());

        if (!target.isMuted()) {
            player.sendLocaleMessage("command-unmute-not-muted",
                    StringPlaceholders.of("player", target.getName()));
            return;
        }

        target.unmute();
        player.sendLocaleMessage("command-unmute-success",
                StringPlaceholders.of("player", target.getName()));
        target.sendLocaleMessage("command-mute-unmuted");
    }

}
