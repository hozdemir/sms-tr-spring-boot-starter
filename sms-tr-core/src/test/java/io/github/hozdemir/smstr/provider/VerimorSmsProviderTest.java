package io.github.hozdemir.smstr.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
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
import io.github.hozdemir.smstr.provider.verimor.VerimorSmsProvider;
import io.github.hozdemir.smstr.spi.WebhookRequest;

class VerimorSmsProviderTest {

    private static final String BASE = VerimorSmsProvider.DEFAULT_BASE_URL;

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final VerimorSmsProvider provider = new VerimorSmsProvider(
            new VerimorSmsProvider.Settings("908501234567", "p&ss+word", "BASLIGIM"), builder.build());

    @Test
    void sendsCommaSeparatedDestinationsAndReadsPlainTextCampaignId() {
        server.expect(requestTo(BASE + "/v2/send.json"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"username":"908501234567","password":"p&ss+word","source_addr":"BASLIGIM","datacoding":"1",
                         "messages":[{"msg":"Randevunuz yarın","dest":"905321234567,905551112233"}]}""",
                        JsonCompareMode.STRICT))
                .andRespond(withSuccess("20212", MediaType.TEXT_PLAIN));

        var result = provider.send(SmsMessage.builder().to("5321234567", "5551112233").text("Randevunuz yarın").build());

        assertThat(result.messageId()).isEqualTo(new MessageId(ProviderId.VERIMOR, "20212"));
    }

    @Test
    void readsJsonCampaignIdFromNewerApi() {
        server.expect(requestTo(BASE + "/v2/send.json"))
                .andRespond(withSuccess("""
                        {"campaign_id": 98765432, "custom_id": null, "status": "0"}""", MediaType.APPLICATION_JSON));

        assertThat(provider.send(message()).messageId().value()).isEqualTo("98765432");
    }

    @Test
    void commercialOtpAndScheduleFields() {
        server.expect(requestTo(BASE + "/v2/send.json"))
                .andExpect(content().json("""
                        {"is_commercial":true,"iys_recipient_type":"TACIR","send_at":"2026-10-25 13:30:00"}"""))
                .andRespond(withSuccess("1", MediaType.TEXT_PLAIN));
        server.expect(requestTo(BASE + "/v2/send.json"))
                .andExpect(content().json("""
                        {"valid_for":"00:05","datacoding":"0"}"""))
                .andRespond(withSuccess("2", MediaType.TEXT_PLAIN));

        provider.send(SmsMessage.builder().to("5321234567").text("Kampanya").type(MessageType.COMMERCIAL_MERCHANT)
                .scheduledAt(Instant.parse("2026-10-25T10:30:00Z")).build());
        provider.send(SmsMessage.builder().to("5321234567").text("Kod: 1234").type(MessageType.OTP).build());

        server.verify();
    }

    @Test
    void classifiesErrorTokenInBadRequestBody() {
        server.expect(requestTo(BASE + "/v2/send.json"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("INSUFFICIENT_CREDITS"));

        assertThatThrownBy(() -> provider.send(message()))
                .isInstanceOfSatisfying(SmsProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(FailureKind.INSUFFICIENT_CREDIT);
                    assertThat(e.providerCode()).isEqualTo("INSUFFICIENT_CREDITS");
                });
    }

    @Test
    void rateLimitIsRetryable() {
        server.expect(requestTo(BASE + "/v2/send.json")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> provider.send(message()))
                .isInstanceOfSatisfying(SmsProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.RATE_LIMITED));
    }

    @Test
    void statusQueryEncodesCredentialsAndMapsStatuses() {
        server.expect(requestTo(BASE + "/v2/status?username=908501234567&password=p%26ss%2Bword&id=20121"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"campaign_id":20121,"message_id":"13582302","dest":"905319876543","status":"DELIVERED",
                          "gsm_error":"0","sent_at":"2015-02-20 16:06:00","done_at":"2015-02-20 16:06:00"},
                         {"campaign_id":20121,"message_id":"13582303","dest":"905321234567","status":"NOT_ALLOWED_BY_IYS",
                          "gsm_error":"0","sent_at":"2015-02-20 16:06:00","done_at":""}]""", MediaType.APPLICATION_JSON));

        MessageId id = new MessageId(ProviderId.VERIMOR, "20121");
        assertThat(provider.fetchReports(id)).containsExactly(
                new DeliveryReport(id, PhoneNumber.of("5319876543"), DeliveryStatus.DELIVERED, "DELIVERED",
                        Instant.parse("2015-02-20T14:06:00Z")),
                new DeliveryReport(id, PhoneNumber.of("5321234567"), DeliveryStatus.REJECTED, "NOT_ALLOWED_BY_IYS",
                        null));
    }

    @Test
    void pushSkipsInboundMessages() {
        String body = """
                [{"type":"outbound","campaign_id":20121,"message_id":"1","dest":"905321234567","status":"EXPIRED"},
                 {"type":"inbound","source_addr":"905551112233","content":"merhaba"}]""";

        assertThat(provider.parseCallback(new WebhookRequest("POST", Map.of(), body.getBytes(StandardCharsets.UTF_8),
                "application/json")))
                .containsExactly(new DeliveryReport(new MessageId(ProviderId.VERIMOR, "20121"),
                        PhoneNumber.of("5321234567"), DeliveryStatus.EXPIRED, "EXPIRED", null));
    }

    private static SmsMessage message() {
        return SmsMessage.builder().to("5321234567").text("x").build();
    }
}
