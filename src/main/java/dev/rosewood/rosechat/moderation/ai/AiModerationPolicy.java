package dev.rosewood.rosechat.moderation.ai;

import java.util.Map;
import java.util.Objects;

public final class AiModerationPolicy {
    private static final String SELF_HARM_INTENT = "self-harm/intent";
    private static final String HARASSMENT = "harassment";

    private final AiModerationConfig config;

    public AiModerationPolicy(AiModerationConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public Verdict evaluate(OpenAiModerationClient.BatchResult batch) {
        Objects.requireNonNull(batch, "batch");
        Candidate strongest = strongestDeleteCandidate(batch.target(), batch.context());
        if (strongest != null) {
            return new Verdict(
                    Action.DELETE,
                    strongest.category(),
                    strongest.targetScore(),
                    severity(strongest.targetScore(), strongest.threshold()),
                    false
            );
        }

        double selfHarmIntent = batch.target().score(SELF_HARM_INTENT);
        if (selfHarmIntent >= config.selfHarmIntentAlertThreshold()) {
            return new Verdict(
                    Action.ALERT_ONLY,
                    SELF_HARM_INTENT,
                    selfHarmIntent,
                    severity(selfHarmIntent, config.selfHarmIntentAlertThreshold()),
                    false
            );
        }

        String strongestCategory = strongestCategory(batch.target());
        double strongestScore = strongestCategory.isEmpty() ? 0.0D : batch.target().score(strongestCategory);
        boolean followUpUseful = nearDeleteThreshold(batch.target());
        return new Verdict(
                Action.ALLOW,
                strongestCategory.isEmpty() ? "none" : strongestCategory,
                strongestScore,
                0,
                followUpUseful
        );
    }

    private Candidate strongestDeleteCandidate(ModerationScores target, ModerationScores context) {
        Candidate strongest = null;
        for (Map.Entry<String, Double> threshold : config.deleteThresholds().entrySet()) {
            String category = threshold.getKey();
            double required = threshold.getValue();
            double targetScore = target.score(category);
            double contextScore = context.score(category);
            boolean direct = targetScore >= required;

            // Plain harassment is intentionally target-only. Minecraft chat is often noisy and
            // mildly insulting, so surrounding toxic context must not turn a low-severity line
            // such as "you suck" into a DELETE decision. Threats, hate, sexual/minor content,
            // and the other higher-risk categories can still use bounded context corroboration.
            boolean corroborated = !HARASSMENT.equals(category)
                    && targetScore >= required * config.corroborationFloorRatio()
                    && contextScore >= required;
            if (!direct && !corroborated) {
                continue;
            }
            Candidate candidate = new Candidate(category, targetScore, required);
            if (strongest == null
                    || normalizedRisk(candidate) > normalizedRisk(strongest)) {
                strongest = candidate;
            }
        }
        return strongest;
    }

    private boolean nearDeleteThreshold(ModerationScores target) {
        for (Map.Entry<String, Double> threshold : config.deleteThresholds().entrySet()) {
            if (target.score(threshold.getKey()) >= threshold.getValue() * 0.60D) {
                return true;
            }
        }
        return false;
    }

    private static String strongestCategory(ModerationScores scores) {
        String category = "";
        double maximum = 0.0D;
        for (Map.Entry<String, Double> entry : scores.scores().entrySet()) {
            if (entry.getValue() > maximum) {
                maximum = entry.getValue();
                category = entry.getKey();
            }
        }
        return category;
    }

    private static double normalizedRisk(Candidate candidate) {
        return candidate.targetScore() / candidate.threshold();
    }

    private static int severity(double score, double threshold) {
        if (threshold <= 0) {
            return 0;
        }
        return (int) Math.round(Math.min(100.0D, (score / threshold) * 100.0D));
    }

    public enum Action {
        ALLOW,
        ALERT_ONLY,
        DELETE
    }

    public record Verdict(Action action, String category, double confidence, int severity, boolean followUpUseful) {
        public Verdict {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(category, "category");
        }
    }

    private record Candidate(String category, double targetScore, double threshold) {
    }
}
