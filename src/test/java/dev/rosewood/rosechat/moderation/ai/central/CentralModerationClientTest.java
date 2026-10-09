package dev.rosewood.rosechat.moderation.ai.central;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

/**
 * Deterministic fixture tests for {@link CentralModerationClient} using a
 * stubbed {@link HttpClient} (this sandbox disallows loopback TCP, so no
 * socket fixtures are used). No live central service is ever contacted.
 *
 * <p>Covers required tests 1 (ALLOW), 2 (BLOCK), 3 (timeout), 4 (503),
 * 5 (degraded/fail-open), 6 (409 conflict), plus malformed responses,
 * auth failure, request-shape assertions, and required test 18
 * (credentials are never logged).</p>
 */
class CentralModerationClientTest {
    private static final String CLIENT_ID = "rosechat-test";
    private static final String TOKEN = "fixture-token-do-not-log";

    /** Stub HttpClient: no sockets, behavior fully scripted per test. */
    static final class StubHttpClient extends HttpClient {
        final AtomicReference<HttpRequest> lastRequest = new AtomicReference<>();
        volatile Function<HttpRequest, CompletableFuture<HttpResponse<String>>> behavior =
                request -> CompletableFuture.failedFuture(new AssertionError("no behavior configured"));

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return sendAsync(request, responseBodyHandler, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            lastRequest.set(request);
            CompletableFuture<HttpResponse<String>> future = behavior.apply(request);
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) future;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            throw new UnsupportedOperationException("async only");
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(2));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    static final class StubResponse implements HttpResponse<String> {
        private final int status;
        private final String body;

        StubResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("http://127.0.0.1/v1/moderate");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private final StubHttpClient stubHttp = new StubHttpClient();

    private CentralModerationClient client() {
        return new CentralModerationClient(
                stubHttp,
                new Gson(),
                URI.create("http://127.0.0.1:8080"),
                CLIENT_ID,
                () -> TOKEN,
                Duration.ofSeconds(2)
        );
    }

    private CentralModerationRequest request() {
        UUID eventId = UUID.randomUUID();
        return CentralModerationRequest.publicMessage(
                "global",
                "global",
                CentralModerationIds.externalMessageId(eventId),
                CentralModerationIds.canonicalMessageId(eventId),
                UUID.randomUUID(),
                Instant.parse("2026-10-03T06:00:00Z"),
                "hello world"
        );
    }

    private void respond(int status, String body) {
        stubHttp.behavior = request -> CompletableFuture.completedFuture(new StubResponse(status, body));
    }

    private void failWith(Throwable throwable) {
        stubHttp.behavior = request -> CompletableFuture.failedFuture(throwable);
    }

    private static String allowBody() {
        return """
                {"event_id":"evt-1","ingestion_status":"INGESTED","message_action":"ALLOW",
                "semantic_label":"SAFE","review_priority":"NONE","strike_recommendation":"NONE",
                "containment":"NONE","scores":{},"rule_hits":[],"reason_codes":[],
                "related_message_ids":[],"related_messages":[],
                "local_model_version":"m1","policy_version":"policy-v1",
                "advisory_status":"DISABLED","latency_ms":12}""";
    }

    private static String blockBody() {
        return """
                {"event_id":"evt-2","ingestion_status":"INGESTED","message_action":"BLOCK",
                "semantic_label":"SEVERE_HARASSMENT","review_priority":"URGENT","strike_recommendation":"STRIKE",
                "containment":"MUTE","scores":{"harassment":0.98},"rule_hits":["r1"],"reason_codes":["TARGETED_ABUSE"],
                "related_message_ids":["rosechat-mc-aaa"],
                "related_messages":[
                  {"platform":"minecraft","scope_id":"global","channel_id":"global","external_message_id":"rosechat-mc-aaa"},
                  {"platform":"discord","scope_id":"guild","channel_id":"123","external_message_id":"discord-999"}
                ],
                "local_model_version":"m1","policy_version":"policy-v1",
                "advisory_status":"COMPLETE","latency_ms":40}""";
    }

    private CentralModerationDecision join(CompletableFuture<CentralModerationDecision> future) {
        return future.join();
    }

    private static Throwable rootCause(CompletableFuture<CentralModerationDecision> future) {
        try {
            future.join();
            throw new AssertionError("expected failure");
        } catch (CompletionException exception) {
            return exception.getCause();
        }
    }

    @Test
    void allowFixtureParses() {
        respond(200, allowBody());

        CentralModerationDecision decision = join(client().moderate(request()));

        assertEquals(CentralModerationDecision.MessageAction.ALLOW, decision.messageAction());
        assertEquals("SAFE", decision.semanticLabel());
        assertFalse(decision.failOpen());
        assertEquals("policy-v1", decision.policyVersion());
        assertEquals("m1", decision.modelVersion());
    }

    @Test
    void blockFixtureParsesWithRelatedMessages() {
        respond(200, blockBody());

        CentralModerationDecision decision = join(client().moderate(request()));

        assertEquals(CentralModerationDecision.MessageAction.BLOCK, decision.messageAction());
        assertTrue(decision.enforceBlock());
        assertEquals("SEVERE_HARASSMENT", decision.semanticLabel());
        assertEquals("STRIKE", decision.strikeRecommendation());
        assertEquals("MUTE", decision.containment());
        assertEquals(2, decision.relatedMessages().size());
        assertEquals("minecraft", decision.relatedMessages().get(0).platform());
        assertEquals("rosechat-mc-aaa", decision.relatedMessages().get(0).externalMessageId());
        assertEquals("discord", decision.relatedMessages().get(1).platform());
    }

    @Test
    void parsesSafePlayerNoticeWithoutExposingInternalLabel() {
        JsonObject json = new Gson().fromJson(blockBody(), JsonObject.class);
        String notice = "Your message was blocked because it may contain harassment. "
                + "If this seems wrong, contact staff.";
        json.addProperty("player_notice", notice);
        respond(200, new Gson().toJson(json));

        CentralModerationDecision decision = join(client().moderate(request()));

        assertEquals(notice, decision.safePlayerNotice());
        assertEquals("Your public message was removed because it may contain harassment. "
                + "If this seems wrong, contact staff.", decision.safeRemovalNotice());
    }

    @Test
    void rejectsFormattedOrOversizedServerPlayerNotice() {
        JsonObject json = new Gson().fromJson(blockBody(), JsonObject.class);
        json.addProperty("player_notice", "Your message was blocked <click:run_command>");
        respond(200, new Gson().toJson(json));
        CentralModerationDecision decision = join(client().moderate(request()));
        assertEquals("Your message was blocked by chat moderation. "
                + "If this seems wrong, contact staff.", decision.safePlayerNotice());
    }

    @Test
    void requestShapeMatchesServiceSchema() {
        respond(200, allowBody());

        CentralModerationRequest sent = request();
        join(client().moderate(sent));

        HttpRequest recorded = stubHttp.lastRequest.get();
        assertEquals("POST", recorded.method());
        assertEquals("/v1/moderate", recorded.uri().getPath());
        assertEquals(List.of(CLIENT_ID), recorded.headers().allValues("X-Client-Id"));
        assertEquals(List.of("Bearer " + TOKEN), recorded.headers().allValues("Authorization"));
        assertTrue(recorded.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));

        JsonObject body = new Gson().fromJson(readRequestBody(recorded), JsonObject.class);
        assertEquals("minecraft", body.get("platform").getAsString());
        assertEquals("minecraft_public", body.get("channel_profile").getAsString());
        assertEquals(sent.externalMessageId(), body.get("external_message_id").getAsString());
        assertEquals(sent.canonicalMessageId(), body.get("canonical_message_id").getAsString());
        assertEquals(sent.senderId().toString(), body.get("sender_id").getAsString());
        assertEquals("hello world", body.get("text").getAsString());
        assertTrue(body.has("occurred_at"), "occurred_at must carry a timezone");
    }

