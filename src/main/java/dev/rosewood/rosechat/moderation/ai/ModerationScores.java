package dev.rosewood.rosechat.moderation.ai;

import java.util.Map;
import java.util.Objects;

public record ModerationScores(boolean flagged, Map<String, Boolean> categories, Map<String, Double> scores) {
    public ModerationScores {
        Objects.requireNonNull(categories, "categories");
        Objects.requireNonNull(scores, "scores");
        categories = Map.copyOf(categories);
        scores = Map.copyOf(scores);
    }

    public double score(String category) {
        return scores.getOrDefault(category, 0.0D);
    }
}
