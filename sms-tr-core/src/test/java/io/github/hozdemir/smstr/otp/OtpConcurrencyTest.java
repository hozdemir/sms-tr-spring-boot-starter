package io.github.hozdemir.smstr.otp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.hozdemir.smstr.DeliveryReport;
import io.github.hozdemir.smstr.MessageId;
import io.github.hozdemir.smstr.PhoneNumber;
import io.github.hozdemir.smstr.ProviderId;
import io.github.hozdemir.smstr.SendResult;
import io.github.hozdemir.smstr.SmsClient;
import io.github.hozdemir.smstr.SmsMessage;

/**
 * Two requests for the same number race. The store pauses every lookup until both callers have made one, which
 * reproduces the interleaving where neither has written yet.
 */
class OtpConcurrencyTest {

    private static final PhoneNumber PHONE = PhoneNumber.of("05321234567");

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC);
    private final List<SmsMessage> sent = new CopyOnWriteArrayList<>();
    private final RacingStore store = new RacingStore(new InMemoryOtpStore(clock));
    private final OtpService otp = new OtpService(new RecordingClient(sent), store,
            new OtpPolicy(6, Duration.ofMinutes(3), 5, Duration.ofSeconds(60), "Code: {code}"), clock);
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void concurrentRequestsSendOneCode() throws Exception {
        List<OtpSendResult> results = race(() -> otp.send(PHONE, "login"));

        assertThat(sent).hasSize(1);
        assertThat(results).filteredOn(OtpSendResult.Throttled.class::isInstance).hasSize(1);
    }

    @Test
    void aCodeCanBeUsedOnlyOnce() throws Exception {
        store.racing = false;
        otp.send(PHONE, "login");
        Matcher matcher = Pattern.compile("Code: (\\d+)").matcher(sent.get(0).text());
        assertThat(matcher.matches()).isTrue();
        String code = matcher.group(1);
        store.racing = true;

        List<OtpVerification> results = race(() -> otp.verify(PHONE, "login", code));

        assertThat(results).containsOnlyOnce(OtpVerification.VERIFIED);
    }

    private <T> List<T> race(Callable<T> action) throws Exception {
        List<Future<T>> futures = pool.invokeAll(List.of(action, action));
        return List.of(futures.get(0).get(), futures.get(1).get());
    }

    private static final class RacingStore implements OtpStore {

        private final OtpStore delegate;
        private final CyclicBarrier bothLookedUp = new CyclicBarrier(2);
        volatile boolean racing = true;

        RacingStore(OtpStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<Entry> find(String key) {
            Optional<Entry> found = delegate.find(key);
            if (racing) {
                try {
                    bothLookedUp.await(500, TimeUnit.MILLISECONDS);
                }
                catch (Exception e) {
                    // The other caller never looked up; nothing to line up with.
                }
            }
            return found;
        }

        @Override
        public Optional<Entry> saveUnlessRecent(String key, Entry entry, Duration cooldown) {
            return delegate.saveUnlessRecent(key, entry, cooldown);
        }

        @Override
        public int incrementAttempts(String key) {
            return delegate.incrementAttempts(key);
        }

        @Override
        public boolean remove(String key, Entry entry) {
            return delegate.remove(key, entry);
        }
    }

    private record RecordingClient(List<SmsMessage> sent) implements SmsClient {

        @Override
        public SendResult send(SmsMessage message) {
            sent.add(message);
            return new SendResult(new MessageId(ProviderId.of("test"), String.valueOf(sent.size())),
                    message.recipients());
        }

        @Override
        public List<DeliveryReport> deliveryReports(MessageId messageId) {
            return List.of();
        }

        @Override
        public SmsClient using(ProviderId provider) {
            return this;
        }
    }
}
