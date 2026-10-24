package io.github.hozdemir.smstr.provider.iletimerkezi;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import io.github.hozdemir.smstr.DeliveryReport;
import io.github.hozdemir.smstr.DeliveryStatus;
import io.github.hozdemir.smstr.FailureKind;
import io.github.hozdemir.smstr.MessageId;
import io.github.hozdemir.smstr.PhoneNumber;
import io.github.hozdemir.smstr.ProviderId;
import io.github.hozdemir.smstr.SendResult;
import io.github.hozdemir.smstr.SmsMessage;
import io.github.hozdemir.smstr.SmsProviderException;
import io.github.hozdemir.smstr.http.ProviderHttp;
import io.github.hozdemir.smstr.spi.DeliveryReportQuery;
import io.github.hozdemir.smstr.spi.DeliveryReportWebhook;
import io.github.hozdemir.smstr.spi.SmsProvider;
import io.github.hozdemir.smstr.spi.WebhookRequest;
import tools.jackson.databind.JsonNode;

/**
 * İleti Merkezi JSON API ({@code https://www.iletimerkezi.com/en/docs/api}). Turkish characters are an
 * account-level panel setting, not a request field.
 */
public final class IletiMerkeziSmsProvider implements SmsProvider, DeliveryReportQuery, DeliveryReportWebhook {

    public static final String DEFAULT_BASE_URL = "https://api.iletimerkezi.com";

    /**
     * @param apiHash the hash shown in the panel next to the key; it is not derived from a secret
     */
    public record Settings(String apiKey, String apiHash, String sender) {
    }

    private static final ProviderId ID = ProviderId.ILETIMERKEZI;
    private static final DateTimeFormatter SCHEDULE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final int REPORT_PAGE_SIZE = 1000;

    private static final Map<Integer, FailureKind> ERRORS = Map.ofEntries(
            Map.entry(400, FailureKind.INVALID_REQUEST),
            Map.entry(401, FailureKind.AUTHENTICATION),
            Map.entry(402, FailureKind.INSUFFICIENT_CREDIT),
            Map.entry(422, FailureKind.INVALID_REQUEST),
            Map.entry(450, FailureKind.SENDER_NOT_ALLOWED),
            Map.entry(451, FailureKind.INVALID_REQUEST),
            Map.entry(452, FailureKind.INVALID_REQUEST),
            Map.entry(453, FailureKind.INVALID_REQUEST),
            Map.entry(454, FailureKind.INVALID_REQUEST),
            Map.entry(455, FailureKind.INVALID_REQUEST),
            Map.entry(457, FailureKind.INVALID_REQUEST),
            Map.entry(459, FailureKind.SENDER_NOT_ALLOWED),
            Map.entry(460, FailureKind.SENDER_NOT_ALLOWED),
            Map.entry(466, FailureKind.INVALID_REQUEST),
            Map.entry(467, FailureKind.SENDER_NOT_ALLOWED),
            Map.entry(468, FailureKind.INVALID_REQUEST),
            Map.entry(469, FailureKind.INVALID_REQUEST),
            Map.entry(470, FailureKind.SENDER_NOT_ALLOWED),
            Map.entry(503, FailureKind.TRANSIENT));

    private static final Map<Integer, DeliveryStatus> REPORT_STATUSES = Map.of(
            110, DeliveryStatus.PENDING,
            111, DeliveryStatus.DELIVERED,
            112, DeliveryStatus.UNDELIVERED);

    private static final Map<String, DeliveryStatus> WEBHOOK_STATUSES = Map.of(
            "accepted", DeliveryStatus.PENDING,
            "delivered", DeliveryStatus.DELIVERED,
            "undelivered", DeliveryStatus.UNDELIVERED);

    private final Settings settings;
    private final RestClient restClient;

