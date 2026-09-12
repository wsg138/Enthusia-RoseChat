package dev.rosewood.rosechat.manager;

import dev.rosewood.rosechat.api.RoseChatAPI;
import dev.rosewood.rosechat.chat.channel.Channel;
import dev.rosewood.rosechat.command.command.CustomChannelCommand;
import dev.rosewood.rosechat.config.Settings;
import dev.rosewood.rosechat.hook.channel.ChannelProvider;
import dev.rosewood.rosechat.hook.channel.worldguard.WorldGuardChannel;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.config.CommentedFileConfiguration;
import dev.rosewood.rosegarden.manager.Manager;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public class ChannelManager extends Manager {

    private final Map<String, ChannelProvider> channelProviders;
    private final Map<String, Channel> channels;
    private final List<WorldGuardChannel> worldGuardChannels;
    private LocaleManager localeManager;
    private Channel defaultChannel;
    private CommentedFileConfiguration channelsConfig;
    private BukkitTask worldGuardTask;

    public ChannelManager(RosePlugin rosePlugin) {
        super(rosePlugin);

        this.channelProviders = new HashMap<>();
        this.channels = new HashMap<>();
        this.worldGuardChannels = new ArrayList<>();
    }

    @Override
    public void reload() {
        this.localeManager = this.rosePlugin.getManager(LocaleManager.class);

        if (this.worldGuardTask != null) {
            this.worldGuardTask.cancel();
            this.worldGuardTask = null;
        }

        File channelsFile = new File(this.rosePlugin.getDataFolder(), "channels.yml");
        if (!channelsFile.exists())
            this.rosePlugin.saveResource("channels.yml", false);

        this.channelsConfig = CommentedFileConfiguration.loadConfiguration(channelsFile);
        this.registerCommands(this.channelsConfig);

        // Delay generating channels until channel providers are registered.
        Bukkit.getScheduler().runTaskLater(this.rosePlugin, () -> {
            this.generateChannels();
            if (this.channelProviders.containsKey("worldguard")) {
                long interval = Settings.WORLDGUARD_CHECK_INTERVAL.get();
                if (interval != 0) {
                    // Region updates can switch channels and send messages, so they must stay on
                    // the server thread instead of calling Bukkit APIs from an async timer.
                    this.worldGuardTask = Bukkit.getScheduler().runTaskTimer(this.rosePlugin,
                            this::updatePlayerRegions, 0, interval);
                }
            }
        }, 0L);
    }

    @Override
    public void disable() {
        if (this.worldGuardTask != null) {
            this.worldGuardTask.cancel();
            this.worldGuardTask = null;
        }
    }

    /**
     * Registers a {@link ChannelProvider} and automatically creates the files.
     * @param channelProvider The channel provider to register.
     */
    public void register(ChannelProvider channelProvider) {
        this.channelProviders.put(channelProvider.getSupportedPlugin().toLowerCase(), channelProvider);
        this.createProviderChannels(channelProvider);
    }

    private void createProviderChannels(ChannelProvider channelProvider) {
        try {
            if (channelProvider.getChannels() != null) {
                for (Class<? extends Channel> channelClass : channelProvider.getChannels()) {
                    Channel channel = channelClass.getDeclaredConstructor(ChannelProvider.class).newInstance(channelProvider);
                    channel.onLoad();

                    this.channels.put(channel.getId(), channel);

                    if (channelProvider.getSupportedPlugin().equalsIgnoreCase("WorldGuard"))
                        this.worldGuardChannels.add((WorldGuardChannel) channel);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Generates channels from the RoseChat channels.yml file.
     * The {@link Channel#onLoad()} method is called when a channel loads.
     * Uses the registered {@link ChannelProvider}s to decide channel configuration.
     */
    public void generateChannels() {
        this.channels.clear();
        this.worldGuardChannels.clear();
        this.defaultChannel = null;

        // Create the channels that should be created by the providers.
        for (ChannelProvider channelProvider : this.channelProviders.values())
            this.createProviderChannels(channelProvider);

        for (String id : this.channelsConfig.getKeys(false)) {
            String plugin = this.channelsConfig.contains(id + ".plugin") ? this.channelsConfig.getString(id + ".plugin") : "RoseChat";

            if (!this.channelProviders.containsKey(plugin.toLowerCase())) {
                this.localeManager.sendCustomMessage(Bukkit.getConsoleSender(), this.localeManager.getLocaleMessage("prefix") +
                        "&eAttempted to load " + plugin + " channel '" + id + "' but " + plugin + " is not installed!");
                continue;
            }

            ChannelProvider provider = this.channelProviders.get(plugin.toLowerCase());
            this.generateChannel(provider, id);
        }

        if (this.channels.isEmpty()) {
            this.localeManager.sendCustomMessage(Bukkit.getConsoleSender(), this.localeManager.getLocaleMessage("prefix") +
                    "&cNo chat channels could be loaded. Check channels.yml and installed channel providers.");
            return;
        }

        // Resolve the default before inheriting formats. HashMap iteration order is not stable, so
        // doing both in one pass can dereference a null default when a non-default channel appears first.
        for (Channel channel : this.channels.values()) {
            if (channel.getSettings().isDefault()) {
                this.defaultChannel = channel;
                break;
            }
        }

        if (this.defaultChannel == null) {
            this.defaultChannel = this.channels.values().iterator().next();
            this.localeManager.sendCustomMessage(Bukkit.getConsoleSender(), this.localeManager.getLocaleMessage("prefix") +
                    "&eNo default channel was found! The default channel has been automatically set to: " + this.defaultChannel.getId());
        }

        Map<String, List<String>> loadedChannels = new HashMap<>();
        String defaultChatFormat = this.defaultChannel.getSettings().getFormats().get("chat");

        for (Channel channel : this.channels.values()) {
            if (!channel.getSettings().getFormats().containsKey("chat") && defaultChatFormat != null)
                channel.getSettings().getFormats().put("chat", defaultChatFormat);

            loadedChannels.computeIfAbsent(channel.getProvider().getSupportedPlugin(), ignored -> new ArrayList<>())
                    .add(channel.getId());
        }

        for (String provider : loadedChannels.keySet()) {
            String channels = loadedChannels.get(provider).toString();
            this.localeManager.sendCustomMessage(Bukkit.getConsoleSender(), this.localeManager.getLocaleMessage("prefix") +
                    "&eLoaded " + provider + " channels: " + channels.substring(1, channels.length() - 1));
        }
    }

    /**
     * Generates a single channel from a provider, with the given ID.
     * @param provider The provider to generate the channel from.
     * @param id The ID to use.
     */
    public void generateChannel(ChannelProvider provider, String id) {
        Class<? extends Channel> base = provider.getChannelGenerator();

        if (base != null) {
            try {
                Channel channel = base.getDeclaredConstructor(ChannelProvider.class).newInstance(provider);
                channel.onLoad(id, this.channelsConfig.getConfigurationSection(id));
                if (channel.getSettings().isDefault())
                    this.defaultChannel = channel;

                this.channels.put(id, channel);

                if (provider.getSupportedPlugin().equalsIgnoreCase("WorldGuard"))
                    this.worldGuardChannels.add((WorldGuardChannel) channel);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void deleteChannel(String id) {
        this.channels.remove(id);
    }

    /**
     * Searches through a config file for 'commands', and registers those commands.
     * @param config The configuration to search through.
     */
    public void registerCommands(CommentedFileConfiguration config) {
        for (String id : config.getKeys(false)) {
            if (config.contains(id + ".commands")) {
                for (String command : config.getStringList(id + ".commands"))
                    this.registerCommand(command);
            }
        }
    }

    /**
     * Registers a command alias for a channel.
     * @param command The command to register.
     */
    public void registerCommand(String command) {
        try {
            Field bukkitCommandMap = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            bukkitCommandMap.setAccessible(true);
            CommandMap commandMap = (CommandMap) bukkitCommandMap.get(Bukkit.getServer());
            commandMap.register(command, new CustomChannelCommand(command));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Updates the region that players are located in.
     */
    public void updatePlayerRegions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            RosePlayer rosePlayer = new RosePlayer(player);

            for (WorldGuardChannel channel : this.getWorldGuardChannels()) {
                if (channel.getMembers().contains(player.getUniqueId()) && !channel.isInWhitelistedRegion(rosePlayer)) {
                    Channel newChannel = rosePlayer.findChannel();
                    if (newChannel.getMembers().contains(player.getUniqueId()))
                        break;

                    if (rosePlayer.switchChannel(newChannel)) {
                        String joinMessage = newChannel.getSettings().getFormats().get("join-message");
                        if (joinMessage != null)
                            rosePlayer.send(RoseChatAPI.getInstance().parse(rosePlayer, rosePlayer, joinMessage));
                    }

                    break;
                }

                if (!channel.getMembers().contains(player.getUniqueId()) && channel.isInWhitelistedRegion(rosePlayer)) {
                    if (rosePlayer.switchChannel(channel)) {
                        String joinMessage = channel.getSettings().getFormats().get("join-message");
                        if (joinMessage != null)
                            rosePlayer.send(RoseChatAPI.getInstance().parse(rosePlayer, rosePlayer, joinMessage));
                    }

                    break;
                }
            }
        }
    }

    public Map<String, ChannelProvider> getChannelProviders() {
        return this.channelProviders;
    }

    public Map<String, Channel> getChannels() {
        return this.channels;
    }

    public Channel getChannel(String id) {
        return this.channels.get(id);
    }

    public Channel getDefaultChannel() {
        return this.defaultChannel;
    }

    public void setDefaultChannel(Channel defaultChannel) {
        this.defaultChannel = defaultChannel;
    }

    public CommentedFileConfiguration getChannelsConfig() {
        return this.channelsConfig;
    }

    public List<WorldGuardChannel> getWorldGuardChannels() {
        return this.worldGuardChannels;
    }

}
