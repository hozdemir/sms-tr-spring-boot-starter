package io.github.hozdemir.smstr.autoconfigure;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import io.github.hozdemir.smstr.DefaultSmsClient;
import io.github.hozdemir.smstr.DeliveryReportWebhookHandler;
import io.github.hozdemir.smstr.ProviderId;
import io.github.hozdemir.smstr.ResiliencePolicy;
import io.github.hozdemir.smstr.SmsClient;
import io.github.hozdemir.smstr.SmsProviders;
import io.github.hozdemir.smstr.event.SmsEventPublisher;
import io.github.hozdemir.smstr.otp.InMemoryOtpStore;
import io.github.hozdemir.smstr.otp.OtpPolicy;
import io.github.hozdemir.smstr.otp.OtpService;
import io.github.hozdemir.smstr.otp.OtpStore;
import io.github.hozdemir.smstr.spi.SmsProvider;

/**
 * Every bean here backs off when the application defines its own, so any piece (the client, the OTP store,
 * the retry policy, a provider) can be replaced without disabling the rest.
 */
@AutoConfiguration(afterName = "org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration")
@ConditionalOnBooleanProperty(name = "sms.enabled", matchIfMissing = true)
@EnableConfigurationProperties(SmsProperties.class)
@Import({ BuiltInProvidersConfiguration.class, SmsWebhookConfiguration.class })
public class SmsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SmsProviders smsProviders(ObjectProvider<SmsProvider> providers) {
        return SmsProviders.of(providers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    SmsEventPublisher smsEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return applicationEventPublisher::publishEvent;
    }

    @Bean
    @ConditionalOnMissingBean
    ResiliencePolicy smsResiliencePolicy(SmsProperties properties) {
        SmsProperties.Retry retry = properties.retry();
        return new ResiliencePolicy(retry.maxAttempts(), retry.initialDelay(), retry.multiplier(), retry.maxDelay(),
                retry.retryAmbiguous());
    }

    @Bean
    @ConditionalOnMissingBean
    SmsClient smsClient(SmsProviders providers, SmsProperties properties, ResiliencePolicy policy,
            SmsEventPublisher events) {
        return new DefaultSmsClient(providers, route(providers, properties), policy, events);
    }

    @Bean
    @ConditionalOnMissingBean
    OtpStore otpStore(ObjectProvider<Clock> clock) {
        return new InMemoryOtpStore(clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    OtpService otpService(SmsClient smsClient, OtpStore otpStore, SmsProperties properties,
            ObjectProvider<Clock> clock) {
        SmsProperties.Otp otp = properties.otp();
        OtpPolicy policy = new OtpPolicy(otp.length(), otp.ttl(), otp.maxAttempts(), otp.resendCooldown(),
                otp.template());
        return new OtpService(smsClient, otpStore, policy, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    DeliveryReportWebhookHandler deliveryReportWebhookHandler(SmsProviders providers, SmsEventPublisher events) {
        return new DeliveryReportWebhookHandler(providers, events);
    }

    static List<ProviderId> route(SmsProviders providers, SmsProperties properties) {
        Set<ProviderId> configured = providers.ids();
        if (configured.isEmpty()) {
            throw new IllegalStateException("No SMS provider is configured. Set credentials for one, e.g. "
                    + "sms.netgsm.username and sms.netgsm.password, register an SmsProvider bean, "
                    + "or turn the starter off with sms.enabled=false.");
        }
        ProviderId primary;
        if (properties.defaultProvider() != null) {
            primary = ProviderId.of(properties.defaultProvider());
        }
        else if (configured.size() == 1) {
            primary = configured.iterator().next();
        }
        else {
            throw new IllegalStateException("Several SMS providers are configured " + configured
                    + "; choose one with sms.default-provider.");
        }
        Set<ProviderId> route = new LinkedHashSet<>();
        route.add(primary);
        properties.fallback().forEach(id -> route.add(ProviderId.of(id)));
        return new ArrayList<>(route);
    }
}
