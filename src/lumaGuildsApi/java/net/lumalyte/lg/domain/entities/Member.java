package net.lumalyte.lg.domain.entities;

import java.util.UUID;

public abstract class Member {
    public abstract UUID getPlayerId();

    public abstract UUID getRankId();
}
