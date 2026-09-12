package net.lumalyte.lg.domain.events;

import java.util.UUID;

public abstract class GuildMemberRemovedEvent {
    public abstract UUID getPlayerId();
}
