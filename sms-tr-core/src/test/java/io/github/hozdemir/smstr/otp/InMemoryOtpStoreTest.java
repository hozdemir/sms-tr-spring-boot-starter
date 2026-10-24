package io.github.hozdemir.smstr.otp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class InMemoryOtpStoreTest {

    private static final Instant NOW = Instant.now();

    private final OtpStore store = new InMemoryOtpStore(Clock.systemUTC());

    private static OtpStore.Entry entry(String hash) {
        return new OtpStore.Entry(hash, "salt", NOW, NOW.plusSeconds(180));
    }

    @Test
    void findsWhatWasSaved() {
        store.save("login:905321234567", entry("h1"));

        assertThat(store.find("login:905321234567")).contains(entry("h1"));
    }

    @Test
    void missingKeyIsEmpty() {
        assertThat(store.find("login:905000000000")).isEmpty();
    }

    @Test
    void saveReplacesEntryAndResetsAttempts() {
        store.save("k", entry("h1"));
        store.incrementAttempts("k");
        store.incrementAttempts("k");

        store.save("k", entry("h2"));

        assertThat(store.find("k")).contains(entry("h2"));
        assertThat(store.incrementAttempts("k")).isEqualTo(1);
    }

    @Test
    void deleteRemovesEntry() {
        store.save("k", entry("h1"));

        store.delete("k");

        assertThat(store.find("k")).isEmpty();
    }

    @Test
    void incrementingAMissingEntryNeverLooksLikeAnAllowedAttempt() {
        assertThat(store.incrementAttempts("absent")).isEqualTo(Integer.MAX_VALUE);
        assertThat(store.find("absent")).isEmpty();
    }

    @Test
    void concurrentAttemptsAreCountedOnceEach() throws Exception {
        store.save("k", entry("h1"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> attempts = IntStream.range(0, 50)
                    .<Callable<Integer>>mapToObj(i -> () -> store.incrementAttempts("k")).toList();
            Set<Integer> counts = pool.invokeAll(attempts).stream().map(InMemoryOtpStoreTest::get)
                    .collect(Collectors.toSet());

            assertThat(counts).hasSize(50).contains(1, 50);
        }
        finally {
            pool.shutdownNow();
        }
    }

    private static Integer get(Future<Integer> future) {
        try {
            return future.get();
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
