package net.lumalyte.lg.domain.events;

import java.util.Set;
import java.util.UUID;

public abstract class GuildDisbandedEvent {
    public abstract Set<UUID> getMemberIds();
}
