package io.github.hozdemir.smstr;

import java.util.List;

/** A send request the provider accepted. Acceptance is not delivery; see {@link DeliveryReport}. */
public record SendResult(MessageId messageId, List<PhoneNumber> recipients) {

    public SendResult {
        recipients = List.copyOf(recipients);
    }

    public ProviderId provider() {
        return messageId.provider();
    }
}
