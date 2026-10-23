package io.github.hozdemir.smstr;

import java.util.List;

/**
 * Sends SMS through the configured providers. Inject this; it is the application's whole SMS API.
 */
public interface SmsClient {

    /**
     * Send through the default provider, retrying and failing over per the configured policy.
     *
     * @throws SmsProviderException when the message could not be handed to any provider
     */
    SendResult send(SmsMessage message);

    default SendResult send(String to, String text) {
        return send(SmsMessage.builder().to(to).text(text).build());
    }

    /** Pull delivery reports from the provider that accepted the message. */
    List<DeliveryReport> deliveryReports(MessageId messageId);

    /** A client that sends only through {@code provider}, with retries but no failover. */
    SmsClient using(ProviderId provider);

    default SmsClient using(String provider) {
        return using(ProviderId.of(provider));
    }
}