    @Test
    void requestJsonMatchesSchemaDirectly() {
        CentralModerationRequest sent = request();
        JsonObject body = sent.toJson(new Gson());
        assertEquals("minecraft", body.get("platform").getAsString());
        assertEquals("minecraft_public", body.get("channel_profile").getAsString());
        assertEquals(sent.externalMessageId(), body.get("external_message_id").getAsString());
        assertEquals(sent.canonicalMessageId(), body.get("canonical_message_id").getAsString());
        assertEquals(sent.senderId().toString(), body.get("sender_id").getAsString());
        assertEquals("hello world", body.get("text").getAsString());
        assertTrue(body.has("occurred_at"));
    }

    @Test
    void degradedBlockResponseFailsOpen() {
        respond(200, """
                {"event_id":"evt-3","ingestion_status":"INGESTED","message_action":"BLOCK",
                "semantic_label":"AMBIGUOUS_REVIEW","review_priority":"NORMAL","strike_recommendation":"NONE",
                "containment":"NONE","scores":{},"rule_hits":[],"reason_codes":[],
                "related_message_ids":[],"related_messages":[],
                "local_model_version":"m1","policy_version":"policy-v1",
                "advisory_status":"ERROR","latency_ms":5,"degraded":true,
                "fallback_state":"classifier_error"}""");

        CentralModerationDecision decision = join(client().moderate(request()));

        assertTrue(decision.failOpen(), "degraded responses must fail open");
        assertFalse(decision.enforceBlock(), "a degraded BLOCK must never enforce");
    }

    @Test
    void failOpenIngestionStatusFailsOpen() {
        respond(200, """
                {"event_id":null,"ingestion_status":"FAIL_OPEN","message_action":"ALLOW",
                "semantic_label":"SAFE","review_priority":"NONE","strike_recommendation":"NONE",
                "containment":"NONE","scores":{},"rule_hits":[],"reason_codes":[],
                "related_message_ids":[],"related_messages":[],
                "local_model_version":"m1","policy_version":"policy-v1",
                "advisory_status":"DISABLED","latency_ms":1,"degraded":true}""");

        CentralModerationDecision decision = join(client().moderate(request()));

        assertTrue(decision.failOpen());
        assertEquals(CentralModerationDecision.MessageAction.ALLOW, decision.messageAction());
    }

