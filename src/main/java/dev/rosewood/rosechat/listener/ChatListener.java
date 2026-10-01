package dev.rosewood.rosechat.listener;

import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.api.RoseChatAPI;
import dev.rosewood.rosechat.chat.PlayerData;
import dev.rosewood.rosechat.chat.channel.Channel;
import dev.rosewood.rosechat.chat.channel.ChannelMessageOptions;
import dev.rosewood.rosechat.chat.filter.Filter;
import dev.rosewood.rosechat.config.Settings;
import dev.rosewood.rosechat.message.MessageUtils;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosegarden.utils.NMSUtil;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.ItemStack;

public class ChatListener implements Listener {

    private static final String CHAT_CLAIM_META = "lumaguilds:chat_claimed";

    private final RoseChatAPI api;

    public ChatListener() {
        this.api = RoseChatAPI.getInstance();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (event.getPlayer().hasMetadata(CHAT_CLAIM_META)) {
            org.bukkit.plugin.Plugin claimer = Bukkit.getPluginManager().getPlugin("LumaGuilds");
            if (claimer != null)
                event.getPlayer().removeMetadata(CHAT_CLAIM_META, claimer);
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        RosePlayer player = new RosePlayer(event.getPlayer());
        PlayerData data = player.getPlayerData();

        String message = event.getMessage();

        player.validateMuteExpiry();
        player.validateChatColor();

        if (NMSUtil.getVersionNumber() >= 19 && Settings.ALLOW_CHAT_SUGGESTIONS.get())
            player.validateChatCompletion();

        if (!player.hasPermission("rosechat.chat")) {
            player.sendLocaleMessage("no-permission");
            return;
        }

        if (MessageUtils.isMessageEmpty(message)) {
            player.sendLocaleMessage("message-blank");
            return;
        }

        if (data.isMuted() && !player.hasPermission("rosechat.mute.bypass")) {
            player.sendLocaleMessage("command-mute-cannot-send");
            return;
        }

        String heldItemFilter = Settings.HELD_ITEM_FILTER.get();
        if (heldItemFilter != null && player.isPlayer()) {
            Filter filter = this.api.getFilterById(heldItemFilter);
            if (filter != null) {
                for (String match : filter.matches()) {
                    if (message.contains(match)) {
                        ItemStack stack = player.asPlayer().getInventory().getItemInMainHand();
                        if (stack.getAmount() == 0 && !Settings.ALLOW_NO_HELD_ITEM.get()) {
                            player.sendLocaleMessage("no-held-item");
                            return;
                        }
                    }
                }
            }
        }

        for (Channel channel : this.api.getChannels()) {
            if (channel.getSettings().getShoutCommands().isEmpty())
                continue;

            for (String command : channel.getSettings().getShoutCommands()) {
                if (!message.startsWith(command))
                    continue;

                if (channel.isMuted() && !player.hasPermission("rosechat.mute.bypass")) {
                    player.sendLocaleMessage("channel-muted");
                    return;
                }

                String format = channel.getSettings().getFormats().get("shout") == null ?
                        channel.getSettings().getFormats().get("chat") : channel.getSettings().getFormats().get("shout");

                ChannelMessageOptions options = new ChannelMessageOptions.Builder()
                        .sender(player)
                        .message(message.substring(command.length()).trim())
                        .format(format)
                        .sendToDiscord(true)
                        .build();
                this.send(channel, options);

                if (Settings.UPDATE_DISPLAY_NAMES.get())
                    player.updateDisplayName();
                return;
            }
        }

        Channel channel = data.getActiveChannel();
        if (channel == null)
            channel = data.getCurrentChannel();

        if (channel == null) {
            channel = player.findChannel();
            if (channel == null) {
                player.sendLocaleMessage("no-channel-available");
                return;
            }
            player.switchChannel(channel);
        }

        if (channel.isMuted() && !player.hasPermission("rosechat.mute.bypass")) {
            player.sendLocaleMessage("channel-muted");
            return;
        }

        ChannelMessageOptions options = new ChannelMessageOptions.Builder()
                .sender(player)
                .message(message)
                .build();
        this.send(channel, options);
        if (Settings.UPDATE_DISPLAY_NAMES.get())
            player.updateDisplayName();
    }

    private void send(Channel channel, ChannelMessageOptions options) {
        RoseChat plugin = RoseChat.getInstance();
        if (plugin.getAiModerationManager() == null) {
            channel.send(options);
            return;
        }

        if (Settings.SPAM_CHECKING_ENABLED.get()
                && options.sender() != null
                && options.sender().getPlayerData() != null
                && options.sender().getPlayerData().getMessageLog().wouldMessageBeSpam(options.message())) {
            // Let the normal local RoseChat rule path reject the message without spending an API call.
            channel.send(options);
            return;
        }

        plugin.getAiModerationManager().moderateAndSend(channel, options);
    }
}
