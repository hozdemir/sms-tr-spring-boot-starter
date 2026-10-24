package io.github.hozdemir.smstr.provider.vatansms;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import io.github.hozdemir.smstr.FailureKind;
import io.github.hozdemir.smstr.MessageId;
import io.github.hozdemir.smstr.PhoneNumber;
import io.github.hozdemir.smstr.ProviderId;
import io.github.hozdemir.smstr.SendResult;
import io.github.hozdemir.smstr.SmsMessage;
import io.github.hozdemir.smstr.SmsProviderException;
import io.github.hozdemir.smstr.http.ProviderHttp;
import io.github.hozdemir.smstr.http.TextEncoding;
import io.github.hozdemir.smstr.spi.SmsProvider;
import tools.jackson.databind.JsonNode;

/**
 * VatanSMS JSON API v1 ({@code https://www.vatansms.net/sms-api}). The official documentation shows requests
 * only; it documents neither error codes nor the delivery report response, so this provider sends but does not
 * query reports.
 */
public final class VatanSmsProvider implements SmsProvider {

    public static final String DEFAULT_BASE_URL = "https://api.vatansms.net";

    /**
     * @param apiId shown under Hesabım > API Bilgilerimi Görüntüle (My Account > View API Details), next to the key
     */
    public record Settings(String apiId, String apiKey, String sender) {
    }

    private static final ProviderId ID = ProviderId.VATANSMS;
    private static final DateTimeFormatter SCHEDULE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Settings settings;
    private final RestClient restClient;

    public VatanSmsProvider(Settings settings, RestClient restClient) {
        this.settings = settings;
        this.restClient = restClient;
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public SendResult send(SmsMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_id", settings.apiId());
        body.put("api_key", settings.apiKey());
        body.put("sender", message.sender() != null ? message.sender() : settings.sender());
        // Only "normal" and "turkce" are documented.
        body.put("message_type", TextEncoding.detect(message.text()) == TextEncoding.GSM ? "normal" : "turkce");
        body.put("message", message.text());
        body.put("message_content_type", message.type().isCommercial() ? "ticari" : "bilgi");
        body.put("phones", message.recipients().stream().map(PhoneNumber::national).toList());
        if (message.scheduledAt() != null) {
            body.put("send_time", SCHEDULE.format(message.scheduledAt().atZone(ProviderHttp.ISTANBUL)));
        }

        ProviderHttp.Response response = ProviderHttp.exchange(ID, restClient.post()
                .uri("/api/v1/1toN")
                .contentType(MediaType.APPLICATION_JSON)
                .body(ProviderHttp.toJson(body)));
        if (!response.isSuccess()) {
            throw ProviderHttp.statusFailure(ID, response);
        }
        JsonNode json = ProviderHttp.parseJson(response.body());
        String status = json == null ? "" : json.path("status").asString("");
        if (status.equals("success") && json.hasNonNull("id")) {
            return new SendResult(new MessageId(ID, json.path("id").asString()), message.recipients());
        }
        if (status.equals("error")) {
            throw new SmsProviderException(ID, FailureKind.UNKNOWN, null, json.path("message").asString(""));
        }
        throw ProviderHttp.unreadable(ID, response, new IllegalStateException("no status/id"));
    }
}
