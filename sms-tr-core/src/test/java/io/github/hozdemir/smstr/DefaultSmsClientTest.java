package io.github.hozdemir.smstr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.hozdemir.smstr.event.SmsEvent;
import io.github.hozdemir.smstr.spi.SmsProvider;

class DefaultSmsClientTest {

    private static final ResiliencePolicy FAST = new ResiliencePolicy(3, Duration.ofMillis(1), 1.0,
            Duration.ofMillis(1), false);

    private static final SmsMessage MESSAGE = SmsMessage.builder().to("05321234567").text("Merhaba").build();

    private final List<SmsEvent> events = new ArrayList<>();

    @Test
    void retriesTransientFailureOnSameProvider() {
        ScriptedProvider primary = new ScriptedProvider("primary", FailureKind.TRANSIENT, null);
        SmsClient client = client(FAST, primary);

        SendResult result = client.send(MESSAGE);

        assertThat(result.provider()).isEqualTo(ProviderId.of("primary"));
        assertThat(primary.calls).isEqualTo(2);
        assertThat(events).singleElement().isInstanceOf(SmsEvent.Sent.class);
    }

    @Test
    void failsOverWhenRetriesAreExhausted() {
        ScriptedProvider primary = new ScriptedProvider("primary", FailureKind.TRANSIENT, FailureKind.TRANSIENT,
                FailureKind.TRANSIENT);
        ScriptedProvider backup = new ScriptedProvider("backup", (FailureKind) null);

        SendResult result = client(FAST, primary, backup).send(MESSAGE);

        assertThat(result.provider()).isEqualTo(ProviderId.of("backup"));
        assertThat(primary.calls).isEqualTo(3);
    }

    @Test
    void failsOverImmediatelyOnAuthenticationError() {
        ScriptedProvider primary = new ScriptedProvider("primary", FailureKind.AUTHENTICATION);
        ScriptedProvider backup = new ScriptedProvider("backup", (FailureKind) null);

        SendResult result = client(FAST, primary, backup).send(MESSAGE);

        assertThat(result.provider()).isEqualTo(ProviderId.of("backup"));
        assertThat(primary.calls).isEqualTo(1);
    }

    @Test
    void invalidRequestStopsWithoutRetryOrFailover() {
        ScriptedProvider primary = new ScriptedProvider("primary", FailureKind.INVALID_REQUEST);
        ScriptedProvider backup = new ScriptedProvider("backup", (FailureKind) null);

        assertThatThrownBy(() -> client(FAST, primary, backup).send(MESSAGE))
                .isInstanceOfSatisfying(SmsProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID_REQUEST));
        assertThat(primary.calls).isEqualTo(1);
        assertThat(backup.calls).isZero();
        assertThat(events).singleElement().isInstanceOf(SmsEvent.Failed.class);
    }

    @Test
    void ambiguousFailureIsNotRepeatedByDefault() {
        ScriptedProvider primary = new ScriptedProvider("primary", FailureKind.AMBIGUOUS);
        ScriptedProvider backup = new ScriptedProvider("backup", (FailureKind) null);

        assertThatThrownBy(() -> client(FAST, primary, backup).send(MESSAGE))
                .isInstanceOf(SmsProviderException.class);
        assertThat(primary.calls).isEqualTo(1);
        assertThat(backup.calls).isZero();
    }

    @Test
    void ambiguousFailureIsRetriedWhenOptedIn() {
        ResiliencePolicy duplicatesOk = new ResiliencePolicy(3, Duration.ofMillis(1), 1.0, Duration.ofMillis(1),
                true);
        ScriptedProvider primary = new ScriptedProvider("primary", FailureKind.AMBIGUOUS, null);

        client(duplicatesOk, primary).send(MESSAGE);

        assertThat(primary.calls).isEqualTo(2);
    }

    @Test
    void unexpectedProviderExceptionIsTreatedAsAmbiguous() {
        SmsProvider broken = new SmsProvider() {
            @Override
            public ProviderId id() {
                return ProviderId.of("broken");
            }

            @Override
            public SendResult send(SmsMessage message) {
                throw new IllegalStateException("parse bug");
            }
        };

        assertThatThrownBy(() -> client(FAST, broken).send(MESSAGE))
                .isInstanceOfSatisfying(SmsProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.AMBIGUOUS));
    }

    @Test
    void lastFailureCarriesEarlierOnesAsSuppressed() {
        ScriptedProvider primary = new ScriptedProvider("primary", FailureKind.INSUFFICIENT_CREDIT);
        ScriptedProvider backup = new ScriptedProvider("backup", FailureKind.SENDER_NOT_ALLOWED);

        assertThatThrownBy(() -> client(FAST, primary, backup).send(MESSAGE))
                .isInstanceOfSatisfying(SmsProviderException.class, e -> {
                    assertThat(e.provider()).isEqualTo(ProviderId.of("backup"));
                    assertThat(e.getSuppressed()).singleElement()
                            .satisfies(s -> assertThat(((SmsProviderException) s).kind())
                                    .isEqualTo(FailureKind.INSUFFICIENT_CREDIT));
                });
    }

    @Test
    void usingPinsOneProviderWithoutFailover() {
        ScriptedProvider primary = new ScriptedProvider("primary", (FailureKind) null);
        ScriptedProvider backup = new ScriptedProvider("backup", FailureKind.AUTHENTICATION);

        assertThatThrownBy(() -> client(FAST, primary, backup).using("backup").send(MESSAGE))
                .isInstanceOf(SmsProviderException.class);
        assertThat(primary.calls).isZero();
    }

    @Test
    void noRetryPolicyMakesOneAttempt() {
        ScriptedProvider primary = new ScriptedProvider("primary", FailureKind.TRANSIENT, null);

        assertThatThrownBy(() -> client(ResiliencePolicy.NO_RETRY, primary).send(MESSAGE))
                .isInstanceOf(SmsProviderException.class);
        assertThat(primary.calls).isEqualTo(1);
    }

    @Test
    void rejectsRouteNamingUnknownProvider() {
        ScriptedProvider primary = new ScriptedProvider("primary", (FailureKind) null);

        assertThatThrownBy(() -> new DefaultSmsClient(SmsProviders.of(List.of(primary)),
                List.of(ProviderId.of("missing")), FAST, events::add))
                .isInstanceOf(SmsException.class)
                .hasMessageContaining("missing");
    }

    private SmsClient client(ResiliencePolicy policy, SmsProvider... providers) {
        List<ProviderId> route = new ArrayList<>();
        for (SmsProvider provider : providers) {
            route.add(provider.id());
        }
        return new DefaultSmsClient(SmsProviders.of(List.of(providers)), route, policy, events::add);
    }

    /** Fails with each scripted kind in turn; a {@code null} entry, or running out, succeeds. */
    private static final class ScriptedProvider implements SmsProvider {

        private final ProviderId id;
        private final Deque<FailureKind> script = new ArrayDeque<>();
        private int calls;

        ScriptedProvider(String id, FailureKind... failures) {
            this.id = ProviderId.of(id);
            for (FailureKind failure : failures) {
                if (failure != null) {
                    script.add(failure);
                }
                else {
                    break;
                }
            }
        }

        @Override
        public ProviderId id() {
            return id;
        }

        @Override
        public SendResult send(SmsMessage message) {
            calls++;
            FailureKind failure = script.poll();
            if (failure != null) {
                throw new SmsProviderException(id, failure, null, "scripted");
            }
            return new SendResult(new MessageId(id, "job-" + calls), message.recipients());
        }
    }
}
