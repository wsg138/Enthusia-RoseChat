package dev.rosewood.rosechat.manager;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.api.RoseChatAPI;
import dev.rosewood.rosechat.chat.PlayerData;
import dev.rosewood.rosechat.chat.channel.Channel;
import dev.rosewood.rosechat.chat.channel.ChannelMessageOptions;
import dev.rosewood.rosechat.config.Settings;
import dev.rosewood.rosechat.message.MessageUtils;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.hook.PlaceholderAPIHook;
import dev.rosewood.rosegarden.manager.Manager;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class BungeeManager extends Manager {

    private final Multimap<String, String> bungeePlayers;
    private final Map<UUID, Boolean> checkPluginResponses;
    private final Map<String, Boolean> legacyCheckPluginResponses;

    public BungeeManager(RosePlugin rosePlugin) {
        super(rosePlugin);

        this.bungeePlayers = ArrayListMultimap.create();
        this.checkPluginResponses = new ConcurrentHashMap<>();
        this.legacyCheckPluginResponses = new ConcurrentHashMap<>();

        if (RoseChatAPI.getInstance().isBungee() && Settings.ALLOW_BUNGEECORD_MESSAGES.get()) {
            // Bukkit plugin messages must be sent from the server thread. Keeping the player list
            // refresh on that thread also means the ArrayListMultimap is never mutated concurrently.
            Bukkit.getScheduler().runTaskTimer(rosePlugin, () -> {
                this.bungeePlayers.get("ALL").clear();
                this.getPlayers("ALL");
            }, 0L, 20L * 5L);
        }
    }

    @Override
    public void reload() {

    }

    @Override
    public void disable() {
        this.checkPluginResponses.clear();
        this.legacyCheckPluginResponses.clear();
    }

    /**
     * Send a generic message to another server.
     * @param command The command to send.
     * @param to The server that the message should go to.
     * @param channel The channel to use.
     * @param msgBytes The bytes of the message.
     * @param msgOut The output stream for the message.
     */
    public void send(String command, String to, String channel, ByteArrayOutputStream msgBytes, DataOutputStream msgOut) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(outputStream);

        try {
            out.writeUTF(command);
            out.writeUTF(to);
            if (channel != null)
                out.writeUTF(channel);

            if (msgBytes != null && msgOut != null) {
                byte[] payload = msgBytes.toByteArray();
                if (payload.length > 0xFFFF) {
                    RoseChat.getInstance().getLogger().warning("Refusing to send an oversized BungeeCord plugin message (" + payload.length + " bytes).");
                    return;
                }

                out.writeShort(payload.length);
                out.write(payload);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        byte[] payload = outputStream.toByteArray();
        Runnable dispatch = () -> {
            Player player = Iterables.getFirst(Bukkit.getOnlinePlayers(), null);
            if (player != null)
                player.sendPluginMessage(RoseChat.getInstance(), "BungeeCord", payload);
        };

        if (Bukkit.isPrimaryThread()) {
            dispatch.run();
        } else {
            Bukkit.getScheduler().runTask(this.rosePlugin, dispatch);
        }
    }

    /**
     * Retrieves the players on the given server.
     * @param server The server to use.
     */
    public void getPlayers(String server) {
        this.send("PlayerList", server, null, null, null);
    }

    public void receivePlayers(String server, String[] players) {
        this.bungeePlayers.putAll(server, Arrays.asList(players));
    }

    //
    // Channel Messages
    //

    private String getPlayerPermissions(RosePlayer sender) {
        if (sender.isConsole() || (sender.isPlayer() && sender.asPlayer().isOp()))
            return "*";

        StringBuilder stringBuilder = new StringBuilder();
        for (String permission : sender.getPermissions()) {
            if (stringBuilder.length() != 0)
                stringBuilder.append(",");

            stringBuilder.append(permission);
        }

        return stringBuilder.toString();
    }

    /**
     * Sends a BungeeCord message to the specified channel on the specified server.
     * The sending and receiving server may have different formats.
     * Each server parses the message independently.
     * This means that permissions and placeholders are sent to the receiving server.
     * Due to this, any custom Tokenizers should start their permission with 'rosechat.', to avoid sending every single permission over the network.
     * @param sender The {@link RosePlayer} who sent the message.
     * @param server The server that should receive the message.
     * @param channel The channel that should receive the message.
     * @param messageId The {@link UUID} of the message that should be sent.
     * @param message The unformatted message that should be sent.
     */
    public void sendChannelMessage(RosePlayer sender, String server, String channel, UUID messageId, boolean isJson, String message) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(outputStream);

        try {
            out.writeLong(System.currentTimeMillis());
            out.writeUTF(channel);
            out.writeUTF(sender.getRealName());
            out.writeUTF(sender.getUUID() == null ? "null" : sender.getUUID().toString());
            out.writeUTF(sender.getPermissionGroup());
            out.writeUTF(this.getPlayerPermissions(sender));
            out.writeUTF(messageId == null ? "null" : messageId.toString());
            out.writeBoolean(isJson);
            out.writeUTF(PlaceholderAPIHook.applyPlaceholders(sender.isPlayer() ? sender.asPlayer() : null, message));
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        this.send("Forward", server, "rosechat:channel_message", outputStream, out);
    }

    /**
     * Called when the server receives a "channel_message" message.
     */
    public void receiveChannelMessage(String channelStr, String senderStr, UUID senderUUID, String senderGroup, List<String> permissions,
                                      UUID messageId, boolean isJson, String message) {
        Channel channel = this.rosePlugin.getManager(ChannelManager.class).getChannel(channelStr);
        if (channel == null)
            return;

        RosePlayer sender = senderUUID == null
                ? new RosePlayer(senderStr, senderGroup)
                : new RosePlayer(senderUUID, senderStr, senderGroup);
        sender.setIgnoredPermissions(permissions);

        RoseChat.MESSAGE_THREAD_POOL.execute(() -> {
            ChannelMessageOptions options = new ChannelMessageOptions.Builder()
                    .sender(sender)
                    .message(message)
                    .messageId(messageId)
                    .isJson(isJson)
                    .build();
            channel.send(options);
        });
    }

    //
    // Direct Messages
    //

    public void sendDirectMessage(RosePlayer sender, String receiver, String json, String message, Consumer<Boolean> callback) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(outputStream);

        try {
            out.writeUTF(sender.getRealName());
            out.writeUTF(sender.getUUID() == null ? "null" : sender.getUUID().toString());
            out.writeUTF(sender.getPermissionGroup());
            out.writeUTF(this.getPlayerPermissions(sender));
            out.writeUTF(json == null ? "" : json);
            out.writeUTF(PlaceholderAPIHook.applyPlaceholders(sender.isPlayer() ? sender.asPlayer() : null, message));
        } catch (IOException e) {
            e.printStackTrace();
            this.completePluginCheck(callback, false);
            return;
        }

        this.sendPluginCheck(sender.getRealName(), receiver, "RoseChat", hasPlugin -> {
            if (hasPlugin)
                this.send("ForwardToPlayer", receiver, "rosechat:direct_message", outputStream, out);

            callback.accept(hasPlugin);
        });
    }

    public void receiveDirectMessage(Player player, String senderStr, UUID senderUUID, String group, List<String> permissions, String json, String message) {
        RosePlayer sender = senderUUID == null
                ? new RosePlayer(senderStr, group)
                : new RosePlayer(senderUUID, senderStr, group);
        sender.setIgnoredPermissions(permissions);

        PlayerData playerData = this.rosePlugin.getManager(PlayerDataManager.class).getPlayerData(player.getUniqueId());
        boolean canBypassToggle = senderUUID == null || permissions.stream()
                .anyMatch(permission -> permission.equals("*") || permission.equalsIgnoreCase("togglemessage.bypass"));
        if ((!canBypassToggle && !playerData.canBeMessaged())
                || (senderUUID != null && playerData.getIgnoringPlayers().contains(senderUUID)))
            return;

        if (json == null || json.isEmpty())
            MessageUtils.sendPrivateMessage(sender, player.getName(), message);
        else
            MessageUtils.sendPrivateJsonMessage(sender, player.getName(), json, message);
    }

    //
    // Plugin Check
    //

    /**
     * Checks if a plugin is on a player's server. New RoseChat builds correlate the response with
     * a UUID so concurrent checks by the same sender cannot consume each other's confirmation.
     * Older builds omit the UUID; the sender-name fallback is retained for rolling upgrades.
     */
    public void sendPluginCheck(String sender, String receiver, String plugin, Consumer<Boolean> callback) {
        UUID requestId = UUID.randomUUID();
        this.legacyCheckPluginResponses.remove(sender);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(outputStream);

        try {
            out.writeUTF(sender);
            out.writeUTF(plugin);
            out.writeUTF(requestId.toString());
        } catch (IOException e) {
            e.printStackTrace();
            this.completePluginCheck(callback, false);
            return;
        }

        this.send("ForwardToPlayer", receiver, "rosechat:check_plugin", outputStream, out);

        Bukkit.getScheduler().runTaskAsynchronously(this.rosePlugin, () -> {
            int timeout = Settings.BUNGEECORD_MESSAGE_TIMEOUT.get();
            long deadline = System.currentTimeMillis() + timeout;
            while (System.currentTimeMillis() < deadline) {
                Boolean response = this.checkPluginResponses.remove(requestId);
                if (response != null) {
                    this.completePluginCheck(callback, response);
                    return;
                }

                // Compatibility with a remote RoseChat build that predates request IDs.
                Boolean legacyResponse = this.legacyCheckPluginResponses.remove(sender);
                if (legacyResponse != null) {
                    this.completePluginCheck(callback, legacyResponse);
                    return;
                }

                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0)
                    break;

                try {
                    Thread.sleep(Math.min(10L, remaining));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    this.completePluginCheck(callback, false);
                    return;
                }
            }

            this.checkPluginResponses.remove(requestId);
            this.completePluginCheck(callback, false);
        });
    }

    public void receivePluginCheck(String sender, String plugin, UUID requestId) {
        this.sendPluginCheckConfirmation(sender,
                Bukkit.getServer().getPluginManager().getPlugin(plugin) != null,
                requestId);
    }

    public void sendPluginCheckConfirmation(String sender, boolean hasPlugin, UUID requestId) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(outputStream);

        try {
            out.writeBoolean(hasPlugin);
            out.writeUTF(sender);
            if (requestId != null)
                out.writeUTF(requestId.toString());
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        this.send("ForwardToPlayer", sender, "rosechat:confirm_plugin", outputStream, out);
    }

    public void receivePluginCheckConfirmation(String player, boolean hasPlugin, UUID requestId) {
        if (requestId != null) {
            this.checkPluginResponses.put(requestId, hasPlugin);
        } else {
            this.legacyCheckPluginResponses.put(player, hasPlugin);
        }
    }

    private void completePluginCheck(Consumer<Boolean> callback, boolean result) {
        Runnable completion = () -> callback.accept(result);
        if (Bukkit.isPrimaryThread()) {
            completion.run();
        } else {
            Bukkit.getScheduler().runTask(this.rosePlugin, completion);
        }
    }

    //
    // Message Replies
    //

    public void sendUpdateReply(String sender, String receiver) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(outputStream);

        try {
            out.writeUTF(sender);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        this.send("ForwardToPlayer", receiver, "rosechat:update_reply", outputStream, out);
    }

    public void receiveUpdateReply(Player player, String sender) {
        PlayerData data = this.rosePlugin.getManager(PlayerDataManager.class).getPlayerData(player.getUniqueId());
        if (data != null) {
            data.setReplyTo(sender);
            data.save();
        }
    }

    //
    // Message Deletion
    //

    public void sendMessageDeletion(String server, UUID messageId) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(outputStream);

        try {
            out.writeUTF(messageId.toString());
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        this.send("Forward", server, "rosechat:delete_message", outputStream, out);
    }

    public void receiveMessageDeletion(UUID messageId) {
        for (Player player : Bukkit.getOnlinePlayers())
            RoseChatAPI.getInstance().deleteMessage(new RosePlayer(player), messageId);
    }

    public Collection<String> getAllPlayers() {
        return this.bungeePlayers.get("ALL");
    }

    public Multimap<String, String> getBungeePlayers() {
        return this.bungeePlayers;
    }

}
