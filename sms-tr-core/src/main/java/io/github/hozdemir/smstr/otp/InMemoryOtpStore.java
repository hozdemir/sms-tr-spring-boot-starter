package io.github.hozdemir.smstr.otp;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class InMemoryOtpStore implements OtpStore {

    private record Slot(Entry entry, AtomicInteger attempts) {
    }

    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryOtpStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<Entry> find(String key) {
        return Optional.ofNullable(slots.get(key)).map(Slot::entry);
    }

    @Override
    public Optional<Entry> saveUnlessRecent(String key, Entry entry, Duration cooldown) {
        slots.values().removeIf(slot -> slot.entry().expiresAt().isBefore(clock.instant()));
        AtomicReference<Entry> blocking = new AtomicReference<>();
        slots.compute(key, (k, current) -> {
            if (current != null && entry.sentAt().isBefore(current.entry().sentAt().plus(cooldown))) {
                blocking.set(current.entry());
                return current;
            }
            return new Slot(entry, new AtomicInteger());
        });
        return Optional.ofNullable(blocking.get());
    }

    @Override
    public int incrementAttempts(String key) {
        Slot slot = slots.get(key);
        return slot == null ? Integer.MAX_VALUE : slot.attempts().incrementAndGet();
    }

    @Override
    public boolean remove(String key, Entry entry) {
        AtomicBoolean removed = new AtomicBoolean();
        slots.computeIfPresent(key, (k, current) -> {
            if (!current.entry().equals(entry)) {
                return current;
            }
            removed.set(true);
            return null;
        });
        return removed.get();
    }
}
