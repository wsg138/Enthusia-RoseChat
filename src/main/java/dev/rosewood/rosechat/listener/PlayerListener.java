package dev.rosewood.rosechat.listener;

import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.api.RoseChatAPI;
import dev.rosewood.rosechat.api.staff.PresenceType;
import dev.rosewood.rosechat.chat.PlayerData;
import dev.rosewood.rosechat.chat.channel.Channel;
import dev.rosewood.rosechat.config.Settings;
import dev.rosewood.rosechat.hook.channel.rosechat.GroupChannel;
import dev.rosewood.rosechat.manager.ChannelManager;
import dev.rosewood.rosechat.manager.JoinMessageManager;
import dev.rosewood.rosechat.manager.LeaveMessageManager;
import dev.rosewood.rosechat.manager.PlayerDataManager;
import dev.rosewood.rosechat.message.contents.MessageContents;
import dev.rosewood.rosechat.placeholder.CustomPlaceholder;
import dev.rosewood.rosechat.placeholder.condition.PlaceholderCondition;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosegarden.utils.NMSUtil;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.TabCompleteEvent;

public class PlayerListener implements Listener {

    private final RoseChat plugin;

    public PlayerListener(RoseChat plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        ChannelManager channelManager = this.plugin.getManager(ChannelManager.class);
        PlayerDataManager playerDataManager = this.plugin.getManager(PlayerDataManager.class);

        RosePlayer player = new RosePlayer(event.getPlayer());

        // Ensure group chats are loaded first.
        RoseChatAPI.getInstance().getGroupManager().loadMemberGroupChats(player.getUUID(), (gcs) -> {
            PlayerData playerData = playerDataManager.getPlayerData(player.getUUID());

            // Set the display name when the player logs in
            if (playerData.getNickname() != null)
                player.updateDisplayName();
            else
                playerData.setStrippedDisplayName(event.getPlayer().getDisplayName());

            // If the current channel is not a group channel, put the player in the right channel.
            if (!playerData.isCurrentChannelGroupChannel() || playerData.getCurrentChannel() == null) {
                playerData.setIsInGroupChannel(false);

                // Place the player in the correct channel.
                for (Channel channel : channelManager.getChannels().values()) {
                    if (channel.onLogin(player)) {
                        player.switchChannel(channel);
                        break;
                    }
                }

                // If no channel was found, force put player in the default channel.
                if (playerData.getCurrentChannel() == null) {
                    Channel defaultChannel = channelManager.getDefaultChannel();
                    player.switchChannel(defaultChannel);
                } else {
                    Channel channel = playerData.getCurrentChannel();
                    player.switchChannel(channel);
                }
            }

            player.validateChatColor();
        });
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        RosePlayer joiningPlayer = new RosePlayer(event.getPlayer());

        // Suppress the vanilla join message.
        event.setJoinMessage(null);

        // Broadcast custom join messages to all online players.
        JoinMessageManager joinMessageManager = this.plugin.getManager(JoinMessageManager.class);
        RoseChatAPI api = RoseChatAPI.getInstance();

        StringPlaceholders emptyPlaceholders = StringPlaceholders.empty();

        for (CustomPlaceholder joinMessage : joinMessageManager.getJoinMessages()) {
            PlaceholderCondition messageCondition = joinMessage.get("message");
            if (messageCondition == null)
                continue;

            // Includes the joining player — at NORMAL priority they are already in getOnlinePlayers().
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (this.plugin.getStaffService() != null
                        && !this.plugin.getStaffService().canRenderPresence(
                                joiningPlayer.getUUID(), online.getUniqueId(), PresenceType.JOIN))
                    continue;

                RosePlayer viewer = new RosePlayer(online);
                List<String> lines = messageCondition.parseToStringList(
                        joiningPlayer, viewer, emptyPlaceholders);
                if (lines == null || lines.isEmpty())
                    continue;
                for (String line : lines) {
                    MessageContents parsed = api.parse(joiningPlayer, viewer, line);
                    if (parsed != null)
                        viewer.send(parsed);
                }
            }
        }

        // Handle chat suggestions (MC 1.19+).
        if (NMSUtil.getVersionNumber() >= 19 && Settings.ALLOW_CHAT_SUGGESTIONS.get())
            joiningPlayer.validateChatCompletion();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        RosePlayer leavingPlayer = new RosePlayer(event.getPlayer());

        // Suppress the vanilla quit message.
        event.setQuitMessage(null);

        // Broadcast custom leave messages to all remaining online players.
        LeaveMessageManager leaveMessageManager = this.plugin.getManager(LeaveMessageManager.class);
        RoseChatAPI rcApi = RoseChatAPI.getInstance();

        StringPlaceholders emptyPlaceholders = StringPlaceholders.empty();

        for (CustomPlaceholder leaveMessage : leaveMessageManager.getLeaveMessages()) {
            PlaceholderCondition messageCondition = leaveMessage.get("message");
            if (messageCondition == null)
                continue;

            for (Player online : Bukkit.getOnlinePlayers()) {
                if (this.plugin.getStaffService() != null
                        && !this.plugin.getStaffService().canRenderPresence(
                                leavingPlayer.getUUID(), online.getUniqueId(), PresenceType.QUIT))
                    continue;

                RosePlayer viewer = new RosePlayer(online);
                List<String> lines = messageCondition.parseToStringList(
                        leavingPlayer, viewer, emptyPlaceholders);
                if (lines == null || lines.isEmpty())
                    continue;
                for (String line : lines) {
                    MessageContents parsed = rcApi.parse(leavingPlayer, viewer, line);
                    if (parsed != null)
                        viewer.send(parsed);
                }
            }
        }

