package io.github.hozdemir.smstr;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.github.hozdemir.smstr.spi.SmsProvider;

/** The registered providers, keyed by id. */
public final class SmsProviders {

    private final Map<ProviderId, SmsProvider> byId;

    private SmsProviders(Map<ProviderId, SmsProvider> byId) {
        this.byId = byId;
    }

    public static SmsProviders of(Collection<? extends SmsProvider> providers) {
        Map<ProviderId, SmsProvider> byId = new LinkedHashMap<>();
        for (SmsProvider provider : providers) {
            SmsProvider existing = byId.putIfAbsent(provider.id(), provider);
            if (existing != null) {
                throw new IllegalStateException("Two SMS providers share the id '" + provider.id() + "': "
                        + existing.getClass().getName() + " and " + provider.getClass().getName());
            }
        }
        return new SmsProviders(Map.copyOf(byId));
    }

    public SmsProvider get(ProviderId id) {
        SmsProvider provider = byId.get(id);
        if (provider == null) {
            throw new SmsException("No SMS provider '" + id + "' is configured; available: " + byId.keySet());
        }
        return provider;
    }

    public Set<ProviderId> ids() {
        return byId.keySet();
    }
}
