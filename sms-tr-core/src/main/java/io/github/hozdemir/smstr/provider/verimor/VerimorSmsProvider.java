package io.github.hozdemir.smstr.provider.verimor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import io.github.hozdemir.smstr.DeliveryReport;
import io.github.hozdemir.smstr.DeliveryStatus;
import io.github.hozdemir.smstr.FailureKind;
import io.github.hozdemir.smstr.MessageId;
import io.github.hozdemir.smstr.MessageType;
import io.github.hozdemir.smstr.PhoneNumber;
import io.github.hozdemir.smstr.ProviderId;
import io.github.hozdemir.smstr.SendResult;
import io.github.hozdemir.smstr.SmsMessage;
import io.github.hozdemir.smstr.SmsProviderException;
import io.github.hozdemir.smstr.http.ProviderHttp;
import io.github.hozdemir.smstr.http.TextEncoding;
import io.github.hozdemir.smstr.spi.DeliveryReportQuery;
import io.github.hozdemir.smstr.spi.DeliveryReportWebhook;
import io.github.hozdemir.smstr.spi.SmsProvider;
import io.github.hozdemir.smstr.spi.WebhookRequest;
import tools.jackson.databind.JsonNode;

/**
 * Verimor SMS API ({@code https://github.com/verimor/SMS-API}). The sending server's IP must be allowed in
 * OİM > SMS Ayarları. Status queries are limited to 20 per minute per account.
 */
public final class VerimorSmsProvider implements SmsProvider, DeliveryReportQuery, DeliveryReportWebhook {

    public static final String DEFAULT_BASE_URL = "https://sms.verimor.com.tr";

    /**
     * @param username the account's phone number, e.g. {@code 908501234567}
     * @param sender {@code null} uses the account's first header
     */
    public record Settings(String username, String password, @Nullable String sender) {
    }

    private static final ProviderId ID = ProviderId.VERIMOR;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int REPORT_PAGE_SIZE = 100;
    private static final Pattern ERROR_TOKEN = Pattern.compile("\\b[A-Z][A-Z0-9_]{3,}\\b");

    private static final Map<String, FailureKind> ERRORS = Map.ofEntries(
            Map.entry("INVALID_SOURCE_ADDRESS", FailureKind.SENDER_NOT_ALLOWED),
            Map.entry("NUMERIC_SOURCE_ADDRESS_NOT_ALLOWED", FailureKind.SENDER_NOT_ALLOWED),
            Map.entry("MISSING_IYS_BRAND_CODE", FailureKind.SENDER_NOT_ALLOWED),
            Map.entry("AHS_AUTHORIZATION_ERROR", FailureKind.SENDER_NOT_ALLOWED),
            Map.entry("NO_AHS_BRAND_ERROR", FailureKind.SENDER_NOT_ALLOWED),
            Map.entry("INSUFFICIENT_CREDITS", FailureKind.INSUFFICIENT_CREDIT),
            Map.entry("MISSING_MESSAGE", FailureKind.INVALID_REQUEST),
            Map.entry("MESSAGE_TOO_LONG", FailureKind.INVALID_REQUEST),
            Map.entry("INVALID_PERIOD", FailureKind.INVALID_REQUEST),
            Map.entry("INVALID_VALID_FOR", FailureKind.INVALID_REQUEST),
            Map.entry("INVALID_DELIVERY_TIME", FailureKind.INVALID_REQUEST),
            Map.entry("INVALID_SEND_AT", FailureKind.INVALID_REQUEST),
            Map.entry("INVALID_DATACODING", FailureKind.INVALID_REQUEST),
            Map.entry("MISSING_DESTINATION_ADDRESS", FailureKind.INVALID_REQUEST),
            Map.entry("INVALID_DESTINATION_ADDRESS", FailureKind.INVALID_REQUEST),
            Map.entry("INVALID_DESTINATION_ADDRESSES", FailureKind.INVALID_REQUEST),
            Map.entry("INVALID_UTF8", FailureKind.INVALID_REQUEST),
            Map.entry("INVALID_JSON", FailureKind.INVALID_REQUEST),
            Map.entry("INVALID_SHORT_MESSAGE", FailureKind.INVALID_REQUEST),
            Map.entry("MUKERRER_RAPORLAMA", FailureKind.INVALID_REQUEST),
            Map.entry("FORBIDDEN_MESSAGE", FailureKind.INVALID_REQUEST),
            Map.entry("MESSAGE_COUNT_LIMIT_EXCEEDED", FailureKind.INVALID_REQUEST),
            Map.entry("INVALID_IYS_RECIPIENT_TYPE", FailureKind.INVALID_REQUEST),
            Map.entry("INVALID_IS_COMMERCIAL", FailureKind.INVALID_REQUEST),
            Map.entry("INVALID_IYS_OPTION", FailureKind.INVALID_REQUEST));

