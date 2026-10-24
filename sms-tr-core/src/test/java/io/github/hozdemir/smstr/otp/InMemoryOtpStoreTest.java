package io.github.hozdemir.smstr.otp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
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

    private void save(String key, OtpStore.Entry entry) {
        store.saveUnlessRecent(key, entry, Duration.ZERO);
    }

    private static OtpStore.Entry entry(String hash) {
        return new OtpStore.Entry(hash, "salt", NOW, NOW.plusSeconds(180));
    }

    @Test
    void findsWhatWasSaved() {
        save("login:905321234567", entry("h1"));

        assertThat(store.find("login:905321234567")).contains(entry("h1"));
    }

    @Test
    void missingKeyIsEmpty() {
        assertThat(store.find("login:905000000000")).isEmpty();
    }

    @Test
    void saveReplacesEntryAndResetsAttempts() {
        save("k", entry("h1"));
        store.incrementAttempts("k");
        store.incrementAttempts("k");

        save("k", entry("h2"));

        assertThat(store.find("k")).contains(entry("h2"));
        assertThat(store.incrementAttempts("k")).isEqualTo(1);
    }

    @Test
    void removeDeletesOnlyTheEntryItWasGiven() {
        save("k", entry("h1"));

        assertThat(store.remove("k", entry("other"))).isFalse();
        assertThat(store.remove("k", entry("h1"))).isTrue();
        assertThat(store.remove("k", entry("h1"))).isFalse();
        assertThat(store.find("k")).isEmpty();
    }

    @Test
    void recentEntryBlocksAnotherUntilCooldownPasses() {
        save("k", entry("h1"));
        OtpStore.Entry tooSoon = new OtpStore.Entry("h2", "salt", NOW.plusSeconds(59), NOW.plusSeconds(240));
        OtpStore.Entry later = new OtpStore.Entry("h3", "salt", NOW.plusSeconds(60), NOW.plusSeconds(240));

        assertThat(store.saveUnlessRecent("k", tooSoon, Duration.ofSeconds(60))).contains(entry("h1"));
        assertThat(store.saveUnlessRecent("k", later, Duration.ofSeconds(60))).isEmpty();
        assertThat(store.find("k")).contains(later);
    }

    @Test
    void incrementingAMissingEntryNeverLooksLikeAnAllowedAttempt() {
        assertThat(store.incrementAttempts("absent")).isEqualTo(Integer.MAX_VALUE);
        assertThat(store.find("absent")).isEmpty();
    }

    @Test
    void concurrentAttemptsAreCountedOnceEach() throws Exception {
        save("k", entry("h1"));
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
