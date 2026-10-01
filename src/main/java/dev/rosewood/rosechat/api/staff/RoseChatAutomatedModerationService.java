package dev.rosewood.rosechat.api.staff;

public interface RoseChatAutomatedModerationService {
    int API_VERSION = 2;

    default int apiVersion() {
        return API_VERSION;
    }

    AutomatedModerationResult applyPublicMute(AutomatedPublicMuteRequest request);
}
