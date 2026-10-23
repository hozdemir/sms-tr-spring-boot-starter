package io.github.hozdemir.smstr.event;

import io.github.hozdemir.smstr.DeliveryReport;
import io.github.hozdemir.smstr.SendResult;
import io.github.hozdemir.smstr.SmsException;
import io.github.hozdemir.smstr.SmsMessage;

/**
 * Lifecycle events. In a Spring application they are published as application events,
 * so {@code @EventListener void on(SmsEvent.DeliveryReportReceived e)} receives them.
 */
public sealed interface SmsEvent {

    record Sent(SmsMessage message, SendResult result) implements SmsEvent {
    }

    /** Every provider in the route failed, or the failure did not allow failover. */
    record Failed(SmsMessage message, SmsException error) implements SmsEvent {
    }

    /** A provider pushed a delivery report to the webhook endpoint. */
    record DeliveryReportReceived(DeliveryReport report) implements SmsEvent {
    }
}
