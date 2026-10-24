package io.github.hozdemir.smstr.samples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.github.hozdemir.smstr.SmsProviderException;
import io.github.hozdemir.smstr.event.SmsEvent;

/**
 * Reacting to SMS events. Delivery reports pushed to the webhook endpoint arrive as
 * {@link SmsEvent.DeliveryReportReceived}; store them, update an order, alert on failures.
 */
@Component
public class SmsEventLogger {

    private static final Logger log = LoggerFactory.getLogger(SmsEventLogger.class);

    @EventListener
    void onSent(SmsEvent.Sent event) {
        log.info("Accepted by {} as {}", event.result().provider(), event.result().messageId());
    }

    @EventListener
    void onFailed(SmsEvent.Failed event) {
        if (event.error() instanceof SmsProviderException failure) {
            log.warn("Not sent: {} from {} (provider code {})", failure.kind(), failure.provider(),
                    failure.providerCode());
        }
        else {
            log.warn("Not sent: {}", event.error().getMessage());
        }
    }

    @EventListener
    void onDeliveryReport(SmsEvent.DeliveryReportReceived event) {
        log.info("{} to {}: {}", event.report().messageId(), event.report().recipient(), event.report().status());
    }
}
