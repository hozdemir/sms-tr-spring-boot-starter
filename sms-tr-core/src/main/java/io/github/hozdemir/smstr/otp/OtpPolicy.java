package io.github.hozdemir.smstr.otp;

import java.time.Duration;

/**
 * @param template message text; {@value #CODE_PLACEHOLDER} is replaced with the code
 * @param maxAttempts wrong guesses allowed before the code is burned
 * @param resendCooldown minimum gap between two codes to the same number and purpose
 */
public record OtpPolicy(int length, Duration ttl, int maxAttempts, Duration resendCooldown, String template) {

    public static final String CODE_PLACEHOLDER = "{code}";

    public static final OtpPolicy DEFAULT = new OtpPolicy(6, Duration.ofMinutes(3), 5, Duration.ofSeconds(60),
            "Doğrulama kodunuz: {code}");

    public OtpPolicy {
        if (length < 4 || length > 10) {
            throw new IllegalArgumentException("OTP length must be between 4 and 10");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (!template.contains(CODE_PLACEHOLDER)) {
            throw new IllegalArgumentException("OTP template must contain " + CODE_PLACEHOLDER);
        }
    }
}
