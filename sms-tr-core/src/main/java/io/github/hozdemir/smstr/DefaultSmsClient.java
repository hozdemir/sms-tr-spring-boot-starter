package io.github.hozdemir.smstr;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

import io.github.hozdemir.smstr.event.SmsEvent;
import io.github.hozdemir.smstr.event.SmsEventPublisher;
import io.github.hozdemir.smstr.spi.DeliveryReportQuery;
import io.github.hozdemir.smstr.spi.SmsProvider;

/**
 * Tries each provider in {@code route} order. Within one provider it retries per {@link ResiliencePolicy};
 * it moves to the next provider only when the failure allows failover.
 */
public final class DefaultSmsClient implements SmsClient {

    private static final Log log = LogFactory.getLog(DefaultSmsClient.class);

    private final SmsProviders providers;
    private final List<ProviderId> route;
    private final ResiliencePolicy policy;
    private final SmsEventPublisher events;
    private final RetryTemplate retryTemplate;

    public DefaultSmsClient(SmsProviders providers, List<ProviderId> route, ResiliencePolicy policy,
            SmsEventPublisher events) {
        if (route.isEmpty()) {
            throw new IllegalArgumentException("Route must name at least one provider");
        }
        route.forEach(providers::get);
        this.providers = providers;
        this.route = List.copyOf(route);
        this.policy = policy;
        this.events = events;
        this.retryTemplate = new RetryTemplate(policy.maxAttempts() == 1 ? RetryPolicy.withMaxRetries(0)
                : RetryPolicy.builder()
                        .maxRetries(policy.maxAttempts() - 1)
                        .delay(policy.initialDelay())
                        .multiplier(policy.multiplier())
                        .maxDelay(policy.maxDelay())
                        .predicate(e -> e instanceof SmsProviderException failure
                                && policy.shouldRetry(failure.kind()))
                        .build());
    }

    @Override
    public SendResult send(SmsMessage message) {
        SmsProviderException failure = null;
        for (ProviderId id : route) {
            SendResult result;
            try {
                result = sendWithRetry(providers.get(id), message);
            }
            catch (SmsProviderException e) {
                if (failure != null) {
                    e.addSuppressed(failure);
                }
                failure = e;
                if (!policy.shouldFailover(e.kind())) {
                    break;
                }
                log.warn("SMS provider '" + id + "' failed, trying the next one: " + e.getMessage());
                continue;
            }
            publish(new SmsEvent.Sent(message, result));
            return result;
        }
        publish(new SmsEvent.Failed(message, failure));
        throw failure;
    }

    /** A listener bug must not make a sent message look failed; the caller could send it again. */
    private void publish(SmsEvent event) {
        try {
            events.publish(event);
        }
        catch (RuntimeException e) {
            log.error("SMS event listener failed for " + event.getClass().getSimpleName(), e);
        }
    }

    private SendResult sendWithRetry(SmsProvider provider, SmsMessage message) {
        try {
            return retryTemplate.execute(() -> attempt(provider, message));
        }
        catch (RetryException e) {
            throw (SmsProviderException) e.getLastException();
        }
    }

    private static SendResult attempt(SmsProvider provider, SmsMessage message) {
        try {
            return provider.send(message);
        }
        catch (SmsProviderException e) {
            throw e;
        }
        catch (RuntimeException e) {
            // A provider bug may surface after the request went out, so a retry could send twice.
            throw new SmsProviderException(provider.id(), FailureKind.AMBIGUOUS, null,
                    "Unexpected error: " + e, e);
        }
    }

    @Override
    public List<DeliveryReport> deliveryReports(MessageId messageId) {
        SmsProvider provider = providers.get(messageId.provider());
        if (!(provider instanceof DeliveryReportQuery query)) {
            throw new SmsException("Provider '" + provider.id() + "' does not support delivery report queries");
        }
        return query.fetchReports(messageId);
    }

    @Override
    public SmsClient using(ProviderId provider) {
        return new DefaultSmsClient(providers, List.of(provider), policy, events);
    }
}
