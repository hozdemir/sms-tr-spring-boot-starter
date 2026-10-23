package io.github.hozdemir.smstr;

import java.util.List;

import io.github.hozdemir.smstr.event.SmsEvent;
import io.github.hozdemir.smstr.event.SmsEventPublisher;
import io.github.hozdemir.smstr.spi.DeliveryReportWebhook;
import io.github.hozdemir.smstr.spi.SmsProvider;
import io.github.hozdemir.smstr.spi.WebhookRequest;

/** Turns a provider callback into {@link SmsEvent.DeliveryReportReceived} events. Web-stack agnostic. */
public final class DeliveryReportWebhookHandler {

    private final SmsProviders providers;
    private final SmsEventPublisher events;

    public DeliveryReportWebhookHandler(SmsProviders providers, SmsEventPublisher events) {
        this.providers = providers;
        this.events = events;
    }

    /**
     * @throws SmsException if the provider is unknown or has no webhook support
     * @throws IllegalArgumentException if the payload is not a delivery report
     */
    public List<DeliveryReport> handle(ProviderId providerId, WebhookRequest request) {
        SmsProvider provider = providers.get(providerId);
        if (!(provider instanceof DeliveryReportWebhook webhook)) {
            throw new SmsException("Provider '" + providerId + "' does not push delivery reports");
        }
        List<DeliveryReport> reports = webhook.parseCallback(request);
        reports.forEach(report -> events.publish(new SmsEvent.DeliveryReportReceived(report)));
        return reports;
    }
}
