package net.lumalyte.lg.application.services;

import java.util.Set;
import java.util.UUID;
import net.lumalyte.lg.domain.entities.Member;

public interface MemberService {
    Set<Member> getGuildMembers(UUID guildId);
}