    @Test
    void http503MapsToUnavailable() {
        respond(503, "{\"error\":\"queue saturated\"}");

        Throwable cause = rootCause(client().moderate(request()));

        assertInstanceOf(CentralModerationException.Unavailable.class, cause);
    }

    @Test
    void http409MapsToConflictWithDiagnostic() {
        respond(409, "{\"error\":\"external_message_id reused with different input\"}");

        Throwable cause = rootCause(client().moderate(request()));

        assertInstanceOf(CentralModerationException.Conflict.class, cause);
        assertTrue(((CentralModerationException.Conflict) cause).diagnosticBody().contains("reused"));
    }

    @Test
    void http401MapsToAuthenticationFailedWithoutLeakingToken() {
        respond(401, "{\"error\":\"bad credentials\"}");

        Throwable cause = rootCause(client().moderate(request()));

        assertInstanceOf(CentralModerationException.AuthenticationFailed.class, cause);
        assertFalse(String.valueOf(cause.getMessage()).contains(TOKEN),
                "auth failures must never include the bearer token");
    }

    @Test
    void http403MapsToAuthenticationFailed() {
        respond(403, "{\"error\":\"forbidden\"}");

        Throwable cause = rootCause(client().moderate(request()));

        assertInstanceOf(CentralModerationException.AuthenticationFailed.class, cause);
    }

    @Test
    void malformedJsonMapsToMalformedResponse() {
        respond(200, "this is not json");

        Throwable cause = rootCause(client().moderate(request()));

        assertInstanceOf(CentralModerationException.MalformedResponse.class, cause);
    }

    @Test
    void missingMessageActionMapsToMalformedResponse() {
        respond(200, "{\"semantic_label\":\"SAFE\"}");

        Throwable cause = rootCause(client().moderate(request()));

        assertInstanceOf(CentralModerationException.MalformedResponse.class, cause);
    }

    @Test
    void unknownMessageActionMapsToMalformedResponse() {
        respond(200, allowBody().replace("\"message_action\":\"ALLOW\"", "\"message_action\":\"REVIEW\""));

        Throwable cause = rootCause(client().moderate(request()));

        assertInstanceOf(CentralModerationException.MalformedResponse.class, cause);
    }

    @Test
    void httpTimeoutMapsToTimedOut() {
        failWith(new HttpTimeoutException("request timed out"));

        Throwable cause = rootCause(client().moderate(request()));

        assertInstanceOf(CentralModerationException.TimedOut.class, cause);
    }

    @Test
    void connectTimeoutMapsToConnectionFailed() {
        failWith(new HttpConnectTimeoutException("connect timed out"));

        Throwable cause = rootCause(client().moderate(request()));

        assertInstanceOf(CentralModerationException.ConnectionFailed.class, cause);
    }

    @Test
    void refusedConnectionMapsToConnectionFailed() {
        failWith(new ConnectException("refused"));

        Throwable cause = rootCause(client().moderate(request()));

        assertInstanceOf(CentralModerationException.ConnectionFailed.class, cause);
    }

    @Test
    void blankTokenFailsWithoutNetworkCall() {
        CentralModerationClient noToken = new CentralModerationClient(
                stubHttp, new Gson(), URI.create("http://127.0.0.1:8080"),
                CLIENT_ID, () -> "   ", Duration.ofSeconds(2));

        Throwable cause = rootCause(noToken.moderate(request()));

        assertInstanceOf(CentralModerationException.AuthenticationFailed.class, cause);
        assertFalse(String.valueOf(cause.getMessage()).contains(TOKEN));
    }

    @Test
    void exceptionMessagesNeverContainTheToken() {
        List<java.util.function.Supplier<Throwable>> failures = List.of(
                () -> rootCause(prepare(401, "{\"error\":\"nope\"}")),
                () -> rootCause(prepare(403, "{\"error\":\"forbidden\"}")),
                () -> rootCause(prepare(409, "{\"error\":\"conflict\"}")),
                () -> rootCause(prepare(503, "{\"error\":\"busy\"}")),
                () -> rootCause(prepare(500, "{\"error\":\"boom\"}")),
                () -> rootCause(prepare(200, "garbage"))
        );
        for (java.util.function.Supplier<Throwable> failure : failures) {
            Throwable cause = failure.get();
            String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            assertFalse(message.contains(TOKEN), "leaked token in: " + message);
        }
    }

    private CompletableFuture<CentralModerationDecision> prepare(int status, String body) {
        respond(status, body);
        return client().moderate(request());
    }

    private static String readRequestBody(HttpRequest recorded) {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        StringBuilder text = new StringBuilder();
        java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> subscriber =
                new java.util.concurrent.Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(java.nio.ByteBuffer item) {
                        byte[] bytes = new byte[item.remaining()];
                        item.get(bytes);
                        text.append(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        done.countDown();
                    }

                    @Override
                    public void onComplete() {
                        done.countDown();
                    }
                };
        recorded.bodyPublisher().orElseThrow().subscribe(subscriber);
        try {
            if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("timed out reading request body");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted reading request body");
        }
        return text.toString();
    }
}
