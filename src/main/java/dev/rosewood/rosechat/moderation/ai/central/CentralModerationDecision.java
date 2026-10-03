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
        boolean idempotentReplay
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
