package io.github.hozdemir.smstr;

/** Provider-independent delivery state of a message to one recipient. */
public enum DeliveryStatus {

    /** Queued, sent to the operator, or waiting for the handset. */
    PENDING(false),
    DELIVERED(true),
    /** The operator or handset reported a failure (unreachable, invalid number, blocked). */
    UNDELIVERED(true),
    /** Validity period ran out before the handset received it. */
    EXPIRED(true),
    /** The provider refused it before sending (İYS consent missing, blacklist, duplicate, no credit). */
    REJECTED(true),
    /** A status code this library does not know; inspect {@link DeliveryReport#providerStatus()}. */
    UNKNOWN(false);

    private final boolean terminal;

    DeliveryStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** Whether the status can no longer change, so polling for it can stop. */
    public boolean isTerminal() {
        return terminal;
    }
}
