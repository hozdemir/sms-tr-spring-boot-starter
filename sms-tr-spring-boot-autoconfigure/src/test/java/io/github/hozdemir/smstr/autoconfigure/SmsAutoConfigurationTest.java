package io.github.hozdemir.smstr.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.function.RouterFunction;

import io.github.hozdemir.smstr.DeliveryReport;
import io.github.hozdemir.smstr.DeliveryStatus;
import io.github.hozdemir.smstr.FailureKind;
import io.github.hozdemir.smstr.MessageId;
import io.github.hozdemir.smstr.PhoneNumber;
import io.github.hozdemir.smstr.ProviderId;
import io.github.hozdemir.smstr.SendResult;
import io.github.hozdemir.smstr.SmsClient;
import io.github.hozdemir.smstr.SmsMessage;
import io.github.hozdemir.smstr.SmsProviderException;
import io.github.hozdemir.smstr.event.SmsEvent;
import io.github.hozdemir.smstr.otp.InMemoryOtpStore;
import io.github.hozdemir.smstr.otp.OtpService;
import io.github.hozdemir.smstr.otp.OtpStore;
import io.github.hozdemir.smstr.spi.DeliveryReportWebhook;
import io.github.hozdemir.smstr.spi.SmsProvider;
import io.github.hozdemir.smstr.spi.WebhookRequest;

class SmsAutoConfigurationTest {

    private static final String TOKEN = "s3cr3t-callback-token";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SmsAutoConfiguration.class));

    @Test
    void customProviderBeanBecomesTheDefaultWhenAlone() {
        runner.withBean("acme", SmsProvider.class, () -> new FakeProvider("acme", false)).run(context -> {
            SendResult result = context.getBean(SmsClient.class).send("05321234567", "Merhaba");

            assertThat(result.provider()).isEqualTo(ProviderId.of("acme"));
            assertThat(context).hasSingleBean(OtpService.class);
        });
    }

    @Test
    void failsOverToConfiguredFallback() {
        runner.withBean("down", SmsProvider.class, () -> new FakeProvider("down", true))
                .withBean("up", SmsProvider.class, () -> new FakeProvider("up", false))
                .withPropertyValues("sms.default-provider=down", "sms.fallback=up", "sms.retry.max-attempts=1")
                .run(context -> assertThat(context.getBean(SmsClient.class).send("05321234567", "x").provider())
                        .isEqualTo(ProviderId.of("up")));
    }

    @Test
    void severalProvidersWithoutDefaultFailStartupWithGuidance() {
        runner.withBean("a", SmsProvider.class, () -> new FakeProvider("a", false))
                .withBean("b", SmsProvider.class, () -> new FakeProvider("b", false))
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("sms.default-provider"));
    }

    @Test
    void noProviderFailsStartupWithGuidance() {
        runner.run(context -> assertThat(context).getFailure()
                .rootCause().hasMessageContaining("sms.netgsm.username"));
    }

    @Test
    void unknownDefaultProviderFailsStartup() {
        runner.withBean("a", SmsProvider.class, () -> new FakeProvider("a", false))
                .withPropertyValues("sms.default-provider=netgsm")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("No SMS provider 'netgsm'"));
    }

    @Test
    void canBeSwitchedOff() {
        runner.withPropertyValues("sms.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SmsClient.class));
    }

    @Test
    void applicationBeansReplaceDefaults() {
        OtpStore custom = new InMemoryOtpStore(java.time.Clock.systemUTC());
        runner.withBean("acme", SmsProvider.class, () -> new FakeProvider("acme", false))
                .withBean(OtpStore.class, () -> custom)
                .run(context -> assertThat(context.getBean(OtpStore.class)).isSameAs(custom));
    }

    @Test
    void sendOutcomesArePublishedAsApplicationEvents() {
        runner.withBean("acme", SmsProvider.class, () -> new FakeProvider("acme", false))
                .withUserConfiguration(EventCollector.class)
                .run(context -> {
                    context.getBean(SmsClient.class).send("05321234567", "Merhaba");

                    assertThat(context.getBean(EventCollector.class).events)
                            .singleElement().isInstanceOf(SmsEvent.Sent.class);
                });
    }

    @Test
    void webhookIsOffByDefault() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SmsAutoConfiguration.class))
                .withBean("acme", SmsProvider.class, () -> new FakeProvider("acme", false))
                .run(context -> assertThat(context).doesNotHaveBean(RouterFunction.class));
    }

    @Test
    void webhookRequiresAToken() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SmsAutoConfiguration.class))
                .withBean("acme", SmsProvider.class, () -> new FakeProvider("acme", false))
                .withPropertyValues("sms.webhook.enabled=true")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("sms.webhook.token"));
    }

    @Test
    void webhookPublishesDeliveryReportsAndRejectsWrongToken() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SmsAutoConfiguration.class))
                .withBean("acme", SmsProvider.class, () -> new FakeProvider("acme", false))
                .withUserConfiguration(EventCollector.class)
                .withPropertyValues("sms.webhook.enabled=true", "sms.webhook.token=" + TOKEN)
                .run(context -> {
                    @SuppressWarnings("unchecked")
                    RouterFunction<?> routes = context.getBean(RouterFunction.class);
                    MockMvc mvc = MockMvcBuilders.routerFunctions(routes).build();

                    mvc.perform(post("/sms/webhooks/acme/wrong-token").param("id", "42")
                            .param("to", "905321234567").param("status", "ok"))
                            .andExpect(status().isNotFound());
                    mvc.perform(post("/sms/webhooks/acme/" + TOKEN).param("id", "42")
                            .param("to", "905321234567").param("status", "ok"))
                            .andExpect(status().isOk());
                    mvc.perform(post("/sms/webhooks/acme/" + TOKEN))
                            .andExpect(status().isBadRequest());

                    assertThat(context.getBean(EventCollector.class).events).singleElement()
                            .isEqualTo(new SmsEvent.DeliveryReportReceived(new DeliveryReport(
                                    new MessageId(ProviderId.of("acme"), "42"), PhoneNumber.of("905321234567"),
                                    DeliveryStatus.DELIVERED, "ok", null)));
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class EventCollector {

        final List<SmsEvent> events = new ArrayList<>();

        @EventListener
        void on(SmsEvent event) {
            events.add(event);
        }
    }

    /** What a third-party provider looks like: one class, registered as a bean. */
    static final class FakeProvider implements SmsProvider, DeliveryReportWebhook {

        private final ProviderId id;
        private final boolean failing;

        FakeProvider(String id, boolean failing) {
            this.id = ProviderId.of(id);
            this.failing = failing;
        }

        @Override
        public ProviderId id() {
            return id;
        }

        @Override
        public SendResult send(SmsMessage message) {
            if (failing) {
                throw new SmsProviderException(id, FailureKind.TRANSIENT, null, "down");
            }
            return new SendResult(new MessageId(id, "1"), message.recipients());
        }

        @Override
        public List<DeliveryReport> parseCallback(WebhookRequest request) {
            String messageId = request.parameter("id");
            String to = request.parameter("to");
            String status = request.parameter("status");
            if (messageId == null || to == null || status == null) {
                throw new IllegalArgumentException("id, to and status are required");
            }
            return List.of(new DeliveryReport(new MessageId(id, messageId), PhoneNumber.of(to),
                    "ok".equals(status) ? DeliveryStatus.DELIVERED : DeliveryStatus.UNDELIVERED, status, null));
        }
    }
}
