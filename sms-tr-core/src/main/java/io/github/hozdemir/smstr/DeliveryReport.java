package io.github.hozdemir.smstr;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * Delivery state of one recipient of a message.
 *
 * @param providerStatus the raw status the provider reported, kept for diagnostics
 * @param reportedAt when the provider recorded this status, if it said
 */
public record DeliveryReport(
        MessageId messageId,
        PhoneNumber recipient,
        DeliveryStatus status,
        String providerStatus,
        @Nullable Instant reportedAt) {
}
