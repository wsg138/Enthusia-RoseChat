package dev.rosewood.rosechat.moderation.ai.central;

import dev.rosewood.rosechat.api.staff.ChannelClassification;
import java.util.Objects;

/**
 * Central Policy-v1 channel profile for a RoseChat message surface.
 *
 * <p>Minecraft requests must use a Minecraft profile. Staff-only or otherwise
 * configured-exempt surfaces map to {@link #EXEMPT} and must bypass semantic
 * moderation locally: their text is never submitted to the central service,
 * not even for logging.</p>
 */
public enum ChannelProfile {
    /** Public Minecraft chat surfaces (RoseChat {@link ChannelClassification#PUBLIC}). */
    MINECRAFT_PUBLIC("minecraft_public"),
    /** Private Minecraft message surfaces (RoseChat {@link ChannelClassification#PRIVATE}). */
    MINECRAFT_PRIVATE("minecraft_private"),
    /**
     * Exempt surface. No request is submitted and no central profile string is
     * emitted for this value.
     */
    EXEMPT(null);

    private final String centralName;

    ChannelProfile(String centralName) {
        this.centralName = centralName;
    }

    /**
     * @return the exact profile string the central service expects, or {@code null} for {@link #EXEMPT}.
     */
    public String centralName() {
        return centralName;
    }

    /**
     * Maps a RoseChat channel classification to the central profile.
     *
     * <ul>
     *   <li>{@code PUBLIC} &rarr; {@code minecraft_public}</li>
     *   <li>{@code PRIVATE} &rarr; {@code minecraft_private}</li>
     *   <li>{@code STAFF} &rarr; {@code EXEMPT} (bypassed locally, never submitted)</li>
     * </ul>
     *
     * @param classification RoseChat channel classification, never {@code null}
     * @return the central profile for the surface
     */
    public static ChannelProfile forClassification(ChannelClassification classification) {
        Objects.requireNonNull(classification, "classification");
        return switch (classification) {
            case PUBLIC -> MINECRAFT_PUBLIC;
            case PRIVATE -> MINECRAFT_PRIVATE;
            case STAFF -> EXEMPT;
        };
    }
}
