package io.github.hozdemir.smstr.otp;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Where pending codes live. The default {@link InMemoryOtpStore} suits a single instance;
 * behind a load balancer register a shared implementation (Redis, a database) as a bean.
 * <p>
 * {@link #saveUnlessRecent} and {@link #remove} must be atomic: concurrent requests rely on them to send at most
 * one code per cooldown and to accept each code once.
 */
public interface OtpStore {

    /** A hashed code; the plain code is never stored. */
    record Entry(String codeHash, String salt, Instant sentAt, Instant expiresAt) {
    }

    Optional<Entry> find(String key);

    /**
     * Store {@code entry} with a fresh attempt count, unless the current entry under {@code key} was sent less than
     * {@code cooldown} before {@code entry.sentAt()}.
     *
     * @return the current entry that prevented the save, or empty when {@code entry} was stored
     */
    Optional<Entry> saveUnlessRecent(String key, Entry entry, Duration cooldown);

    /** Atomically count one verification attempt and return the new total. */
    int incrementAttempts(String key);

    /** Remove {@code key} only while it still holds {@code entry}; {@code true} if this call removed it. */
    boolean remove(String key, Entry entry);
}
