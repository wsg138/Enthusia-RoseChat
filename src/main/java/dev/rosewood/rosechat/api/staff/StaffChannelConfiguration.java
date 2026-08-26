package dev.rosewood.rosechat.api.staff;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record StaffChannelConfiguration(
        String staffChannelId,
        String globalChannelId,
        Set<String> privateChannelIds
) {
    public StaffChannelConfiguration {
        staffChannelId = normalizeRequired(staffChannelId, "staffChannelId");
        globalChannelId = globalChannelId == null ? "" : globalChannelId.trim();
        if (!globalChannelId.isEmpty() && staffChannelId.equalsIgnoreCase(globalChannelId)) {
            throw new IllegalArgumentException("The staff and global channels must be different");
        }
        Set<String> normalizedPrivateChannels = new LinkedHashSet<>();
        if (privateChannelIds != null) {
            for (String channelId : privateChannelIds) {
                normalizedPrivateChannels.add(
                        normalizeRequired(channelId, "privateChannelId").toLowerCase(Locale.ROOT)
                );
            }
        }
        privateChannelIds = Set.copyOf(normalizedPrivateChannels);
    }

    public StaffChannelConfiguration(String staffChannelId) {
        this(staffChannelId, "", Set.of());
    }

    private static String normalizeRequired(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
