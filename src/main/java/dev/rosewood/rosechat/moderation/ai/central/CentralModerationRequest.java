package dev.rosewood.rosechat.moderation.ai.central;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A single {@code POST /v1/moderate} request.
 *
 * <p>The central service owns semantic context, so RoseChat sends the current
 * structured message plus authoritative metadata only. No local prose
 * transcript is attached.</p>
 *
 * <p>Identity fields ({@code sender_id}, {@code recipient_ids}) come from real
 * application state (player UUIDs), never from text inference.</p>
 */
public record CentralModerationRequest(
        ChannelProfile profile,
        String scopeId,
        String channelId,
        String conversationId,
        String externalMessageId,
        String canonicalMessageId,
        UUID senderId,
        List<UUID> recipientIds,
        Instant occurredAt,
        String text,
        String replyToMessageId
) {
    private static final int MAX_TEXT_LENGTH = 4096;

    public CentralModerationRequest {
        Objects.requireNonNull(profile, "profile");
        if (profile == ChannelProfile.EXEMPT) {
            throw new IllegalArgumentException("exempt surfaces must bypass the central service locally");
        }
        Objects.requireNonNull(externalMessageId, "externalMessageId");
        Objects.requireNonNull(canonicalMessageId, "canonicalMessageId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(text, "text");
        if (externalMessageId.isBlank()) {
            throw new IllegalArgumentException("externalMessageId must not be blank");
        }
        if (canonicalMessageId.isBlank()) {
            throw new IllegalArgumentException("canonicalMessageId must not be blank");
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        scopeId = scopeId == null ? "" : scopeId;
        channelId = channelId == null ? "" : channelId;
        conversationId = conversationId == null ? "" : conversationId;
        replyToMessageId = replyToMessageId == null ? "" : replyToMessageId;
        recipientIds = recipientIds == null ? List.of() : List.copyOf(recipientIds);
    }

    /**
     * Serializes the request to the exact {@code ModerationRequest} schema the
     * central service validates with {@code extra="forbid"}. Only declared
     * fields are emitted.
     */
    JsonObject toJson(Gson gson) {
        JsonObject body = new JsonObject();
        body.addProperty("platform", "minecraft");
        body.addProperty("channel_profile", profile.centralName());
        body.addProperty("scope_id", scopeId.isBlank() ? channelId : scopeId);
        if (!channelId.isBlank()) {
            body.addProperty("channel_id", channelId);
        }
        if (!conversationId.isBlank()) {
            body.addProperty("conversation_id", conversationId);
        }
        body.addProperty("external_message_id", externalMessageId);
        body.addProperty("canonical_message_id", canonicalMessageId);
        body.addProperty("sender_id", senderId.toString());
        if (!recipientIds.isEmpty()) {
            JsonArray recipients = new JsonArray();
            for (UUID recipient : recipientIds) {
                recipients.add(recipient.toString());
            }
            body.add("recipient_ids", recipients);
        }
        body.addProperty("occurred_at", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                occurredAt.atOffset(ZoneOffset.UTC)));
        body.addProperty("text", boundedText());
        if (!replyToMessageId.isBlank()) {
            body.addProperty("reply_to_message_id", replyToMessageId);
        }
        return body;
    }

    private String boundedText() {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH);
    }

    /**
     * Builds the canonical request for a public channel message.
     */
    public static CentralModerationRequest publicMessage(
            String scopeId,
            String channelId,
            String externalMessageId,
            String canonicalMessageId,
            UUID senderId,
            Instant occurredAt,
            String text
    ) {
        return new CentralModerationRequest(
                ChannelProfile.MINECRAFT_PUBLIC,
                scopeId,
                channelId,
                "",
                externalMessageId,
                canonicalMessageId,
                senderId,
                List.of(),
                occurredAt,
                text,
                ""
        );
    }

    /**
     * Builds the canonical request for a private message. The private-message
     * surface is currently bypassed locally (unchanged from the legacy path);
     * this constructor exists so the mapping is exercised and tested for any
     * future private-message coverage.
     */
    public static CentralModerationRequest privateMessage(
            String scopeId,
            String conversationId,
            String externalMessageId,
            String canonicalMessageId,
            UUID senderId,
            List<UUID> recipientIds,
            Instant occurredAt,
            String text
    ) {
        return new CentralModerationRequest(
                ChannelProfile.MINECRAFT_PRIVATE,
                scopeId,
                "private",
                conversationId,
                externalMessageId,
                canonicalMessageId,
                senderId,
                new ArrayList<>(recipientIds),
                occurredAt,
                text,
                ""
        );
    }
}
