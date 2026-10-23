package io.github.hozdemir.smstr.otp;

import java.time.Duration;
import java.time.Instant;

import io.github.hozdemir.smstr.SendResult;

public sealed interface OtpSendResult {

    record Sent(SendResult sms, Instant expiresAt) implements OtpSendResult {
    }

    /** A code was sent too recently; nothing was sent. */
    record Throttled(Duration retryAfter) implements OtpSendResult {
    }
}
