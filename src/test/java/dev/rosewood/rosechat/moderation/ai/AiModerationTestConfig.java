package dev.rosewood.rosechat.moderation.ai;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

final class AiModerationTestConfig {
    private AiModerationTestConfig() {
    }

    static AiModerationConfig create() {
        Map<String, Double> thresholds = new LinkedHashMap<>();
        thresholds.put("harassment", 0.92);
        thresholds.put("harassment/threatening", 0.78);
        thresholds.put("hate", 0.82);
        thresholds.put("hate/threatening", 0.70);
        thresholds.put("self-harm/instructions", 0.80);
        thresholds.put("sexual/minors", 0.65);
        thresholds.put("violence/graphic", 0.92);
        thresholds.put("illicit/violent", 0.92);
        return new AiModerationConfig(
                true,
                false,
                "omni-moderation-latest",
                "OPENAI_API_KEY",
                Duration.ofMillis(300),
                Duration.ofSeconds(2),
                6,
                3,
                Duration.ofSeconds(45),
                3500,
                Duration.ofMillis(1500),
                3,
                Duration.ofSeconds(60),
                2,
                Duration.ofHours(1),
                Duration.ofDays(30),
                0.75,
                thresholds,
                0.55,
                "rosechat.seeblocked"
        );
    }
}
