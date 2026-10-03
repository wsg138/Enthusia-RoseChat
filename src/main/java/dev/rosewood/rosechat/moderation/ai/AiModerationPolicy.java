package dev.rosewood.rosechat.moderation.ai;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * RETIRED as a production decision authority (W13 central migration).
 *
 * <p>This local threshold policy must not independently decide production
 * ALLOW/DELETE while central mode is active: the central Policy-v1 service is
 * the single semantic authority. Retained, with its tests, for the explicitly
 * labeled legacy OpenAI diagnostics ({@code /rosechat ai inspect}), which
 * cannot enforce.</p>
 */
@Deprecated
public final class AiModerationPolicy {
    private static final String SELF_HARM_INTENT = "self-harm/intent";
    private static final String SELF_HARM_INSTRUCTIONS = "self-harm/instructions";
    private static final String SEXUAL_MINORS = "sexual/minors";
    private static final String HARASSMENT = "harassment";
    private static final String HARASSMENT_THREATENING = "harassment/threatening";
    private static final String ILLICIT_VIOLENT = "illicit/violent";
    private static final String VIOLENCE = "violence";

    // These floors are intentionally narrower than the global delete thresholds. They
    // only apply when the message itself contains an explicit real-world cue. The
    // values are based on Enthusia's Minecraft calibration corpus, where explicit
    // real-life threats scored materially lower than generic PvP violence.
    private static final double REAL_WORLD_THREAT_FLOOR = 0.50D;
    private static final double REAL_WORLD_VIOLENCE_FLOOR = 0.40D;
    private static final double REAL_WORLD_ILLICIT_VIOLENT_FLOOR = 0.85D;

    private final AiModerationConfig config;

    public AiModerationPolicy(AiModerationConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public Verdict evaluate(OpenAiModerationClient.BatchResult batch) {
        return evaluate(batch.targetMessage(), batch);
    }

    public Verdict evaluate(String message, OpenAiModerationClient.BatchResult batch) {
        Objects.requireNonNull(batch, "batch");
        String normalized = normalize(message);

        Candidate modelFlag = highConfidenceModelFlag(batch.target());
        if (modelFlag != null) {
            return delete(modelFlag);
        }

        Candidate realWorld = realWorldSafetyCandidate(normalized, batch.target());
        if (realWorld != null) {
            return delete(realWorld);
        }

        Candidate strongest = strongestDeleteCandidate(normalized, batch.target(), batch.context());
        if (strongest != null) {
            return delete(strongest);
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
        boolean followUpUseful = nearDeleteThreshold(normalized, batch.target());
        return new Verdict(
                Action.ALLOW,
                strongestCategory.isEmpty() ? "none" : strongestCategory,
                strongestScore,
                0,
                followUpUseful
        );
    }

    private Candidate highConfidenceModelFlag(ModerationScores target) {
        for (String category : new String[] {SELF_HARM_INSTRUCTIONS, SEXUAL_MINORS}) {
            if (Boolean.TRUE.equals(target.categories().get(category))) {
                double score = target.score(category);
                return new Candidate(category, score, score > 0.0D ? score : 1.0D);
            }
        }
        return null;
    }

    private Candidate realWorldSafetyCandidate(String normalized, ModerationScores target) {
        if (!hasExplicitRealWorldCue(normalized)) {
            return null;
        }

        double threat = target.score(HARASSMENT_THREATENING);
        double violence = target.score(VIOLENCE);
        if (threat >= REAL_WORLD_THREAT_FLOOR && violence >= REAL_WORLD_VIOLENCE_FLOOR) {
            return new Candidate(HARASSMENT_THREATENING, threat, REAL_WORLD_THREAT_FLOOR);
        }

        double illicitViolent = target.score(ILLICIT_VIOLENT);
        if (illicitViolent >= REAL_WORLD_ILLICIT_VIOLENT_FLOOR) {
            return new Candidate(ILLICIT_VIOLENT, illicitViolent, REAL_WORLD_ILLICIT_VIOLENT_FLOOR);
        }

        return null;
    }

    private Candidate strongestDeleteCandidate(
            String normalized,
            ModerationScores target,
            ModerationScores context
    ) {
        Candidate strongest = null;
        for (Map.Entry<String, Double> threshold : config.deleteThresholds().entrySet()) {
            String category = threshold.getKey();
            if (shouldIgnoreCategoryForMinecraft(normalized, category)) {
                continue;
            }

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
            if (strongest == null || normalizedRisk(candidate) > normalizedRisk(strongest)) {
                strongest = candidate;
            }
        }
        return strongest;
    }

    private boolean nearDeleteThreshold(String normalized, ModerationScores target) {
        for (Map.Entry<String, Double> threshold : config.deleteThresholds().entrySet()) {
            String category = threshold.getKey();

            if (shouldIgnoreCategoryForMinecraft(normalized, category)) {
                continue;
            }

            if (target.score(category) >= threshold.getValue() * 0.60D) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldIgnoreCategoryForMinecraft(String normalized, String category) {
        if (normalized.isEmpty() || hasExplicitRealWorldCue(normalized)) {
            return false;
        }

        if (HARASSMENT_THREATENING.equals(category) && looksLikeAmbiguousGameplayThreat(normalized)) {
            return true;
        }

        return ILLICIT_VIOLENT.equals(category) && containsToken(normalized, "tnt");
    }

    private static boolean looksLikeAmbiguousGameplayThreat(String normalized) {
        return containsPhrase(normalized, "kill you")
                || containsPhrase(normalized, "kill u")
                || containsPhrase(normalized, "stab you")
                || containsPhrase(normalized, "stab u")
                || containsPhrase(normalized, "shoot you")
                || containsPhrase(normalized, "shoot u")
                || containsPhrase(normalized, "blow you up")
                || containsPhrase(normalized, "blow u up")
                || containsPhrase(normalized, "burn your house")
                || containsPhrase(normalized, "burn ur house");
    }

    private static boolean hasExplicitRealWorldCue(String normalized) {
        return containsPhrase(normalized, "in real life")
                || containsToken(normalized, "irl")
                || containsPhrase(normalized, "real world")
                || containsPhrase(normalized, "where you live")
                || containsPhrase(normalized, "where u live")
                || containsPhrase(normalized, "your address")
                || containsPhrase(normalized, "ur address")
                || containsPhrase(normalized, "home address")
                || containsPhrase(normalized, "your school")
                || containsPhrase(normalized, "ur school")
                || containsPhrase(normalized, "at your school")
                || containsPhrase(normalized, "at ur school")
                || containsPhrase(normalized, "outside your house")
                || containsPhrase(normalized, "outside ur house")
                || containsPhrase(normalized, "outside your home")
                || containsPhrase(normalized, "outside ur home");
    }

    private static boolean containsPhrase(String normalized, String phrase) {
        return normalized.contains(" " + phrase + " ");
    }

    private static boolean containsToken(String normalized, String token) {
        return containsPhrase(normalized, token);
    }

    private static String normalize(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String normalized = message.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return normalized.isEmpty() ? "" : " " + normalized + " ";
    }

    private static Verdict delete(Candidate candidate) {
        return new Verdict(
                Action.DELETE,
                candidate.category(),
                candidate.targetScore(),
                severity(candidate.targetScore(), candidate.threshold()),
                false
        );
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