    public IletiMerkeziSmsProvider(Settings settings, RestClient restClient) {
        this.settings = settings;
        this.restClient = restClient;
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public SendResult send(SmsMessage message) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("sender", message.sender() != null ? message.sender() : settings.sender());
        if (message.scheduledAt() != null) {
            order.put("sendDateTime", SCHEDULE.format(message.scheduledAt().atZone(ProviderHttp.ISTANBUL)));
        }
        // Mandatory with no default; omitting it is error 468.
        order.put("iys", message.type().isCommercial() ? "1" : "0");
        switch (message.type()) {
            case COMMERCIAL_INDIVIDUAL -> order.put("iysList", "BIREYSEL");
            case COMMERCIAL_MERCHANT -> order.put("iysList", "TACIR");
            case TRANSACTIONAL, OTP -> {
            }
        }
        order.put("message", Map.of(
                "text", message.text(),
                "receipents", Map.of("number",
                        message.recipients().stream().map(PhoneNumber::withCountryCode).toList())));

        JsonNode json = call("/v1/send-sms/json", order);
        return new SendResult(new MessageId(ID, json.path("order").path("id").asString()), message.recipients());
    }

    @Override
    public List<DeliveryReport> fetchReports(MessageId messageId) {
        List<DeliveryReport> reports = new ArrayList<>();
        for (int page = 1;; page++) {
            JsonNode json;
            try {
                json = call("/v1/get-report/json", Map.of("id", orderId(messageId), "page", page,
                        "rowCount", REPORT_PAGE_SIZE));
            }
            catch (SmsProviderException e) {
                if ("456".equals(e.providerCode())) {
                    return List.of();
                }
                throw e;
            }
            JsonNode rows = json.path("order").path("message");
            for (JsonNode row : rows) {
                int status = row.path("status").asInt(-1);
                reports.add(new DeliveryReport(messageId, PhoneNumber.of(row.path("number").asString()),
                        REPORT_STATUSES.getOrDefault(status, DeliveryStatus.UNKNOWN), String.valueOf(status), null));
            }
            if (rows.size() < REPORT_PAGE_SIZE) {
                return reports;
            }
        }
    }

    /** One JSON object per request: {@code {"report":{"id","packet_id","status","to"}}}. */
    @Override
    public List<DeliveryReport> parseCallback(WebhookRequest request) {
        JsonNode json = ProviderHttp.parseJson(request.bodyAsString());
        JsonNode report = json == null ? null : json.get("report");
        if (report == null || !report.has("packet_id") || !report.has("to") || !report.has("status")) {
            throw new IllegalArgumentException("Not an İleti Merkezi delivery report");
        }
        String status = report.path("status").asString();
        return List.of(new DeliveryReport(new MessageId(ID, report.path("packet_id").asString()),
                PhoneNumber.of(report.path("to").asString()),
                WEBHOOK_STATUSES.getOrDefault(status, DeliveryStatus.UNKNOWN), status, null));
    }

    /** The documented report request carries the order id as a JSON number. */
    private static Object orderId(MessageId messageId) {
        return messageId.value().matches("\\d{1,18}") ? Long.parseLong(messageId.value()) : messageId.value();
    }

    private JsonNode call(String path, Map<String, Object> order) {
        Map<String, Object> body = Map.of("request", Map.of(
                "authentication", Map.of("key", settings.apiKey(), "hash", settings.apiHash()),
                "order", order));
        ProviderHttp.Response response = ProviderHttp.exchange(ID, restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ProviderHttp.toJson(body)));
        JsonNode json = ProviderHttp.parseJson(response.body());
        JsonNode status = json == null ? null : json.path("response").get("status");
        if (status == null) {
            throw response.isSuccess()
                    ? ProviderHttp.unreadable(ID, response, new IllegalStateException("no response.status"))
                    : ProviderHttp.statusFailure(ID, response);
        }
        int code = status.path("code").asInt(-1);
        if (code != 200) {
            throw new SmsProviderException(ID, ERRORS.getOrDefault(code, FailureKind.UNKNOWN),
                    String.valueOf(code), status.path("message").asString(""));
        }
        return json.path("response");
    }
}
