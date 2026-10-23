package io.github.hozdemir.smstr;

/**
 * Identifier a provider assigned to an accepted send request (Netgsm job id, Verimor campaign id, ...).
 * It carries its provider, so a delivery report query always reaches the provider that sent the message,
 * even after a failover.
 */
public record MessageId(ProviderId provider, String value) {

    public MessageId {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Message id must not be blank");
        }
    }

    @Override
    public String toString() {
        return provider + ":" + value;
    }
}
