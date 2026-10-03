package dev.rosewood.rosechat.staff;

import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.api.RoseChatAPI;
import dev.rosewood.rosechat.api.staff.PresenceContext;
import dev.rosewood.rosechat.api.staff.PresenceType;
import dev.rosewood.rosechat.manager.JoinMessageManager;
import dev.rosewood.rosechat.manager.LeaveMessageManager;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosechat.message.contents.MessageContents;
import dev.rosewood.rosechat.placeholder.CustomPlaceholder;
import dev.rosewood.rosechat.placeholder.condition.PlaceholderCondition;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import java.util.Collection;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class PresenceMessageRenderer {

    private PresenceMessageRenderer() {
    }

    public static boolean render(RoseChat plugin, PresenceContext context) {
        if (plugin == null)
            return false;

        Player subject = Bukkit.getPlayer(context.subjectId());
        Player viewer = Bukkit.getPlayer(context.viewerId());
        if (subject == null || viewer == null)
            return false;

        return render(plugin, new RosePlayer(subject), new RosePlayer(viewer), context.type());
    }

    public static boolean render(RoseChat plugin, RosePlayer subject, RosePlayer viewer, PresenceType type) {
        Collection<CustomPlaceholder> messages = type == PresenceType.JOIN
                ? plugin.getManager(JoinMessageManager.class).getJoinMessages()
                : plugin.getManager(LeaveMessageManager.class).getLeaveMessages();
        RoseChatAPI api = RoseChatAPI.getInstance();
        boolean rendered = false;

        for (CustomPlaceholder message : messages) {
            PlaceholderCondition condition = message.get("message");
            if (condition == null)
                continue;

            List<String> lines = condition.parseToStringList(subject, viewer, StringPlaceholders.empty());
            if (lines == null || lines.isEmpty())
                continue;

            for (String line : lines) {
                MessageContents parsed = api.parse(subject, viewer, line);
                if (parsed == null)
                    continue;
                viewer.send(parsed);
                rendered = true;
            }
        }

        return rendered;
    }
}
