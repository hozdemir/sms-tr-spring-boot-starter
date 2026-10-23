package io.github.hozdemir.smstr.autoconfigure;

import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param defaultProvider provider that sends first; may be omitted when exactly one provider is configured
 * @param fallback providers tried in order when the default one fails with a failure that allows failover
 */
@ConfigurationProperties("sms")
public record SmsProperties(
        @DefaultValue("true") boolean enabled,
        @Nullable String defaultProvider,
        @DefaultValue List<String> fallback,
        @DefaultValue Retry retry,
        @DefaultValue Timeout timeout,
        @DefaultValue Otp otp,
        @DefaultValue Webhook webhook,
        @DefaultValue Netgsm netgsm,
        @DefaultValue IletiMerkezi iletimerkezi,
        @DefaultValue Verimor verimor,
        @DefaultValue Mutlucell mutlucell) {

    /**
     * @param maxAttempts attempts per provider, including the first; 1 disables retries
     * @param retryAmbiguous retry and fail over even when the provider may already have accepted the message
     */
    public record Retry(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("500ms") Duration initialDelay,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("5s") Duration maxDelay,
            @DefaultValue("false") boolean retryAmbiguous) {
    }

    public record Timeout(
            @DefaultValue("3s") Duration connect,
            @DefaultValue("10s") Duration read) {
    }

    /**
     * @param template message text; must contain {@code {code}}
     */
    public record Otp(
            @DefaultValue("6") int length,
            @DefaultValue("3m") Duration ttl,
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("60s") Duration resendCooldown,
            @DefaultValue("Doğrulama kodunuz: {code}") String template) {
    }

    /**
     * Delivery report callbacks are served at {@code {path}/{provider}/{token}}; enter that URL in the
     * provider's panel.
     *
     * @param token secret path segment that authenticates callbacks; required when enabled
     */
    public record Webhook(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("/sms/webhooks") String path,
            @Nullable String token) {
    }

    public record Netgsm(
            @Nullable String username,
            @Nullable String password,
            @Nullable String sender,
            @Nullable String appName,
            @DefaultValue("https://api.netgsm.com.tr") String baseUrl) {
    }

    public record IletiMerkezi(
            @Nullable String apiKey,
            @Nullable String apiSecret,
            @Nullable String sender,
            @DefaultValue("https://api.iletimerkezi.com") String baseUrl) {
    }

    public record Verimor(
            @Nullable String username,
            @Nullable String password,
            @Nullable String sender,
            @DefaultValue("https://sms.verimor.com.tr") String baseUrl) {
    }

    public record Mutlucell(
            @Nullable String username,
            @Nullable String password,
            @Nullable String sender,
            @DefaultValue("https://smsgw.mutlucell.com") String baseUrl) {
    }
}
