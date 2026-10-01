package dev.rosewood.rosechat.command.command;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.command.RoseChatCommand;
import dev.rosewood.rosechat.moderation.ai.AiModerationConfig;
import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.command.argument.ArgumentHandlers;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

public class AiInspectCommand extends RoseChatCommand {
    private static final String CALIBRATION_FILE = "ai-moderation-calibration.jsonl";
    private static final Gson GSON = new Gson();

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
                    if (appendCalibration(plugin, message, result)) {
                        sender.sendMessage("Calibration sample saved to plugins/RoseChat/" + CALIBRATION_FILE);
                    } else {
                        sender.sendMessage("Calibration sample could not be saved; check the server log.");
                    }
                }));
    }

    private static boolean appendCalibration(RoseChat plugin, String message, AiCommand.ProbeResult result) {
        AiModerationConfig config = AiModerationConfig.load(plugin);
        JsonObject row = new JsonObject();
        row.addProperty("at", Instant.now().toString());
        row.addProperty("model", config.model());
        row.addProperty("message", message);
        row.addProperty("latency_ms", result.elapsedMs());
        row.addProperty("openai_flagged", result.target().flagged());
        row.addProperty("decision", result.verdict().action().name());
        row.addProperty("decision_category", result.verdict().category());
        row.addProperty("decision_confidence", result.verdict().confidence());
        row.addProperty("severity", result.verdict().severity());
        row.add("categories", GSON.toJsonTree(result.target().categories()));
        row.add("scores", GSON.toJsonTree(result.target().scores()));

        Path file = plugin.getDataFolder().toPath().resolve(CALIBRATION_FILE);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    GSON.toJson(row) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not append AI moderation calibration sample: "
                    + exception.getClass().getSimpleName());
            return false;
        }
    }
}
