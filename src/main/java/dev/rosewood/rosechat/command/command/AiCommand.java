package dev.rosewood.rosechat.command.command;

import com.google.gson.Gson;
import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.command.RoseChatCommand;
import dev.rosewood.rosechat.moderation.ai.AiModerationConfig;
import dev.rosewood.rosechat.moderation.ai.AiModerationManager;
import dev.rosewood.rosechat.moderation.ai.AiModerationPolicy;
import dev.rosewood.rosechat.moderation.ai.ModerationScores;
import dev.rosewood.rosechat.moderation.ai.OpenAiModerationClient;
import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.command.framework.ArgumentsDefinition;
import dev.rosewood.rosegarden.command.framework.CommandContext;
import dev.rosewood.rosegarden.command.framework.CommandInfo;
import dev.rosewood.rosegarden.command.framework.annotation.RoseExecutable;
import java.io.File;
import java.net.http.HttpClient;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

public class AiCommand extends RoseChatCommand {

    public AiCommand(RosePlugin rosePlugin) {
        super(rosePlugin);
    }

    @Override
    protected CommandInfo createCommandInfo() {
        return CommandInfo.builder("ai")
                .permission("rosechat.debug")
                .arguments(ArgumentsDefinition.builder()
                        .optionalSub(
                                new AiStatusCommand(this.rosePlugin),
                                new AiTestCommand(this.rosePlugin),
                                new AiInspectCommand(this.rosePlugin)
                        ))
                .build();
    }

    @RoseExecutable
    public void execute(CommandContext context) {
        sendStatus((RoseChat) this.rosePlugin, context.getSender());
    }

    static void sendStatus(RoseChat plugin, CommandSender sender) {
        AiModerationConfig config = AiModerationConfig.load(plugin);
        AiModerationManager manager = plugin.getAiModerationManager();
        KeyInfo key = resolveKey(plugin, config);

        sender.sendMessage("--- RoseChat AI Moderation ---");
        sender.sendMessage("Enabled: " + config.enabled());
        sender.sendMessage("Mode: " + (config.shadowMode() ? "SHADOW (no enforcement)" : "ENFORCING"));
        sender.sendMessage("Model: " + config.model());
        sender.sendMessage("API key: " + (key.key().isBlank() ? "MISSING" : "configured via " + key.source()));

        if (manager == null) {
            sender.sendMessage("Health: NOT INITIALIZED");
        } else {
            AiModerationManager.Health health = manager.health();
            String status = health.status().name();
            if (health.status() == AiModerationManager.Status.HEALTHY && "configured".equalsIgnoreCase(health.detail())) {
                status = "CONFIGURED (waiting for a successful request)";
            }
            sender.sendMessage("Health: " + status);
            sender.sendMessage("Detail: " + health.detail());
        }

        sender.sendMessage("Max chat hold: " + config.maximumChatHold().toMillis() + "ms");
        sender.sendMessage("Use /rosechat ai test to verify OpenAI now.");
        sender.sendMessage("Use /rosechat ai inspect <message> to see scores without enforcing.");
    }

    static CompletableFuture<ProbeResult> probe(RoseChat plugin, String message) {
        AiModerationConfig config = AiModerationConfig.load(plugin);
        if (!config.enabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("AI moderation is disabled"));
        }

        KeyInfo key = resolveKey(plugin, config);
        if (key.key().isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "No OpenAI API key is configured in ai-moderation.yml or " + config.apiKeyEnvironmentVariable()));
        }

        OpenAiModerationClient client = new OpenAiModerationClient(
                HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build(),
                new Gson(),
                config,
                key.key()
        );
        String transcript = "RoseChat AI diagnostic request\n"
                + "TARGET_INDEX=1\nTARGET_POSITION=END\n[1] diagnostic: " + message;
        long started = System.nanoTime();
        return client.moderate(message, transcript).thenApply(batch -> {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            AiModerationPolicy.Verdict verdict = new AiModerationPolicy(config).evaluate(batch);
            return new ProbeResult(elapsedMs, batch.target(), verdict, topScores(batch.target(), 5));
        });
    }

    static void sendProbeResult(CommandSender sender, ProbeResult result, boolean includeScores) {
        sender.sendMessage("OpenAI moderation API: OK (" + result.elapsedMs() + "ms)");
        sender.sendMessage("Target flagged by OpenAI: " + result.target().flagged());
        sender.sendMessage("RoseChat decision: " + result.verdict().action()
                + " | category=" + result.verdict().category()
                + " | severity=" + result.verdict().severity() + "/100");
        if (includeScores) {
            sender.sendMessage("Top target category scores:");
            for (Map.Entry<String, Double> score : result.topScores()) {
                sender.sendMessage(" - " + score.getKey() + ": " + String.format("%.2f%%", score.getValue() * 100.0D));
            }
        }
    }

    static String failureMessage(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current.getClass() == RuntimeException.class)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String detail = current.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = current.getClass().getSimpleName();
        } else {
            detail = current.getClass().getSimpleName() + ": " + detail.replaceAll("\\s+", " ").trim();
        }
        return detail.length() <= 400 ? detail : detail.substring(0, 400) + "...";
    }

    private static KeyInfo resolveKey(RoseChat plugin, AiModerationConfig config) {
        File file = new File(plugin.getDataFolder(), "ai-moderation.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String configured = yaml.getString("api-key", "");
        if (configured != null && !configured.isBlank()) {
            return new KeyInfo(configured.trim(), "ai-moderation.yml");
        }
        String environment = System.getenv(config.apiKeyEnvironmentVariable());
        return new KeyInfo(environment == null ? "" : environment.trim(), config.apiKeyEnvironmentVariable());
    }

    private static List<Map.Entry<String, Double>> topScores(ModerationScores scores, int limit) {
        return scores.scores().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    record ProbeResult(
            long elapsedMs,
            ModerationScores target,
            AiModerationPolicy.Verdict verdict,
            List<Map.Entry<String, Double>> topScores
    ) {
    }

    private record KeyInfo(String key, String source) {
    }
}
