package io.github.hozdemir.smstr;

import java.time.Duration;

/**
 * Retry behaviour per provider; failover across providers follows {@link FailureKind#allowsFailover()}.
 *
 * @param maxAttempts total attempts against one provider, including the first
 * @param retryAmbiguous also retry and fail over on {@link FailureKind#AMBIGUOUS}, accepting possible duplicates
 */
public record ResiliencePolicy(
        int maxAttempts,
        Duration initialDelay,
        double multiplier,
        Duration maxDelay,
        boolean retryAmbiguous) {

    public static final ResiliencePolicy DEFAULT =
            new ResiliencePolicy(3, Duration.ofMillis(500), 2.0, Duration.ofSeconds(5), false);

    public static final ResiliencePolicy NO_RETRY =
            new ResiliencePolicy(1, Duration.ZERO, 1.0, Duration.ZERO, false);

    public ResiliencePolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1.0");
        }
        if (maxAttempts > 1 && (initialDelay.isNegative() || initialDelay.isZero()
                || maxDelay.compareTo(initialDelay) < 0)) {
            throw new IllegalArgumentException("Retries need initialDelay > 0 and maxDelay >= initialDelay");
        }
    }

    public boolean shouldRetry(FailureKind kind) {
        return kind.isRetryable() || (retryAmbiguous && kind == FailureKind.AMBIGUOUS);
    }

    public boolean shouldFailover(FailureKind kind) {
        return kind.allowsFailover() || (retryAmbiguous && kind == FailureKind.AMBIGUOUS);
    }
}
