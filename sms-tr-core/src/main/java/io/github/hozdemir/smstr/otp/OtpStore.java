package io.github.hozdemir.smstr.otp;

import java.time.Instant;
import java.util.Optional;

/**
 * Where pending codes live. The default {@link InMemoryOtpStore} suits a single instance;
 * behind a load balancer register a shared implementation (Redis, a database) as a bean.
 */
public interface OtpStore {

    /** A hashed code; the plain code is never stored. */
    record Entry(String codeHash, String salt, Instant sentAt, Instant expiresAt) {
    }

    Optional<Entry> find(String key);

    /** Store {@code entry} under {@code key}, replacing any previous one and resetting its attempt count. */
    void save(String key, Entry entry);

    /** Atomically count one verification attempt and return the new total. */
    int incrementAttempts(String key);

    void delete(String key);
}