    private static final Map<String, DeliveryStatus> STATUSES = Map.ofEntries(
            Map.entry("SENDING", DeliveryStatus.PENDING),
            Map.entry("WAITING", DeliveryStatus.PENDING),
            Map.entry("DELIVERED", DeliveryStatus.DELIVERED),
            Map.entry("SENT", DeliveryStatus.DELIVERED),
            Map.entry("NOT_DELIVERED", DeliveryStatus.UNDELIVERED),
            Map.entry("SEND_ERROR", DeliveryStatus.UNDELIVERED),
            Map.entry("INVALID_DESTINATION_ADDRESS", DeliveryStatus.UNDELIVERED),
            Map.entry("MISSING_TARIFF", DeliveryStatus.UNDELIVERED),
            Map.entry("ROUTE_NOT_AVAILABLE", DeliveryStatus.UNDELIVERED),
            Map.entry("NETWORK_NOTCOVERED", DeliveryStatus.UNDELIVERED),
            Map.entry("INTERNATIONAL_DENIED", DeliveryStatus.UNDELIVERED),
            Map.entry("EXPIRED", DeliveryStatus.EXPIRED),
            Map.entry("REJECTED", DeliveryStatus.REJECTED),
            Map.entry("DOUBLE_SEND_ERROR", DeliveryStatus.REJECTED),
            Map.entry("BLACKLISTED_DESTINATION_ADDRESS", DeliveryStatus.REJECTED),
            Map.entry("NOT_ALLOWED_BY_IYS", DeliveryStatus.REJECTED));

    private final Settings settings;
    private final RestClient restClient;