        PlayerDataManager playerDataManager = this.plugin.getManager(PlayerDataManager.class);
        playerDataManager.getPlayerData(leavingPlayer.getUUID()).save();
        playerDataManager.getPlayerData(leavingPlayer.getUUID()).getCurrentChannel().onLeave(leavingPlayer);

        // Delay unloading to keep data cached while the player is leaving.
        Bukkit.getScheduler().runTaskLaterAsynchronously(RoseChat.getInstance(), () -> {
            if (!leavingPlayer.asPlayer().isOnline())
                playerDataManager.unloadPlayerData(leavingPlayer.getUUID());
        }, 20L * 30L);

        GroupChannel group = leavingPlayer.getOwnedGroupChannel();
        if (group == null)
            return;

        if (Settings.DISBAND_GROUP_ON_OWNER_DISCONNECT.get()) {
            boolean success = group.disband();
            if (!success)
                return;

            RoseChatAPI api = RoseChatAPI.getInstance();
            List<UUID> members = new ArrayList<>(group.getMembers());
            for (UUID uuid : members) {
                Player member = Bukkit.getPlayer(uuid);
                if (member == null)
                    continue;

                RosePlayer roseMember = new RosePlayer(member);
                PlayerData data = api.getPlayerData(member.getUniqueId());
                if (data.isCurrentChannelGroupChannel() && data.getCurrentChannel().getId().equalsIgnoreCase(group.getId()))
                    roseMember.switchChannel(roseMember.findChannel());

                api.getLocaleManager().sendComponentMessage(member, "command-gc-disband-success",
                        StringPlaceholders.of("name", group.getName()));
            }
        }
    }

    @EventHandler
    public void onCommandSend(PlayerCommandSendEvent event) {
        for (Channel channel : RoseChatAPI.getInstance().getChannels()) {
            if (channel.getSettings().getCommands().isEmpty())
                continue;

            for (String command : channel.getSettings().getCommands()) {
                event.getCommands().remove(command + ":" + command);
                if (!event.getPlayer().hasPermission("rosechat.channel." + channel.getId()))
                    event.getCommands().remove(command);
            }
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        RoseChatAPI api = RoseChatAPI.getInstance();
        RosePlayer player = new RosePlayer(event.getPlayer());
        api.getPlayerDataManager().getPlayerData(player.getUUID(), (playerData -> {
            World world = player.asPlayer().getWorld();
            Channel currentChannel = playerData.getCurrentChannel();

            // Check if the player can leave their current channel first.
            boolean leftWorld = currentChannel.onWorldLeave(player, event.getFrom(), world);

            // Find the appropriate channel before removing the player.
            // Loop through the channels to find if the player should join one.
            boolean joinedWorld = false;
            for (Channel channel : api.getChannels()) {
                // If the player can join a channel, join.
                if (channel.onWorldJoin(player, event.getFrom(), event.getPlayer().getWorld())) {
                    if (player.switchChannel(channel)) {
                        player.sendLocaleMessage("command-channel-joined", StringPlaceholders.of("id", channel.getId()));

                        joinedWorld = true;
                    }

                    break;
                }
            }

            // If the player left a world channel and did not find an appropriate channel
            if (leftWorld && !joinedWorld) {
                Channel defaultChannel = api.getChannelManager().getDefaultChannel();
                player.switchChannel(defaultChannel);
            }

            playerData.save();
        }));
    }

    @EventHandler
    public void onCommandPreProcess(PlayerCommandPreprocessEvent event) {
        String input = event.getMessage();
        RosePlayer player = new RosePlayer(event.getPlayer());

        for (Channel channel : RoseChatAPI.getInstance().getChannels()) {
            if (channel.getSettings().getOverrideCommands().isEmpty())
                continue;

            for (String command : channel.getSettings().getOverrideCommands()) {
                if (input.equalsIgnoreCase("/" + command) || input.toLowerCase().startsWith("/" + command.toLowerCase() + " ")) {
                    if (!channel.canJoinByCommand(player)) {
                        player.sendLocaleMessage("command-channel-not-joinable");
                        return;
                    }

                    if (channel.getId().equals(player.getPlayerData().getCurrentChannel().getId()))
                        channel = RoseChatAPI.getInstance().getDefaultChannel();

                    // If the message was the same, join the channel.
                    if (input.equalsIgnoreCase("/" + command)) {
                        boolean success = player.switchChannel(channel, channel instanceof GroupChannel);
                        if (!success)
                            return;

                        player.switchChannel(channel);

                        String joinMessage = channel.getSettings().getFormats().get("join-message");
                        if (joinMessage != null)
                            player.send(RoseChatAPI.getInstance().parse(player, player, joinMessage));
                        else
                            player.sendLocaleMessage("command-channel-joined",
                                    StringPlaceholders.of("id", channel.getId()));
                    } else {
                        String message = input.substring(("/" + command + " ").length());
                        player.quickChat(channel, message);
                    }

                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onTabComplete(TabCompleteEvent event) {
        String input = event.getBuffer();

        for (Channel channel : RoseChatAPI.getInstance().getChannels()) {
            if (!channel.getSettings().getOverrideCommands().isEmpty()) {
                for (String command : channel.getSettings().getOverrideCommands()) {
                    if (!input.toLowerCase().startsWith("/" + command.toLowerCase() + " "))
                        continue;

                    event.setCompletions(Collections.singletonList("[message]"));
                    return;
                }
            }

            if (!channel.getSettings().getCommands().isEmpty()) {
                for (String command : channel.getSettings().getCommands()) {
                    if (!input.toLowerCase().startsWith("/" + command.toLowerCase() + " "))
                        continue;

                    event.setCompletions(Collections.singletonList("[message]"));
                    return;
                }
            }
        }
    }

}
