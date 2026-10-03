package dev.rosewood.rosechat.moderation.ai.central;

import java.util.Objects;

/**
 * Failures of the central moderation client, mapped from transport and
 * protocol outcomes. Every failure mode fails open for ordinary chat.
 */
public class CentralModerationException extends RuntimeException {
    public CentralModerationException(String message) {
        super(message);
    }

    public CentralModerationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The request could not reach the service (connection refused, DNS, reset).
     */
    public static final class ConnectionFailed extends CentralModerationException {
        public ConnectionFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The service did not answer inside the request deadline.
     */
    public static final class TimedOut extends CentralModerationException {
        public TimedOut(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The service answered 503 (queue saturation / deadline). The contract
     * explicitly requires the client to fail open.
     */
    public static final class Unavailable extends CentralModerationException {
        public Unavailable(String message) {
            super(message);
        }
    }

    /**
     * 401/403: the client id or bearer token is missing, wrong, or lacks the
     * {@code moderate} permission. Configuration defect, fails open.
     */
    public static final class AuthenticationFailed extends CentralModerationException {
        public AuthenticationFailed(String message) {
            super(message);
        }
    }

    /**
     * 409: the same external/canonical key was reused with different input.
     * Integration defect: fail open, keep the original IDs, record a bounded
     * diagnostic, alert staff. Never regenerate IDs or retry in a loop.
     */
    public static final class Conflict extends CentralModerationException {
        private final String diagnosticBody;

        public Conflict(String message, String diagnosticBody) {
            super(message);
            this.diagnosticBody = diagnosticBody == null ? "" : diagnosticBody;
        }

        /**
         * @return the sanitized, truncated response body for staff diagnostics.
         */
        public String diagnosticBody() {
            return diagnosticBody;
        }
    }

    /**
     * The service answered 2xx with a body that is not a valid moderation response.
     */
    public static final class MalformedResponse extends CentralModerationException {
        public MalformedResponse(String message) {
            super(message);
        }

        public MalformedResponse(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Any other non-2xx response or request-building failure.
     */
    public static final class RequestFailed extends CentralModerationException {
        public RequestFailed(String message) {
            super(message);
        }

        public RequestFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