    public VerimorSmsProvider(Settings settings, RestClient restClient) {
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
        body.put("username", settings.username());
        body.put("password", settings.password());
        String sender = message.sender() != null ? message.sender() : settings.sender();
        if (sender != null) {
            body.put("source_addr", sender);
        }
        if (message.scheduledAt() != null) {
            body.put("send_at", TIME.format(message.scheduledAt().atZone(ProviderHttp.ISTANBUL)));
        }
        if (message.type() == MessageType.OTP) {
            body.put("valid_for", "00:05");
        }
        body.put("datacoding", switch (TextEncoding.detect(message.text())) {
            case GSM -> "0";
            case TURKISH -> "1";
            case UNICODE -> "2";
        });
        switch (message.type()) {
            case COMMERCIAL_INDIVIDUAL -> {
                body.put("is_commercial", true);
                body.put("iys_recipient_type", "BIREYSEL");
            }
            case COMMERCIAL_MERCHANT -> {
                body.put("is_commercial", true);
                body.put("iys_recipient_type", "TACIR");
            }
            case TRANSACTIONAL, OTP -> {
            }
        }
        body.put("messages", List.of(Map.of(
                "msg", message.text(),
                "dest", message.recipients().stream().map(PhoneNumber::withCountryCode)
                        .collect(Collectors.joining(",")))));

        ProviderHttp.Response response = ProviderHttp.exchange(ID, restClient.post()
                .uri("/v2/send.json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(ProviderHttp.toJson(body)));
        if (response.status() == 400) {
            throw badRequest(response);
        }
        if (!response.isSuccess()) {
            throw ProviderHttp.statusFailure(ID, response);
        }
        return new SendResult(new MessageId(ID, campaignId(response)), message.recipients());
    }

    /** The user guide documents a plain-text campaign id; the newer docs site shows {@code {"campaign_id":..}}. */
    private static String campaignId(ProviderHttp.Response response) {
        String body = response.body().strip();
        JsonNode json = body.startsWith("{") ? ProviderHttp.parseJson(body) : null;
        String id = json != null ? json.path("campaign_id").asString("") : body;
        if (!id.matches("\\d+")) {
            throw ProviderHttp.unreadable(ID, response, new IllegalStateException("no campaign id"));
        }
        return id;
    }

    private static SmsProviderException badRequest(ProviderHttp.Response response) {
        Matcher token = ERROR_TOKEN.matcher(response.body());
        while (token.find()) {
            FailureKind kind = ERRORS.get(token.group());
            if (kind != null) {
                return new SmsProviderException(ID, kind, token.group(), response.body().strip());
            }
        }
        return ProviderHttp.statusFailure(ID, response);
    }

    @Override
    public List<DeliveryReport> fetchReports(MessageId messageId) {
        List<DeliveryReport> reports = new ArrayList<>();
        String after = null;
        while (true) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("username", settings.username());
            query.put("password", settings.password());
            query.put("id", messageId.value());
            if (after != null) {
                query.put("greater_than", after);
            }
            // Values go in as URI variables so they are fully encoded; a password may contain '&' or '+'.
            ProviderHttp.Response response = ProviderHttp.exchange(ID, restClient.get()
                    .uri(uri -> {
                        uri.path("/v2/status");
                        query.keySet().forEach(name -> uri.queryParam(name, "{" + name + "}"));
                        return uri.build(query);
                    }));
            if (response.status() == 404) {
                throw new SmsProviderException(ID, FailureKind.INVALID_REQUEST, "HTTP 404", response.body().strip());
            }
            JsonNode rows = response.isSuccess() ? ProviderHttp.parseJson(response.body()) : null;
            if (rows == null || !rows.isArray()) {
                throw ProviderHttp.statusFailure(ID, response);
            }
            for (JsonNode row : rows) {
                reports.add(report(messageId, row));
                after = row.path("message_id").asString();
            }
            if (rows.size() < REPORT_PAGE_SIZE) {
                return reports;
            }
        }
    }

    /** A JSON array of message states; inbound SMS pushes share the URL and are skipped. */
    @Override
    public List<DeliveryReport> parseCallback(WebhookRequest request) {
        JsonNode rows = ProviderHttp.parseJson(request.bodyAsString());
        if (rows == null || !rows.isArray()) {
            throw new IllegalArgumentException("Not a Verimor delivery report push");
        }
        List<DeliveryReport> reports = new ArrayList<>();
        for (JsonNode row : rows) {
            if (row.path("type").asString("outbound").equals("outbound")) {
                reports.add(report(new MessageId(ID, row.path("campaign_id").asString()), row));
            }
        }
        return reports;
    }

    private static DeliveryReport report(MessageId messageId, JsonNode row) {
        String status = row.path("status").asString("");
        return new DeliveryReport(messageId, PhoneNumber.of(row.path("dest").asString()),
                STATUSES.getOrDefault(status, DeliveryStatus.UNKNOWN), status, time(row.path("done_at").asString("")));
    }

    private static @Nullable Instant time(String value) {
        try {
            return value.isBlank() ? null : LocalDateTime.parse(value, TIME).atZone(ProviderHttp.ISTANBUL).toInstant();
        }
        catch (DateTimeParseException e) {
            return null;
        }
    }
}
