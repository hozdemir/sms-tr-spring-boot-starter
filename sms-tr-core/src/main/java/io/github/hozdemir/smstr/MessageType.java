package io.github.hozdemir.smstr;

/**
 * What a message is, in İYS (İleti Yönetim Sistemi) terms. Each provider maps this to its own
 * İYS fields, so callers never deal with {@code iysfilter}, {@code is_commercial} or {@code iysList}.
 */
public enum MessageType {

    /** Bilgilendirme: order status, appointment reminders. Not subject to İYS consent. */
    TRANSACTIONAL,

    /** One-time password. Transactional, and routed to a provider's OTP channel where one exists. */
    OTP,

    /** Ticari ileti to individuals (İYS "BIREYSEL"); delivered only to recipients with consent. */
    COMMERCIAL_INDIVIDUAL,

    /** Ticari ileti to merchants (İYS "TACIR"). */
    COMMERCIAL_MERCHANT;

    public boolean isCommercial() {
        return this == COMMERCIAL_INDIVIDUAL || this == COMMERCIAL_MERCHANT;
    }
}
