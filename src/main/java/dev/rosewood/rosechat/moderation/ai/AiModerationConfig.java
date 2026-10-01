package dev.rosewood.rosechat.moderation.ai;

import java.io.File;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public record AiModerationConfig(
        boolean enabled,
        boolean shadowMode,
        boolean punishmentsEnabled,
        String model,
        String apiKeyEnvironmentVariable,
        Duration maximumChatHold,
        Duration requestTimeout,
        int beforeMessages,
        int afterMessages,
        Duration contextMaxAge,
        int contextMaxCharacters,
        Duration followUpDelay,
        int failuresToOpen,
        Duration circuitOpenDuration,
        int requiredStrikes,
        Duration strikeWindow,
        Duration muteDuration,
        double corroborationFloorRatio,
        Map<String, Double> deleteThresholds,
        double selfHarmIntentAlertThreshold,
        String staffStatusPermission
) {
    static final int REQUIRED_AUTOMATIC_MUTE_STRIKES = 2;
    static final Duration REQUIRED_AUTOMATIC_MUTE_WINDOW = Duration.ofHours(1);
    static final Duration REQUIRED_AUTOMATIC_MUTE_DURATION = Duration.ofDays(30);
    private static final String RESOURCE = "ai-moderation.yml";
    private static final String DEFAULT_MODEL = "omni-moderation-latest";
    private static final String DEFAULT_STAFF_STATUS_PERMISSION = "rosechat.ai.alerts";
    private static final String LEGACY_STAFF_STATUS_PERMISSION = "rosechat.seeblocked";
    private static final String INVALID_CONFIG_ENVIRONMENT_VARIABLE = "__ROSECHAT_AI_CONFIG_INVALID__";

    public AiModerationConfig {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(apiKeyEnvironmentVariable, "apiKeyEnvironmentVariable");
        Objects.requireNonNull(maximumChatHold, "maximumChatHold");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(contextMaxAge, "contextMaxAge");
        Objects.requireNonNull(followUpDelay, "followUpDelay");
        Objects.requireNonNull(circuitOpenDuration, "circuitOpenDuration");
        Objects.requireNonNull(strikeWindow, "strikeWindow");
        Objects.requireNonNull(muteDuration, "muteDuration");
        Objects.requireNonNull(deleteThresholds, "deleteThresholds");
        Objects.requireNonNull(staffStatusPermission, "staffStatusPermission");
        deleteThresholds = Map.copyOf(deleteThresholds);
        if (maximumChatHold.isNegative() || maximumChatHold.toMillis() > 300) {
            throw new IllegalArgumentException("maximum-chat-hold-ms must be between 0 and 300");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("request-timeout-ms must be positive");
        }
        if (beforeMessages < 0 || afterMessages < 0 || contextMaxCharacters < 256) {
            throw new IllegalArgumentException("invalid AI moderation context bounds");
        }
        if (failuresToOpen < 1) {
            throw new IllegalArgumentException("invalid AI moderation circuit-breaker settings");
        }
        if (requiredStrikes != REQUIRED_AUTOMATIC_MUTE_STRIKES) {
            throw new IllegalArgumentException("strikes.required must be 2 to match the EnthusiaStaff automatic-mute policy");
        }
        if (!REQUIRED_AUTOMATIC_MUTE_WINDOW.equals(strikeWindow)) {
            throw new IllegalArgumentException("strikes.window-minutes must be 60 to match the EnthusiaStaff automatic-mute policy");
        }
        if (!REQUIRED_AUTOMATIC_MUTE_DURATION.equals(muteDuration)) {
            throw new IllegalArgumentException("strikes.mute-days must be 30 to match the EnthusiaStaff automatic-mute policy");
        }
        if (corroborationFloorRatio <= 0 || corroborationFloorRatio > 1) {
            throw new IllegalArgumentException("corroboration-floor-ratio must be in (0, 1]");
        }
    }

    public static AiModerationConfig load(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        try {
            return loadStrict(plugin);
        } catch (RuntimeException exception) {
            String detail = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            plugin.getLogger().warning("AI moderation configuration is invalid; chat will fail open and staff will be warned: "
                    + detail);
            return invalidConfigurationFallback();
        }
    }

    private static AiModerationConfig loadStrict(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), RESOURCE);
        if (!file.exists()) {
            plugin.saveResource(RESOURCE, false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<String, Double> thresholds = new LinkedHashMap<>();
        thresholds.put("harassment", yaml.getDouble("policy.harassment", 0.97));
        thresholds.put("harassment/threatening", yaml.getDouble("policy.harassment-threatening", 0.78));
        thresholds.put("hate", yaml.getDouble("policy.hate", 0.82));
        thresholds.put("hate/threatening", yaml.getDouble("policy.hate-threatening", 0.70));
        thresholds.put("self-harm/instructions", yaml.getDouble("policy.self-harm-instructions", 0.80));
        thresholds.put("sexual/minors", yaml.getDouble("policy.sexual-minors", 0.65));
        thresholds.put("violence/graphic", yaml.getDouble("policy.violence-graphic", 0.92));
        thresholds.put("illicit/violent", yaml.getDouble("policy.illicit-violent", 0.92));
        thresholds.replaceAll((category, value) -> boundedThreshold(category, value));
        return new AiModerationConfig(
                yaml.getBoolean("enabled", false),
                yaml.getBoolean("shadow-mode", true),
                yaml.getBoolean("punishments.enabled", false),
                nonBlank(yaml.getString("model"), DEFAULT_MODEL),
                nonBlank(yaml.getString("api-key-environment-variable"), "OPENAI_API_KEY"),
                Duration.ofMillis(yaml.getLong("maximum-chat-hold-ms", 200)),
                Duration.ofMillis(yaml.getLong("request-timeout-ms", 2000)),
                yaml.getInt("context.before-messages", 6),
                yaml.getInt("context.after-messages", 3),
                Duration.ofSeconds(yaml.getLong("context.max-age-seconds", 45)),
                yaml.getInt("context.max-characters", 3500),
                Duration.ofMillis(yaml.getLong("context.follow-up-delay-ms", 1500)),
                yaml.getInt("circuit-breaker.failures-to-open", 3),
                Duration.ofSeconds(yaml.getLong("circuit-breaker.open-seconds", 60)),
                yaml.getInt("strikes.required", REQUIRED_AUTOMATIC_MUTE_STRIKES),
                Duration.ofMinutes(yaml.getLong("strikes.window-minutes", REQUIRED_AUTOMATIC_MUTE_WINDOW.toMinutes())),
                Duration.ofDays(yaml.getLong("strikes.mute-days", REQUIRED_AUTOMATIC_MUTE_DURATION.toDays())),
                boundedThreshold("corroboration-floor-ratio", yaml.getDouble("policy.corroboration-floor-ratio", 0.75)),
                thresholds,
                boundedThreshold("self-harm-intent-alert", yaml.getDouble("policy.self-harm-intent-alert", 0.55)),
                staffPermission(yaml.getString("staff-status-permission"))
        );
    }

    private static AiModerationConfig invalidConfigurationFallback() {
        Map<String, Double> thresholds = new LinkedHashMap<>();
        thresholds.put("harassment", 0.97);
        thresholds.put("harassment/threatening", 0.78);
        thresholds.put("hate", 0.82);
        thresholds.put("hate/threatening", 0.70);
        thresholds.put("self-harm/instructions", 0.80);
        thresholds.put("sexual/minors", 0.65);
        thresholds.put("violence/graphic", 0.92);
        thresholds.put("illicit/violent", 0.92);
        return new AiModerationConfig(
                true,
                true,
                false,
                DEFAULT_MODEL,
                INVALID_CONFIG_ENVIRONMENT_VARIABLE,
                Duration.ZERO,
                Duration.ofSeconds(2),
                6,
                3,
                Duration.ofSeconds(45),
                3500,
                Duration.ofMillis(1500),
                3,
                Duration.ofSeconds(60),
                REQUIRED_AUTOMATIC_MUTE_STRIKES,
                REQUIRED_AUTOMATIC_MUTE_WINDOW,
                REQUIRED_AUTOMATIC_MUTE_DURATION,
                0.75,
                thresholds,
                0.55,
                DEFAULT_STAFF_STATUS_PERMISSION
        );
    }

    private static double boundedThreshold(String key, double value) {
        if (!Double.isFinite(value) || value <= 0 || value > 1) {
            throw new IllegalArgumentException("AI moderation threshold " + key + " must be in (0, 1]");
        }
        return value;
    }

    private static String staffPermission(String value) {
        String permission = nonBlank(value, DEFAULT_STAFF_STATUS_PERMISSION);
        if (LEGACY_STAFF_STATUS_PERMISSION.equalsIgnoreCase(permission)) {
            return DEFAULT_STAFF_STATUS_PERMISSION;
        }
        return permission;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
