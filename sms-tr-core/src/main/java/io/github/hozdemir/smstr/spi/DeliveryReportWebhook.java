package io.github.hozdemir.smstr.spi;

import java.util.List;

import io.github.hozdemir.smstr.DeliveryReport;

/**
 * Capability: the provider pushes delivery reports to a callback URL configured in its panel.
 * The starter exposes that URL and hands each request here.
 */
public interface DeliveryReportWebhook {

    /**
     * Parse a callback request. Throw {@link IllegalArgumentException} for a payload that is not a
     * delivery report; the endpoint answers it with 400.
     */
    List<DeliveryReport> parseCallback(WebhookRequest request);
}
