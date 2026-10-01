package dev.rosewood.rosechat.staff;

import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.api.staff.PresenceType;
import dev.rosewood.rosechat.message.MessageUtils;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class StaffVisibilityPolicy {

    private StaffVisibilityPolicy() {
    }

    public static boolean canSee(CommandSender viewer, Player subject) {
        if (!(viewer instanceof Player viewerPlayer))
            return true;

        return canSee(viewerPlayer.getUniqueId(), subject);
    }

    public static boolean canSee(UUID viewerId, Player subject) {
        RoseChatStaffServiceImpl staffService = RoseChat.getInstance().getStaffService();
        boolean canonicalVisibility = staffService != null && staffService.getBridgeOwner().isPresent();
        BiPredicate<UUID, UUID> visibility = canonicalVisibility
                ? (subjectId, subjectViewerId) -> staffService.canRenderPresence(subjectId, subjectViewerId, PresenceType.JOIN)
                : (subjectId, subjectViewerId) -> true;
        return canSee(
                viewerId,
                subject.getUniqueId(),
                MessageUtils.isPlayerVanished(subject),
                canonicalVisibility,
                visibility
        );
    }

    public static boolean hasCanonicalVisibility() {
        RoseChatStaffServiceImpl staffService = RoseChat.getInstance().getStaffService();
        return staffService != null && staffService.getBridgeOwner().isPresent();
    }

    public static boolean canSee(
            UUID viewerId,
            UUID subjectId,
            boolean legacyVanished,
            boolean canonicalVisibility,
            BiPredicate<UUID, UUID> visibility
    ) {
        if (viewerId == null)
            return !canonicalVisibility && !legacyVanished;
        if (viewerId.equals(subjectId))
            return true;
        if (canonicalVisibility)
            return visibility.test(subjectId, viewerId);
        return !legacyVanished;
    }
}
