package net.lumalyte.lg.application.services;

import java.util.Set;
import java.util.UUID;
import net.lumalyte.lg.domain.entities.Relation;
import net.lumalyte.lg.domain.entities.RelationType;

public interface RelationService {
    Set<Relation> getGuildRelationsByType(UUID guildId, RelationType type);
}
