package net.lumalyte.lg.domain.events;

import java.util.UUID;

public abstract class GuildMemberJoinEvent {
    public abstract UUID getPlayerId();
}
