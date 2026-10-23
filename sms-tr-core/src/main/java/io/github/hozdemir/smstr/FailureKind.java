package io.github.hozdemir.smstr;

/**
 * Why a send failed, classified so retry and failover decisions live in one table instead of in every provider.
 * <p>
 * {@code retryable}: trying the same provider again can succeed and cannot duplicate the message.
 * {@code failover}: another provider, with its own credentials, credit and sender headers, can succeed.
 */
public enum FailureKind {

    /** The request never reached the provider, or it reported a temporary outage. */
    TRANSIENT(true, true),

    RATE_LIMITED(true, true),

    /**
     * The request was sent but no answer came back (read timeout, connection reset, 500/504).
     * The provider may have accepted it, so a retry can send the SMS twice. Off by default;
     * see {@link ResiliencePolicy#retryAmbiguous()}.
     */
    AMBIGUOUS(false, false),

    AUTHENTICATION(false, true),

    INSUFFICIENT_CREDIT(false, true),

    /** Sender header (başlık) not defined, or the account lacks İYS permission for this message type. */
    SENDER_NOT_ALLOWED(false, true),

    /** Invalid number, text or parameters: every provider would refuse it. */
    INVALID_REQUEST(false, false),

    /** The provider answered with an error this library does not recognise; the message was not accepted. */
    UNKNOWN(false, true);

    private final boolean retryable;
    private final boolean failover;

    FailureKind(boolean retryable, boolean failover) {
        this.retryable = retryable;
        this.failover = failover;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public boolean allowsFailover() {
        return failover;
    }
}
