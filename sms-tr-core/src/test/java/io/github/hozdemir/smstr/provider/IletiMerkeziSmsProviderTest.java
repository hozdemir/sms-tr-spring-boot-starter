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
import java.util.List;
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
import io.github.hozdemir.smstr.provider.iletimerkezi.IletiMerkeziSmsProvider;
import io.github.hozdemir.smstr.spi.WebhookRequest;

class IletiMerkeziSmsProviderTest {

    private static final String BASE = IletiMerkeziSmsProvider.DEFAULT_BASE_URL;

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final IletiMerkeziSmsProvider provider = new IletiMerkeziSmsProvider(
            new IletiMerkeziSmsProvider.Settings("API_KEY", "API_HASH", "YOURBRAND"), builder.build());

    @Test
    void sendsTransactionalOrderWithMandatoryIysFlag() {
        server.expect(requestTo(BASE + "/v1/send-sms/json"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"request":{"authentication":{"key":"API_KEY","hash":"API_HASH"},
                          "order":{"sender":"YOURBRAND","iys":"0",
                            "message":{"text":"Order shipped","receipents":{"number":["905321234567"]}}}}}""",
                        JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"response":{"status":{"code":200,"message":"İşlem başarılı"},"order":{"id":"312891245"}}}""",
                        MediaType.APPLICATION_JSON));

        var result = provider.send(SmsMessage.builder().to("5321234567").text("Order shipped").build());

        assertThat(result.messageId()).isEqualTo(new MessageId(ProviderId.ILETIMERKEZI, "312891245"));
    }

    @Test
    void commercialIndividualSetsIysListAndScheduleWithoutSeconds() {
        server.expect(requestTo(BASE + "/v1/send-sms/json"))
                .andExpect(content().json("""
                        {"request":{"order":{"iys":"1","iysList":"BIREYSEL","sendDateTime":"25/10/2026 13:30"}}}"""))
                .andRespond(withSuccess("""
                        {"response":{"status":{"code":200},"order":{"id":"1"}}}""", MediaType.APPLICATION_JSON));

        provider.send(SmsMessage.builder().to("5321234567").text("Kampanya").type(MessageType.COMMERCIAL_INDIVIDUAL)
                .scheduledAt(Instant.parse("2026-10-25T10:30:15Z")).build());

        server.verify();
    }

    @Test
    void insufficientBalanceAllowsFailover() {
        server.expect(requestTo(BASE + "/v1/send-sms/json"))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"response":{"status":{"code":402,"message":"Bakiye yetersiz"}}}"""));

        assertThatThrownBy(() -> provider.send(SmsMessage.builder().to("5321234567").text("x").build()))
                .isInstanceOfSatisfying(SmsProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(FailureKind.INSUFFICIENT_CREDIT);
                    assertThat(e.providerCode()).isEqualTo("402");
                });
    }

    @Test
    void parsesPullReport() {
        server.expect(requestTo(BASE + "/v1/get-report/json"))
                .andExpect(content().json("""
                        {"request":{"order":{"id":312891245,"page":1,"rowCount":1000}}}"""))
                .andRespond(withSuccess("""
                        {"response":{"status":{"code":200},"order":{"id":"312891245","status":114,
                          "message":[{"number":"+905333128320","status":111},{"number":"+905321234567","status":110}]}}}""",
                        MediaType.APPLICATION_JSON));

        MessageId id = new MessageId(ProviderId.ILETIMERKEZI, "312891245");
        assertThat(provider.fetchReports(id)).containsExactly(
                new DeliveryReport(id, PhoneNumber.of("5333128320"), DeliveryStatus.DELIVERED, "111", null),
                new DeliveryReport(id, PhoneNumber.of("5321234567"), DeliveryStatus.PENDING, "110", null));
    }

    @Test
    void scheduledOrderNotSentYetHasNoReports() {
        server.expect(requestTo(BASE + "/v1/get-report/json"))
                .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(456)).contentType(MediaType.APPLICATION_JSON).body("""
                        {"response":{"status":{"code":456,"message":"Gönderim zamanı gelmedi"}}}"""));

        assertThat(provider.fetchReports(new MessageId(ProviderId.ILETIMERKEZI, "1"))).isEmpty();
    }

    @Test
    void parsesWebhookEvent() {
        String body = """
                {"report":{"id":1599558518,"packet_id":104525848,"status":"delivered","to":"+905321234567","body":"x"}}""";

        List<DeliveryReport> reports = provider.parseCallback(new WebhookRequest("POST", Map.of(),
                body.getBytes(StandardCharsets.UTF_8), "application/json"));

        assertThat(reports).containsExactly(new DeliveryReport(new MessageId(ProviderId.ILETIMERKEZI, "104525848"),
                PhoneNumber.of("5321234567"), DeliveryStatus.DELIVERED, "delivered", null));
    }
}
