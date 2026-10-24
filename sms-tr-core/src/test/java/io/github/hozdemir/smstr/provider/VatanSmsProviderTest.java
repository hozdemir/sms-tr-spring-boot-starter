package io.github.hozdemir.smstr.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.github.hozdemir.smstr.DefaultSmsClient;
import io.github.hozdemir.smstr.FailureKind;
import io.github.hozdemir.smstr.MessageId;
import io.github.hozdemir.smstr.MessageType;
import io.github.hozdemir.smstr.ProviderId;
import io.github.hozdemir.smstr.ResiliencePolicy;
import io.github.hozdemir.smstr.SmsException;
import io.github.hozdemir.smstr.SmsMessage;
import io.github.hozdemir.smstr.SmsProviderException;
import io.github.hozdemir.smstr.SmsProviders;
import io.github.hozdemir.smstr.event.SmsEventPublisher;
import io.github.hozdemir.smstr.provider.vatansms.VatanSmsProvider;

class VatanSmsProviderTest {

    private static final String BASE = VatanSmsProvider.DEFAULT_BASE_URL;

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final VatanSmsProvider provider = new VatanSmsProvider(
            new VatanSmsProvider.Settings("API_ID", "API_KEY", "SMSBASLIGINIZ"), builder.build());

    @Test
    void sendsDocumentedOneToManyRequestAndReadsId() {
        server.expect(requestTo(BASE + "/api/v1/1toN"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"api_id":"API_ID","api_key":"API_KEY","sender":"SMSBASLIGINIZ","message_type":"normal",
                         "message":"Bu bir test mesaji.","message_content_type":"bilgi",
                         "phones":["5321234567","5551112233"]}""", JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"status":"success","id":926410}""", MediaType.APPLICATION_JSON));

        var result = provider.send(SmsMessage.builder().to("05321234567", "+905551112233")
                .text("Bu bir test mesaji.").build());

        assertThat(result.messageId()).isEqualTo(new MessageId(ProviderId.VATANSMS, "926410"));
    }

    @Test
    void turkishCommercialScheduledMessage() {
        server.expect(requestTo(BASE + "/api/v1/1toN"))
                .andExpect(content().json("""
                        {"message_type":"turkce","message_content_type":"ticari","send_time":"2026-10-25 13:30:00"}"""))
                .andRespond(withSuccess("""
                        {"status":"success","id":1}""", MediaType.APPLICATION_JSON));

        provider.send(SmsMessage.builder().to("5321234567").text("İndirim başladı")
                .type(MessageType.COMMERCIAL_INDIVIDUAL).scheduledAt(Instant.parse("2026-10-25T10:30:00Z")).build());

        server.verify();
    }

    @Test
    void unauthorizedIsAuthenticationFailure() {
        server.expect(requestTo(BASE + "/api/v1/1toN"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Unauthorized\"}"));

        assertThatThrownBy(() -> provider.send(message()))
                .isInstanceOfSatisfying(SmsProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(FailureKind.AUTHENTICATION));
    }

    @Test
    void errorStatusIsNotRetriedButAllowsFailover() {
        server.expect(requestTo(BASE + "/api/v1/1toN"))
                .andRespond(withSuccess("""
                        {"status":"error","message":"Gönderici adı bulunamadı"}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.send(message()))
                .isInstanceOfSatisfying(SmsProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(FailureKind.UNKNOWN);
                    assertThat(e.getMessage()).contains("Gönderici adı bulunamadı");
                });
    }

    @Test
    void deliveryReportsAreNotOffered() {
        var client = new DefaultSmsClient(SmsProviders.of(List.of(provider)), List.of(ProviderId.VATANSMS),
                ResiliencePolicy.NO_RETRY, SmsEventPublisher.NONE);

        assertThatThrownBy(() -> client.deliveryReports(new MessageId(ProviderId.VATANSMS, "1")))
                .isInstanceOf(SmsException.class)
                .hasMessageContaining("does not support delivery report queries");
    }

    private static SmsMessage message() {
        return SmsMessage.builder().to("5321234567").text("x").build();
    }
}
