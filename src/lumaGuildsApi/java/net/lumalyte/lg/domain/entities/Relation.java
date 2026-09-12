package net.lumalyte.lg.domain.entities;

import java.util.UUID;

public abstract class Relation {
    public abstract UUID getOtherGuild(UUID guildId);

    public abstract boolean isActive();
}
