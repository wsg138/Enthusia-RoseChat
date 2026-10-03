package dev.rosewood.rosechat.moderation.ai.central;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * HTTP client for the central Policy-v1 moderation service
 * ({@code POST /v1/moderate}).
 *
 * <p>Authentication uses {@code X-Client-Id} plus
 * {@code Authorization: Bearer &lt;secret&gt;}. Credentials are never logged:
 * error messages carry only the HTTP status and a sanitized, truncated body.
 * The bearer token is supplied lazily via {@link Supplier} and is only ever
 * placed in the request header.</p>
 *
 * <p>All I/O is asynchronous ({@link HttpClient#sendAsync}); this client never
 * blocks the calling thread.</p>
 */
public final class CentralModerationClient {
    private static final int MAX_ERROR_DETAIL_LENGTH = 300;

    private final HttpClient http;
    private final Gson gson;
    private final URI moderateUri;
    private final String clientId;
    private final Supplier<String> tokenSupplier;
    private final Duration requestTimeout;

    /**
     * @param http           shared async HTTP client
     * @param gson           JSON codec
     * @param baseUri        central service base URI, e.g. {@code http://10.0.0.5:8080}
     * @param clientId       value for the {@code X-Client-Id} header
     * @param tokenSupplier  supplies the bearer secret per request; never logged or persisted
     * @param requestTimeout per-request deadline applied to connect and read
     */
    public CentralModerationClient(
            HttpClient http,
            Gson gson,
            URI baseUri,
            String clientId,
            Supplier<String> tokenSupplier,
            Duration requestTimeout
    ) {
        this.http = Objects.requireNonNull(http, "http");
        this.gson = Objects.requireNonNull(gson, "gson");
        Objects.requireNonNull(baseUri, "baseUri");
        String scheme = baseUri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("central base URI must be http or https");
        }
        String base = baseUri.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.moderateUri = URI.create(base + "/v1/moderate");
        this.clientId = Objects.requireNonNull(clientId, "clientId").trim();
        if (this.clientId.isEmpty()) {
            throw new IllegalArgumentException("central client id must not be blank");
        }
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("request timeout must be positive");
        }
    }

    /**
     * Submits one moderation request. The returned future completes with the
     * parsed decision, or exceptionally with a {@link CentralModerationException}
     * subtype. The caller fails open on every exception type.
     */
    public CompletableFuture<CentralModerationDecision> moderate(CentralModerationRequest request) {
        Objects.requireNonNull(request, "request");
        String token;
        try {
            token = tokenSupplier.get();
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(
                    new CentralModerationException.RequestFailed("central token supplier failed", exception));
        }
        if (token == null || token.isBlank()) {
            return CompletableFuture.failedFuture(new CentralModerationException.AuthenticationFailed(
                    "central bearer token is not configured"));
        }
        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(moderateUri)
                    .timeout(requestTimeout)
                    .header("X-Client-Id", clientId)
                    .header("Authorization", "Bearer " + token.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(request.toJson(gson))))
                    .build();
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(
                    new CentralModerationException.RequestFailed("could not build central request", exception));
        }
        return http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleResponse)
                .exceptionally(throwable -> {
                    throw translateTransportFailure(throwable);
                });
    }

    private CentralModerationDecision handleResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new CentralModerationException.AuthenticationFailed(
                    "central service rejected credentials (HTTP " + status + ")");
        }
        if (status == 409) {
            throw new CentralModerationException.Conflict(
                    "central service reported an idempotency conflict (HTTP 409); failing open without regenerating IDs",
                    sanitize(response.body()));
        }
        if (status == 503) {
            throw new CentralModerationException.Unavailable(
                    "central service unavailable (HTTP 503); failing open");
        }
        if (status < 200 || status >= 300) {
            throw new CentralModerationException.RequestFailed(
                    "central service returned HTTP " + status + sanitizeSuffix(response.body()));
        }
        return parseDecision(response.body());
    }

    private RuntimeException translateTransportFailure(Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        if (cause instanceof CentralModerationException central) {
            return central;
        }
        if (cause instanceof java.net.http.HttpConnectTimeoutException) {
            return new CentralModerationException.ConnectionFailed("central connection timed out", cause);
        }
        if (cause instanceof HttpTimeoutException) {
            return new CentralModerationException.TimedOut("central request timed out", cause);
        }
        if (cause instanceof ConnectException) {
            return new CentralModerationException.ConnectionFailed("could not connect to central service", cause);
        }
        return new CentralModerationException.RequestFailed(
                "central request failed: " + cause.getClass().getSimpleName(), cause);
    }

    CentralModerationDecision parseDecision(String body) {
        JsonObject root;
        try {
            root = gson.fromJson(body, JsonObject.class);
        } catch (RuntimeException exception) {
            throw new CentralModerationException.MalformedResponse("central response is not JSON", exception);
        }
        if (root == null || !root.isJsonObject()) {
            throw new CentralModerationException.MalformedResponse("central response is not a JSON object");
        }
        try {
            String action = requiredString(root, "message_action");
            CentralModerationDecision.MessageAction messageAction;
            try {
                messageAction = CentralModerationDecision.MessageAction.parse(action);
            } catch (IllegalArgumentException exception) {
                throw new CentralModerationException.MalformedResponse(
                        "central response has unknown message_action: " + sanitize(action));
            }
            return new CentralModerationDecision(
                    messageAction,
                    optionalString(root, "ingestion_status"),
                    root.has("degraded") && !root.get("degraded").isJsonNull()
                            && root.get("degraded").getAsBoolean(),
                    optionalString(root, "semantic_label"),
                    root.has("confidence") && !root.get("confidence").isJsonNull()
                            ? root.get("confidence").getAsDouble() : null,
                    optionalString(root, "review_priority"),
                    optionalString(root, "strike_recommendation"),
                    optionalString(root, "containment"),
                    stringList(root, "reason_codes"),
                    relatedMessages(root),
                    optionalString(root, "policy_version"),
                    optionalString(root, "local_model_version"),
                    optionalString(root, "fallback_state"),
                    root.has("idempotent_replay") && !root.get("idempotent_replay").isJsonNull()
                            && root.get("idempotent_replay").getAsBoolean()
            );
        } catch (CentralModerationException.MalformedResponse malformed) {
            throw malformed;
        } catch (RuntimeException exception) {
            throw new CentralModerationException.MalformedResponse(
                    "central response could not be parsed: " + exception.getClass().getSimpleName(), exception);
        }
    }

    private List<RelatedMessageRef> relatedMessages(JsonObject root) {
        List<RelatedMessageRef> refs = new ArrayList<>();
        if (!root.has("related_messages") || root.get("related_messages").isJsonNull()) {
            return refs;
        }
        JsonElement element = root.get("related_messages");
        if (!element.isJsonArray()) {
            throw new CentralModerationException.MalformedResponse("related_messages is not an array");
        }
        JsonArray array = element.getAsJsonArray();
        for (JsonElement item : array) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject ref = item.getAsJsonObject();
            refs.add(new RelatedMessageRef(
                    optionalString(ref, "platform"),
                    optionalString(ref, "scope_id"),
                    optionalString(ref, "channel_id"),
                    optionalString(ref, "external_message_id")
            ));
        }
        return refs;
    }

    private static String requiredString(JsonObject root, String key) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            throw new CentralModerationException.MalformedResponse("central response is missing " + key);
        }
        return root.get(key).getAsString();
    }

    private static String optionalString(JsonObject root, String key) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return "";
        }
        try {
            return root.get(key).getAsString();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static List<String> stringList(JsonObject root, String key) {
        List<String> values = new ArrayList<>();
        if (!root.has(key) || root.get(key).isJsonNull() || !root.get(key).isJsonArray()) {
            return values;
        }
        for (JsonElement item : root.getAsJsonArray(key)) {
            if (!item.isJsonNull()) {
                values.add(item.getAsString());
            }
        }
        return values;
    }

    private static String sanitizeSuffix(String body) {
        String detail = sanitize(body);
        return detail.isEmpty() ? "" : ": " + detail;
    }

    /**
     * Sanitizes untrusted response text for diagnostics. Never includes
     * request credentials: only the response body is ever passed here.
     */
    static String sanitize(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_ERROR_DETAIL_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_ERROR_DETAIL_LENGTH) + "...";
    }
}
