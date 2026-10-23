package io.github.hozdemir.smstr.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.github.hozdemir.smstr.DeliveryReport;
import io.github.hozdemir.smstr.DeliveryStatus;
import io.github.hozdemir.smstr.FailureKind;
import io.github.hozdemir.smstr.MessageId;
import io.github.hozdemir.smstr.MessageType;
import io.github.hozdemir.smstr.PhoneNumber;
import io.github.hozdemir.smstr.ProviderId;
import io.github.hozdemir.smstr.SmsMessage;
import io.github.hozdemir.smstr.SmsProviderException;
import io.github.hozdemir.smstr.provider.mutlucell.MutlucellSmsProvider;

class MutlucellSmsProviderTest {

    private static final String BASE = MutlucellSmsProvider.DEFAULT_BASE_URL;
    private static final Instant T0 = Instant.parse("2026-10-23T10:00:00Z");

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final MovableClock clock = new MovableClock();

    private MutlucellSmsProvider provider(String brandCode) {
        return new MutlucellSmsProvider(new MutlucellSmsProvider.Settings("user", "pa\"ss", "MUTLUCELL", brandCode),
                builder.build(), clock);
    }

    @Test
    void postsEscapedXmlAndReadsPackageId() {
        server.expect(requestTo(BASE + "/smsgw-ws/sndblkex"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<smspack ka=\"user\" pwd=\"pa&quot;ss\" org=\"MUTLUCELL\" charset=\"turkish\">"
                        + "<mesaj><metin>Ş &amp; &lt;b&gt;</metin><nums>905321234567,905551112233</nums></mesaj>"
                        + "</smspack>"))
                .andRespond(withSuccess("$34672#13.0", MediaType.TEXT_PLAIN));

        var result = provider(null).send(SmsMessage.builder().to("5321234567", "5551112233").text("Ş & <b>").build());

        assertThat(result.messageId()).isEqualTo(new MessageId(ProviderId.MUTLUCELL, "34672"));
    }

    @Test
    void commercialMerchantUsesTacirBrandCode() {
        server.expect(requestTo(BASE + "/smsgw-ws/sndblkex"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("brandCode=\"123456_TACIR\"")))
                .andRespond(withSuccess("$1#1.0", MediaType.TEXT_PLAIN));

        provider("123456").send(SmsMessage.builder().to("5321234567").text("x")
                .type(MessageType.COMMERCIAL_MERCHANT).build());

        server.verify();
    }

    @Test
    void commercialWithoutBrandCodeFailsBeforeCallingMutlucell() {
        assertThatThrownBy(() -> provider(null).send(SmsMessage.builder().to("5321234567").text("x")
                .type(MessageType.COMMERCIAL_INDIVIDUAL).build()))
                .isInstanceOfSatisfying(SmsProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.SENDER_NOT_ALLOWED));
        server.verify();
    }

    @Test
    void numericBodyIsAnErrorCode() {
        server.expect(requestTo(BASE + "/smsgw-ws/sndblkex")).andRespond(withSuccess("23", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> provider(null).send(message()))
                .isInstanceOfSatisfying(SmsProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.AUTHENTICATION));
    }

    @Test
    void nothingChargedMeansNoValidRecipient() {
        server.expect(requestTo(BASE + "/smsgw-ws/sndblkex")).andRespond(withSuccess("$88#0.0", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> provider(null).send(message()))
                .isInstanceOfSatisfying(SmsProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.INVALID_REQUEST));
    }

    @Test
    void parsesReportLinesAndRefusesPollingFasterThanFifteenMinutes() {
        server.expect(requestTo(BASE + "/smsgw-ws/gtblkrprtex"))
                .andExpect(content().string("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<smsrapor ka=\"user\" pwd=\"pa&quot;ss\" id=\"88512\"/>"))
                .andRespond(withSuccess("905334445566 3\n905334445567 2\n905334445568 22\n", MediaType.TEXT_PLAIN));
        server.expect(requestTo(BASE + "/smsgw-ws/gtblkrprtex"))
                .andRespond(withSuccess("905334445567 3", MediaType.TEXT_PLAIN));
        MutlucellSmsProvider provider = provider(null);
        MessageId id = new MessageId(ProviderId.MUTLUCELL, "88512");

        assertThat(provider.fetchReports(id)).containsExactly(
                new DeliveryReport(id, PhoneNumber.of("5334445566"), DeliveryStatus.DELIVERED, "3", null),
                new DeliveryReport(id, PhoneNumber.of("5334445567"), DeliveryStatus.PENDING, "2", null),
                new DeliveryReport(id, PhoneNumber.of("5334445568"), DeliveryStatus.REJECTED, "22", null));

        clock.now = T0.plus(Duration.ofMinutes(14));
        assertThatThrownBy(() -> provider.fetchReports(id))
                .isInstanceOfSatisfying(SmsProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.RATE_LIMITED));

        clock.now = T0.plus(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(provider.fetchReports(id)).singleElement()
                .extracting(DeliveryReport::status).isEqualTo(DeliveryStatus.DELIVERED);
        server.verify();
    }

    private static SmsMessage message() {
        return SmsMessage.builder().to("5321234567").text("x").build();
    }

    private static final class MovableClock extends Clock {

        Instant now = T0;

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
