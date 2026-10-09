package dev.rosewood.rosechat.moderation.ai.central;

import java.util.List;
import java.util.Objects;

/**
 * The parsed {@code POST /v1/moderate} response.
 *
 * <p>RoseChat enforces <strong>only</strong> {@link #messageAction()}. The
 * review, strike, containment, and support dimensions are surfaced to staff
 * diagnostics and the audit log; they are never converted into automatic
 * punishments by this client.</p>
 */
public record CentralModerationDecision(
        MessageAction messageAction,
        String ingestionStatus,
        boolean degraded,
        String semanticLabel,
        Double confidence,
        String reviewPriority,
        String strikeRecommendation,
        String containment,
        List<String> reasonCodes,
        List<RelatedMessageRef> relatedMessages,
        String policyVersion,
        String modelVersion,
        String fallbackState,
        boolean idempotentReplay,
        String playerNotice
) {
    public CentralModerationDecision {
        Objects.requireNonNull(messageAction, "messageAction");
        ingestionStatus = ingestionStatus == null ? "" : ingestionStatus;
        semanticLabel = semanticLabel == null ? "none" : semanticLabel;
        reviewPriority = reviewPriority == null ? "NONE" : reviewPriority;
        strikeRecommendation = strikeRecommendation == null ? "NONE" : strikeRecommendation;
        containment = containment == null ? "NONE" : containment;
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        relatedMessages = relatedMessages == null ? List.of() : List.copyOf(relatedMessages);
        policyVersion = policyVersion == null ? "" : policyVersion;
        modelVersion = modelVersion == null ? "" : modelVersion;
        fallbackState = fallbackState == null ? "" : fallbackState;
        playerNotice = playerNotice == null ? "" : playerNotice;
    }

    /** Source-compatible constructor for older W13 call sites/tests. */
    public CentralModerationDecision(
            MessageAction messageAction, String ingestionStatus,
            boolean degraded, String semanticLabel, Double confidence,
            String reviewPriority, String strikeRecommendation,
            String containment, List<String> reasonCodes,
            List<RelatedMessageRef> relatedMessages, String policyVersion,
            String modelVersion, String fallbackState, boolean idempotentReplay
    ) {
        this(messageAction, ingestionStatus, degraded, semanticLabel, confidence,
                reviewPriority, strikeRecommendation, containment, reasonCodes,
                relatedMessages, policyVersion, modelVersion, fallbackState,
                idempotentReplay, "");
    }

    /**
     * API notices are private client-facing hints, not trusted markup.
     * Reject newlines/control codes/formatting and fall back to a safe message.
     */
    public String safePlayerNotice() {
        if (!enforceBlock()) {
            return "";
        }
        if (playerNotice.length() <= 220
                && playerNotice.startsWith("Your message was blocked ")
                && playerNotice.chars().noneMatch(ch ->
                        ch < 32 || ch > 126 || ch == '&' || ch == '<'
                                || ch == '>' || ch == 0xA7)) {
            return playerNotice;
        }
        return "Your message was blocked by chat moderation. If this seems wrong, contact staff.";
    }

    public String safeRemovalNotice() {
        String notice = safePlayerNotice();
        if (notice.isEmpty()) {
            return "";
        }
        return notice.replaceFirst("^Your message was blocked",
                "Your public message was removed");
    }

    /**
     * The contract's fail-open markers: a degraded response or an explicit
     * {@code FAIL_OPEN} ingestion status must always be treated as ALLOW.
     */
    public boolean failOpen() {
        return degraded || "FAIL_OPEN".equalsIgnoreCase(ingestionStatus);
    }

    /**
     * @return {@code true} only when the service explicitly BLOCKs and the
     * response is not a fail-open/degraded response.
     */
    public boolean enforceBlock() {
        return messageAction == MessageAction.BLOCK && !failOpen();
    }

    public enum MessageAction {
        ALLOW,
        BLOCK;

        static MessageAction parse(String value) {
            if ("BLOCK".equalsIgnoreCase(value)) {
                return BLOCK;
            }
            if ("ALLOW".equalsIgnoreCase(value)) {
                return ALLOW;
            }
            throw new IllegalArgumentException("unknown message_action: " + value);
        }
    }
}
