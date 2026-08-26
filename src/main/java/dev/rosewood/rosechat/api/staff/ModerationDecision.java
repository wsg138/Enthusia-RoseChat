package dev.rosewood.rosechat.api.staff;

import java.util.Objects;

public record ModerationDecision(Action action, String feedback) {
    public ModerationDecision {
        Objects.requireNonNull(action, "action");
        feedback = feedback == null ? "" : feedback;
    }

    public static ModerationDecision allow() {
        return new ModerationDecision(Action.ALLOW, "");
    }

    public static ModerationDecision block(String feedback) {
        return new ModerationDecision(Action.BLOCK, feedback);
    }

    public static ModerationDecision staffOnly() {
        return new ModerationDecision(Action.STAFF_ONLY, "");
    }

    public enum Action {
        ALLOW,
        BLOCK,
        STAFF_ONLY
    }
}
