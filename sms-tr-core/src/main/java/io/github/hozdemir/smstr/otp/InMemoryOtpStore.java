package io.github.hozdemir.smstr.otp;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
    public void save(String key, Entry entry) {
        slots.values().removeIf(slot -> slot.entry().expiresAt().isBefore(clock.instant()));
        slots.put(key, new Slot(entry, new AtomicInteger()));
    }

    @Override
    public int incrementAttempts(String key) {
        Slot slot = slots.get(key);
        return slot == null ? Integer.MAX_VALUE : slot.attempts().incrementAndGet();
    }

    @Override
    public void delete(String key) {
        slots.remove(key);
    }
}
