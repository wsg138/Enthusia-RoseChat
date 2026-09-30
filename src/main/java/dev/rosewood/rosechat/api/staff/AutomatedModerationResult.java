package dev.rosewood.rosechat.api.staff;

import java.util.Objects;

public record AutomatedModerationResult(Status status, String detail) {
    public AutomatedModerationResult {
        Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail.trim();
        if (detail.length() > 512) {
            detail = detail.substring(0, 512);
        }
    }

    public static AutomatedModerationResult applied(String detail) {
        return new AutomatedModerationResult(Status.APPLIED, detail);
    }

    public static AutomatedModerationResult rejected(String detail) {
        return new AutomatedModerationResult(Status.REJECTED, detail);
    }

    public static AutomatedModerationResult unavailable(String detail) {
        return new AutomatedModerationResult(Status.UNAVAILABLE, detail);
    }

    public enum Status {
        APPLIED,
        REJECTED,
        UNAVAILABLE
    }
}
