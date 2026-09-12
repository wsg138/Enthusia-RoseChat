package net.lumalyte.lg.application.services;

import java.util.UUID;
import net.lumalyte.lg.domain.entities.Rank;

public interface RankService {
    Rank getRank(UUID rankId);
}
