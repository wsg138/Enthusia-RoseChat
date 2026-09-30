package dev.rosewood.rosechat.command.command;

import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.command.RoseChatCommand;
import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.command.argument.ArgumentHandlers;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

public class AiInspectCommand extends RoseChatCommand {

    public AiInspectCommand(RosePlugin rosePlugin) {
        super(rosePlugin);
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("inspect")
                .permission("rosechat.debug")
                .arguments(ArgumentsDefinition.builder()
                        .required("message", ArgumentHandlers.GREEDY_STRING)
                        .build())
                .build();
    }

    @RoseExecutable
    public void execute(CommandContext context, String message) {
        RoseChat plugin = (RoseChat) this.rosePlugin;
        CommandSender sender = context.getSender();
        sender.sendMessage("Inspecting with OpenAI moderation; this does not enforce or add a strike.");
        AiCommand.probe(plugin, message)
                .whenComplete((result, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (failure != null) {
                        sender.sendMessage("OpenAI moderation API: FAILED");
                        sender.sendMessage(AiCommand.failureMessage(failure));
                        return;
                    }
                    AiCommand.sendProbeResult(sender, result, true);
                }));
    }
}
