package net.lumalyte.lg.application.services;

import java.util.Set;
import java.util.UUID;
import net.lumalyte.lg.domain.entities.Guild;

public interface GuildService {
    Guild getGuild(UUID guildId);

    Set<Guild> getPlayerGuilds(UUID playerId);
}
