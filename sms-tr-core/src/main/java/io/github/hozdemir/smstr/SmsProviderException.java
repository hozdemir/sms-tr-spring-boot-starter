package io.github.hozdemir.smstr;

import org.jspecify.annotations.Nullable;

/**
 * A provider refused or failed a request.
 *
 * @see FailureKind
 */
public class SmsProviderException extends SmsException {

    private final ProviderId provider;
    private final FailureKind kind;
    private final @Nullable String providerCode;

    public SmsProviderException(ProviderId provider, FailureKind kind, @Nullable String providerCode, String message) {
        this(provider, kind, providerCode, message, null);
    }

    public SmsProviderException(ProviderId provider, FailureKind kind, @Nullable String providerCode, String message,
            @Nullable Throwable cause) {
        super("[" + provider + "] " + kind + (providerCode != null ? " (" + providerCode + ")" : "") + ": " + message,
                cause);
        this.provider = provider;
        this.kind = kind;
        this.providerCode = providerCode;
    }

    public ProviderId provider() {
        return provider;
    }

    public FailureKind kind() {
        return kind;
    }

    /** The provider's own error code, such as Netgsm {@code 40}, when it sent one. */
    public @Nullable String providerCode() {
        return providerCode;
    }
}
