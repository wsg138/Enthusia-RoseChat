package net.lumalyte.lg;

import net.lumalyte.lg.application.services.GuildService;
import net.lumalyte.lg.application.services.MemberService;
import net.lumalyte.lg.application.services.RankService;
import net.lumalyte.lg.application.services.RelationService;

public abstract class LumaGuilds {
    public abstract GuildService getGuildService();

    public abstract MemberService getMemberService();

    public abstract RankService getRankService();

    public abstract RelationService getRelationService();
}
