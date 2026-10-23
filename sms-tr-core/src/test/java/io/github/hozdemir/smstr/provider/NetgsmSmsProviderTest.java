package io.github.hozdemir.smstr.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import io.github.hozdemir.smstr.provider.netgsm.NetgsmSmsProvider;

class NetgsmSmsProviderTest {

    private static final String BASE = NetgsmSmsProvider.DEFAULT_BASE_URL;
    private static final String JOB_ID = "17377215342605050417149344";

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private NetgsmSmsProvider provider(boolean otpEndpoint) {
        return new NetgsmSmsProvider(new NetgsmSmsProvider.Settings("8501234567", "apipass", "BASLIK", null,
                otpEndpoint), builder.build());
    }

    @Test
    void sendsTransactionalTurkishTextWithBasicAuth() {
        server.expect(requestTo(BASE + "/sms/rest/v2/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic ODUwMTIzNDU2NzphcGlwYXNz"))
                .andExpect(content().json("""
                        {"msgheader":"BASLIK","messages":[{"msg":"Siparişiniz yolda","no":"5321234567"}],
                         "encoding":"TR","iysfilter":"0"}""", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"code":"00","jobid":"%s","description":"queued"}""".formatted(JOB_ID),
                        MediaType.APPLICATION_JSON));

        var result = provider(false).send(SmsMessage.builder().to("0532 123 45 67").text("Siparişiniz yolda").build());

        assertThat(result.messageId()).isEqualTo(new MessageId(ProviderId.NETGSM, JOB_ID));
        server.verify();
    }

    @Test
    void mapsCommercialTypeAndScheduleToIstanbulTime() {
        server.expect(requestTo(BASE + "/sms/rest/v2/send"))
                .andExpect(content().json("""
                        {"iysfilter":"12","startdate":"251020261330","encoding":"UTF-8",
                         "messages":[{"msg":"Kampanya","no":"5321234567"},{"msg":"Kampanya","no":"5551112233"}]}"""))
                .andRespond(withSuccess("""
                        {"code":"00","jobid":"1"}""", MediaType.APPLICATION_JSON));

        provider(false).send(SmsMessage.builder().to("5321234567", "5551112233").text("Kampanya")
                .type(MessageType.COMMERCIAL_MERCHANT).scheduledAt(Instant.parse("2026-10-25T10:30:00Z")).build());

        server.verify();
    }

    @Test
    void classifiesErrorCodeFromRejectedRequestBody() {
        server.expect(requestTo(BASE + "/sms/rest/v2/send"))
                .andRespond(withStatus(HttpStatus.NOT_ACCEPTABLE).contentType(MediaType.APPLICATION_JSON).body("""
                        {"code":"40","jobid":null,"description":"Mesaj başlığı tanımlı değil"}"""));

        assertThatThrownBy(() -> provider(false).send(message("x")))
                .isInstanceOfSatisfying(SmsProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(FailureKind.SENDER_NOT_ALLOWED);
                    assertThat(e.providerCode()).isEqualTo("40");
                });
    }

    @Test
    void sendLimitIsRateLimited() {
        server.expect(requestTo(BASE + "/sms/rest/v2/send"))
                .andRespond(withSuccess("""
                        {"code":"80","description":"limit"}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider(false).send(message("x")))
                .isInstanceOfSatisfying(SmsProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.RATE_LIMITED));
    }

    @Test
    void successStatusWithUnexpectedBodyIsAmbiguous() {
        server.expect(requestTo(BASE + "/sms/rest/v2/send"))
                .andRespond(withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> provider(false).send(message("x")))
                .isInstanceOfSatisfying(SmsProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.AMBIGUOUS));
    }

    @Test
    void otpGoesToOtpEndpointTransliteratedWhenEnabled() {
        server.expect(requestTo(BASE + "/sms/rest/v2/otp"))
                .andExpect(content().json("""
                        {"msgheader":"BASLIK","msg":"Dogrulama kodunuz: 123456","no":"5321234567"}""",
                        org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"jobid":"%s","code":"00","description":"success"}""".formatted(JOB_ID),
                        MediaType.APPLICATION_JSON));

        provider(true).send(SmsMessage.builder().to("5321234567").text("Doğrulama kodunuz: 123456")
                .type(MessageType.OTP).build());

        server.verify();
    }

    @Test
    void missingOtpPackageAllowsFailover() {
        server.expect(requestTo(BASE + "/sms/rest/v2/otp"))
                .andRespond(withSuccess("""
                        {"code":"60","description":"OTP paketi yok"}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider(true).send(SmsMessage.builder().to("5321234567").text("1234")
                .type(MessageType.OTP).build()))
                .isInstanceOfSatisfying(SmsProviderException.class,
                        e -> assertThat(e.kind().allowsFailover()).isTrue());
    }

    @Test
    void otpUsesRegularEndpointWhenOtpEndpointIsOff() {
        server.expect(requestTo(BASE + "/sms/rest/v2/send"))
                .andRespond(withSuccess("""
                        {"code":"00","jobid":"1"}""", MediaType.APPLICATION_JSON));

        provider(false).send(SmsMessage.builder().to("5321234567").text("1234").type(MessageType.OTP).build());

        server.verify();
    }

    @Test
    void parsesPerRecipientReports() {
        server.expect(requestTo(BASE + "/sms/rest/v2/report"))
                .andExpect(content().json("""
                        {"jobids":["%s"]}""".formatted(JOB_ID), org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"code":"00","jobs":[
                          {"jobid":"%1$s","number":"5321234567","status":1,"deliveredDate":"2026-04-07 08:55:47"},
                          {"jobid":"%1$s","number":"5551112233","status":14,"deliveredDate":""}],
                         "description":"success"}""".formatted(JOB_ID), MediaType.APPLICATION_JSON));

        MessageId id = new MessageId(ProviderId.NETGSM, JOB_ID);
        assertThat(provider(false).fetchReports(id)).containsExactly(
                new DeliveryReport(id, PhoneNumber.of("5321234567"), DeliveryStatus.DELIVERED, "1",
                        Instant.parse("2026-04-07T05:55:47Z")),
                new DeliveryReport(id, PhoneNumber.of("5551112233"), DeliveryStatus.REJECTED, "14", null));
    }

    @Test
    void noRecordsYetIsAnEmptyReport() {
        server.expect(requestTo(BASE + "/sms/rest/v2/report"))
                .andRespond(withSuccess("""
                        {"code":"60","description":"no records"}""", MediaType.APPLICATION_JSON));

        assertThat(provider(false).fetchReports(new MessageId(ProviderId.NETGSM, JOB_ID))).isEmpty();
    }

    private static SmsMessage message(String text) {
        return SmsMessage.builder().to("5321234567").text(text).build();
    }
}
