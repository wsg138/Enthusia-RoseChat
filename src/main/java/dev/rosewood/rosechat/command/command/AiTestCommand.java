package dev.rosewood.rosechat.command.command;

import com.google.gson.Gson;
import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.command.RoseChatCommand;
import dev.rosewood.rosechat.moderation.ai.AiModerationConfig;
import dev.rosewood.rosechat.moderation.ai.central.CentralCredentials;
import dev.rosewood.rosechat.moderation.ai.central.CentralModerationClient;
import dev.rosewood.rosechat.moderation.ai.central.CentralModerationIds;
import dev.rosewood.rosechat.moderation.ai.central.CentralModerationRequest;
import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Probes the central Policy-v1 moderation service with a harmless message.
 * Diagnostic only: the probe never enforces and never records anything.
 */
public class AiTestCommand extends RoseChatCommand {

    public AiTestCommand(RosePlugin rosePlugin) {
        super(rosePlugin);
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("test")
                .permission("rosechat.debug")
                .arguments(ArgumentsDefinition.builder().build())
                .build();
    }

    @RoseExecutable
    public void execute(CommandContext context) {
        RoseChat plugin = (RoseChat) this.rosePlugin;
        CommandSender sender = context.getSender();
        AiModerationConfig config = AiModerationConfig.load(plugin);
        if (!config.centralMode()) {
            sender.sendMessage("Central moderation is not configured; nothing to probe.");
            return;
        }
        CentralCredentials credentials = CentralCredentials.resolve(config);
        if (!credentials.complete()) {
            sender.sendMessage("Central credentials incomplete (" + credentials.safeSummary() + ").");
            return;
        }
        sender.sendMessage("Sending a harmless test request to the central moderation service...");
        CentralModerationClient client = new CentralModerationClient(
                HttpClient.newBuilder().connectTimeout(config.centralRequestTimeout()).build(),
                new Gson(),
                URI.create(config.centralBaseUri().trim()),
                credentials.clientId(),
                credentials::token,
                config.centralRequestTimeout()
        );
        UUID senderId = sender instanceof Player player ? player.getUniqueId() : new UUID(0L, 0L);
        UUID probeId = UUID.randomUUID();
        CentralModerationRequest request = CentralModerationRequest.publicMessage(
                config.centralScopeId().isBlank() ? "diagnostic" : config.centralScopeId(),
                "diagnostic",
                CentralModerationIds.externalMessageId(probeId),
                CentralModerationIds.canonicalMessageId(probeId),
                senderId,
                Instant.now(),
                "RoseChat central moderation connectivity test."
        );
        long started = System.nanoTime();
        client.moderate(request).whenComplete((decision, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (failure != null) {
                sender.sendMessage("Central moderation API: FAILED (chat would fail open)");
                sender.sendMessage(AiCommand.failureMessage(failure));
                return;
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            sender.sendMessage("Central moderation API: OK (" + elapsedMs + "ms)");
            sender.sendMessage("message_action=" + decision.messageAction()
                    + " label=" + decision.semanticLabel()
                    + (decision.failOpen() ? " (FAIL-OPEN/DEGRADED)" : ""));
            sender.sendMessage("policy=" + decision.policyVersion() + " model=" + decision.modelVersion());
        }));
    }
}
