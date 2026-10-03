package dev.rosewood.rosechat.moderation.ai.central;

import dev.rosewood.rosechat.moderation.ai.AiModerationConfig;
import java.util.Objects;
import java.util.function.Function;

/**
 * Resolves central-service credentials from configuration.
 *
 * <ul>
 *   <li>Client ID: explicit {@code central.client-id} value, else the
 *       {@code central.client-id-environment-variable}.</li>
 *   <li>Bearer token: <strong>only</strong> the
 *       {@code central.token-environment-variable}. It is never read from the
 *       YAML file and never committed.</li>
 * </ul>
 *
 * <p>Credential <em>values</em> are never logged. Diagnostics report only
 * where each credential was sourced from ({@code config} vs {@code env:NAME})
 * or that it is missing.</p>
 */
public final class CentralCredentials {
    private final String clientId;
    private final String clientIdSource;
    private final String token;
    private final String tokenSource;

    private CentralCredentials(String clientId, String clientIdSource, String token, String tokenSource) {
        this.clientId = clientId;
        this.clientIdSource = clientIdSource;
        this.token = token;
        this.tokenSource = tokenSource;
    }

    /**
     * Resolves credentials using the real process environment.
     */
    public static CentralCredentials resolve(AiModerationConfig config) {
        return resolve(config, System::getenv);
    }

    /**
     * Resolves credentials with an injectable environment lookup (tests).
     */
    static CentralCredentials resolve(AiModerationConfig config, Function<String, String> environment) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(environment, "environment");
        String clientId = config.centralClientId();
        String clientIdSource = "config";
        if (clientId.isBlank()) {
            clientId = blankToEmpty(environment.apply(config.centralClientIdEnvironmentVariable()));
            clientIdSource = clientId.isBlank() ? "missing" : "env:" + config.centralClientIdEnvironmentVariable();
        }
        String token = blankToEmpty(environment.apply(config.centralTokenEnvironmentVariable()));
        String tokenSource = token.isBlank() ? "missing" : "env:" + config.centralTokenEnvironmentVariable();
        return new CentralCredentials(clientId.trim(), clientIdSource, token.trim(), tokenSource);
    }

    private static String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    /**
     * @return {@code true} when both the client ID and the bearer token resolved.
     */
    public boolean complete() {
        return !clientId.isBlank() && !token.isBlank();
    }

    public String clientId() {
        return clientId;
    }

    /**
     * @return the bearer secret. Callers must only place it in the
     * {@code Authorization} header; never log, persist, or embed it elsewhere.
     */
    public String token() {
        return token;
    }

    /**
     * @return a safe, non-secret description of credential provenance for
     * health/status output, e.g. {@code "client-id via config, token via env:ROSECHAT_MODERATION_TOKEN"}.
     */
    public String safeSummary() {
        return "client-id " + describe(clientId, clientIdSource)
                + ", token " + describe(token, tokenSource);
    }

    private static String describe(String value, String source) {
        if (value.isBlank()) {
            return "MISSING";
        }
        return "configured via " + source;
    }
}
