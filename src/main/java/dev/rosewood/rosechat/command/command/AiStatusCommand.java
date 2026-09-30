package dev.rosewood.rosechat.command.command;

import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.command.RoseChatCommand;
import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;

public class AiStatusCommand extends RoseChatCommand {

    public AiStatusCommand(RosePlugin rosePlugin) {
        super(rosePlugin);
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("status")
                .permission("rosechat.debug")
                .arguments(ArgumentsDefinition.builder().build())
                .build();
    }

    @RoseExecutable
    public void execute(CommandContext context) {
        AiCommand.sendStatus((RoseChat) this.rosePlugin, context.getSender());
    }
}
