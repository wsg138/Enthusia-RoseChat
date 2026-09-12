package dev.rosewood.rosechat.hook.channel.lumaguilds;

import dev.rosewood.rosechat.RoseChat;
import dev.rosewood.rosechat.api.RoseChatAPI;
import dev.rosewood.rosechat.chat.channel.Channel;
import dev.rosewood.rosechat.hook.channel.ChannelProvider;
import dev.rosewood.rosechat.hook.channel.rosechat.RoseChatChannel;
import dev.rosewood.rosechat.message.RosePlayer;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.lumalyte.lg.LumaGuilds;
import net.lumalyte.lg.application.services.GuildService;
import net.lumalyte.lg.application.services.MemberService;
import net.lumalyte.lg.application.services.RankService;
import net.lumalyte.lg.application.services.RelationService;
import net.lumalyte.lg.domain.entities.Guild;
import net.lumalyte.lg.domain.entities.Member;
import net.lumalyte.lg.domain.entities.Rank;
import net.lumalyte.lg.domain.entities.Relation;
import net.lumalyte.lg.domain.entities.RelationType;
import net.lumalyte.lg.domain.events.GuildDisbandedEvent;
import net.lumalyte.lg.domain.events.GuildMemberJoinEvent;
import net.lumalyte.lg.domain.events.GuildMemberRemovedEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class LumaGuildsChannel extends RoseChatChannel implements Listener {

    private LumaGuildsChannelType channelType;
    private int officerRankPriority;

    public LumaGuildsChannel(ChannelProvider provider) {
        super(provider);

        Bukkit.getPluginManager().registerEvents(this, RoseChat.getInstance());
    }

    @Override
    public void onLoad(String id, ConfigurationSection config) {
        super.onLoad(id, config);

        if (config.contains("channel-type"))
            this.channelType = LumaGuildsChannelType.valueOf(config.getString("channel-type").toUpperCase());

        if (!config.contains("visible-anywhere"))
            this.visibleAnywhere = true;

        if (this.channelType == null)
            this.channelType = LumaGuildsChannelType.GUILD;

        // Rank.priority: 0 = owner (highest). Members at or below this priority count as officers.
        this.officerRankPriority = config.contains("officer-rank-priority")
                ? config.getInt("officer-rank-priority") : 1;
    }

    @EventHandler
    public void onTeamDisband(GuildDisbandedEvent event) {
        for (UUID memberId : event.getMemberIds()) {
            this.onTeamLeaveGeneric(memberId);
        }
    }

    @EventHandler
    public void onTeamLeave(GuildMemberRemovedEvent event) {
        this.onTeamLeaveGeneric(event.getPlayerId());
    }

    @EventHandler
    public void onTeamJoin(GuildMemberJoinEvent event) {
        if (this.autoJoin) {
            Player player = Bukkit.getPlayer(event.getPlayerId());
            if (player == null)
                return;

            RosePlayer rosePlayer = new RosePlayer(player);
            Channel currentChannel = rosePlayer.getPlayerData().getCurrentChannel();
            if (currentChannel == this)
                return;

            if (rosePlayer.switchChannel(this)) {
                RoseChatAPI.getInstance().getLocaleManager().sendMessage(player,
                        "command-channel-joined", StringPlaceholders.of("id", this.getId()));
            }
        }
    }

    private LumaGuilds plugin() {
        return (LumaGuilds) Bukkit.getPluginManager().getPlugin("LumaGuilds");
    }

    private Guild getGuild(UUID playerId) {
        Set<Guild> guilds = plugin().getGuildService().getPlayerGuilds(playerId);
        if (guilds.isEmpty())
            return null;
        Iterator<Guild> it = guilds.iterator();
        return it.next();
    }

    private boolean hasTeam(RosePlayer player) {
        return this.getGuild(player.getUUID()) != null;
    }

    @Override
    public boolean onLogin(RosePlayer player) {
        return super.onLogin(player) && this.hasTeam(player);
    }

    @Override
    public List<Player> getVisibleAnywhereRecipients(RosePlayer sender, World world) {
        List<Player> recipients = new ArrayList<>();

        if (!sender.isPlayer())
            return recipients;

        LumaGuilds plugin = this.plugin();
        if (plugin == null)
            return recipients;

        Guild guild = this.getGuild(sender.getUUID());
        if (guild == null)
            return recipients;

        MemberService memberService = plugin.getMemberService();
        GuildService guildService = plugin.getGuildService();
        RelationService relationService = plugin.getRelationService();
        RankService rankService = plugin.getRankService();

        switch (this.channelType) {
            case GUILD: {
                this.addMembersIfOnline(memberService.getGuildMembers(guild.getId()), sender, recipients, null);
                return recipients;
            }

            case ALLY: {
                // Own guild members + members of allied guilds.
                this.addMembersIfOnline(memberService.getGuildMembers(guild.getId()), sender, recipients, null);

                Set<Relation> allyRelations = relationService.getGuildRelationsByType(guild.getId(), RelationType.ALLY);
                for (Relation relation : allyRelations) {
                    if (!relation.isActive())
                        continue;
                    UUID otherGuildId = relation.getOtherGuild(guild.getId());
                    if (guildService.getGuild(otherGuildId) == null)
                        continue;
                    this.addMembersIfOnline(memberService.getGuildMembers(otherGuildId), sender, recipients, null);
                }
                return recipients;
            }

            case OFFICER: {
                this.addMembersIfOnline(memberService.getGuildMembers(guild.getId()), sender, recipients, member -> {
                    Rank rank = rankService.getRank(member.getRankId());
                    return rank != null && rank.getPriority() <= this.officerRankPriority;
                });
                return recipients;
            }
        }

        return recipients;
    }

    private void addMembersIfOnline(Set<Member> members, RosePlayer sender, List<Player> recipients,
                                    java.util.function.Predicate<Member> filter) {
        for (Member member : members) {
            if (filter != null && !filter.test(member))
                continue;

            Player player = Bukkit.getPlayer(member.getPlayerId());
            if (player == null)
                continue;

            if (recipients.contains(player))
                continue;

            RosePlayer rosePlayer = new RosePlayer(player);
            if (this.getReceiveCondition(sender, rosePlayer))
                recipients.add(player);
        }
    }

    @Override
    public boolean canJoinByCommand(RosePlayer player) {
        return super.canJoinByCommand(player) && this.hasTeam(player);
    }

    @Override
    public StringPlaceholders.Builder getInfoPlaceholders() {
        return super.getInfoPlaceholders()
                .add("type", this.channelType.toString().toLowerCase());
    }

    public enum LumaGuildsChannelType {

        GUILD,
        ALLY,
        OFFICER

    }

}
