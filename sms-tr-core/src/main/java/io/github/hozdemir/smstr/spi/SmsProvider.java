package io.github.hozdemir.smstr.spi;

import io.github.hozdemir.smstr.ProviderId;
import io.github.hozdemir.smstr.SendResult;
import io.github.hozdemir.smstr.SmsMessage;
import io.github.hozdemir.smstr.SmsProviderException;

/**
 * The one interface a new provider implements. Register the implementation as a Spring bean and it joins
 * routing, retry, failover and OTP with no further wiring.
 * <p>
 * Optional capabilities are separate interfaces the same class may also implement:
 * {@link DeliveryReportQuery} (pull reports) and {@link DeliveryReportWebhook} (push reports).
 * <p>
 * Implementations do not retry; they make one attempt and report failures as {@link SmsProviderException}
 * with an accurate {@link io.github.hozdemir.smstr.FailureKind}.
 */
public interface SmsProvider {

    ProviderId id();

    SendResult send(SmsMessage message);
}
