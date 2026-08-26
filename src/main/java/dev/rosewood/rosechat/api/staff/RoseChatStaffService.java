package dev.rosewood.rosechat.api.staff;

import java.util.Optional;
import java.util.UUID;

public interface RoseChatStaffService {
    int API_VERSION = 1;

    default int apiVersion() {
        return API_VERSION;
    }

    Optional<String> getCurrentChannel(UUID playerId);

    boolean setCurrentChannel(UUID playerId, String channelId);

    Optional<String> getStaffChannel();

    String getGlobalChannel();

    ChannelClassification classifyChannel(String channelId);

    boolean toggleStaffChannel(UUID playerId);

    BridgeRegistration installBridge(
            String owner,
            StaffChannelConfiguration configuration,
            RoseChatModerationBridge bridge
    );

    Optional<String> getBridgeOwner();
}
