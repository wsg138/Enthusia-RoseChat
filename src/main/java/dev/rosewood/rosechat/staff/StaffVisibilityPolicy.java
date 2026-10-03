package dev.rosewood.rosechat.staff;

import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.api.staff.PresenceType;
import dev.rosewood.rosechat.message.MessageUtils;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class StaffVisibilityPolicy {

    private static final String STAFF_PLUGIN_NAME = "EnthusiaStaff";

    private StaffVisibilityPolicy() {
    }

    public static boolean canSee(CommandSender viewer, Player subject) {
        if (!(viewer instanceof Player viewerPlayer))
            return true;

        return canSee(viewerPlayer.getUniqueId(), subject);
    }

    public static boolean canSee(UUID viewerId, Player subject) {
        RoseChatStaffServiceImpl staffService = RoseChat.getInstance().getStaffService();
        boolean canonicalAvailable = staffService != null && staffService.getBridgeOwner().isPresent();
        boolean canonicalRequired = canonicalAvailable || isStaffPluginPresent();
        BiPredicate<UUID, UUID> visibility = canonicalAvailable
                ? (subjectId, subjectViewerId) -> staffService.canRenderPresence(subjectId, subjectViewerId, PresenceType.JOIN)
                : (subjectId, subjectViewerId) -> false;
        return canSee(
                viewerId,
                subject.getUniqueId(),
                MessageUtils.isPlayerVanished(subject),
                canonicalRequired,
                canonicalAvailable,
                visibility
        );
    }

    public static boolean hasCanonicalVisibility() {
        RoseChatStaffServiceImpl staffService = RoseChat.getInstance().getStaffService();
        return staffService != null && staffService.getBridgeOwner().isPresent();
    }

    public static boolean isCanonicalVisibilityRequired() {
        return hasCanonicalVisibility() || isStaffPluginPresent();
    }

    private static boolean isStaffPluginPresent() {
        return Bukkit.getPluginManager().getPlugin(STAFF_PLUGIN_NAME) != null;
    }

    public static boolean canSee(
            UUID viewerId,
            UUID subjectId,
            boolean legacyVanished,
            boolean canonicalRequired,
            boolean canonicalAvailable,
            BiPredicate<UUID, UUID> visibility
    ) {
        if (viewerId != null && viewerId.equals(subjectId))
            return true;
        if (canonicalRequired)
            return canonicalAvailable && viewerId != null && visibility.test(subjectId, viewerId);
        return !legacyVanished;
    }
}
