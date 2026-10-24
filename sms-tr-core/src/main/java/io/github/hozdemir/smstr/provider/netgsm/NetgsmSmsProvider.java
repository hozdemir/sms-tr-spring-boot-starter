package io.github.hozdemir.smstr.provider.netgsm;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import io.github.hozdemir.smstr.spi.SmsProvider;
import tools.jackson.databind.JsonNode;

/**
 * Netgsm REST v2 ({@code https://www.netgsm.com.tr/dokuman/}). No delivery report webhook exists for SMS,
 * so reports are pulled; Netgsm allows one query per job id per minute.
 */
public final class NetgsmSmsProvider implements SmsProvider, DeliveryReportQuery {

    public static final String DEFAULT_BASE_URL = "https://api.netgsm.com.tr";

    /**
     * @param username subscriber number (abone numarası)
     * @param password password of an "API Kullanıcısı" (API user) sub-user; the main account password is refused
     * @param otpEndpoint send {@link MessageType#OTP} messages through {@code /sms/rest/v2/otp}, Netgsm's priority
     *     route. Needs an OTP package on the account; Turkish letters are transliterated because the route
     *     rejects them.
     */
    public record Settings(String username, String password, String sender, @Nullable String appName,
            boolean otpEndpoint) {
    }

    private static final ProviderId ID = ProviderId.NETGSM;
    private static final DateTimeFormatter SCHEDULE = DateTimeFormatter.ofPattern("ddMMyyyyHHmm");
    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Map<String, FailureKind> SEND_ERRORS = Map.of(
            "20", FailureKind.INVALID_REQUEST,
            "30", FailureKind.AUTHENTICATION,
            "40", FailureKind.SENDER_NOT_ALLOWED,
            "50", FailureKind.SENDER_NOT_ALLOWED,
            "51", FailureKind.SENDER_NOT_ALLOWED,
            "70", FailureKind.INVALID_REQUEST,
            "80", FailureKind.RATE_LIMITED,
            "85", FailureKind.RATE_LIMITED,
            "100", FailureKind.TRANSIENT);

    private static final Map<String, FailureKind> OTP_ERRORS = Map.ofEntries(
            Map.entry("20", FailureKind.INVALID_REQUEST),
            Map.entry("30", FailureKind.AUTHENTICATION),
            Map.entry("40", FailureKind.SENDER_NOT_ALLOWED),
            Map.entry("41", FailureKind.SENDER_NOT_ALLOWED),
            Map.entry("50", FailureKind.INVALID_REQUEST),
            Map.entry("51", FailureKind.INVALID_REQUEST),
            Map.entry("52", FailureKind.INVALID_REQUEST),
            Map.entry("60", FailureKind.SENDER_NOT_ALLOWED),
            Map.entry("70", FailureKind.INVALID_REQUEST),
            Map.entry("100", FailureKind.TRANSIENT));

    private static final Map<Integer, DeliveryStatus> REPORT_STATUSES = Map.ofEntries(
            Map.entry(0, DeliveryStatus.PENDING),
            Map.entry(1, DeliveryStatus.DELIVERED),
            Map.entry(2, DeliveryStatus.EXPIRED),
            Map.entry(3, DeliveryStatus.UNDELIVERED),
            Map.entry(4, DeliveryStatus.UNDELIVERED),
            Map.entry(11, DeliveryStatus.UNDELIVERED),
            Map.entry(12, DeliveryStatus.UNDELIVERED),
            Map.entry(13, DeliveryStatus.REJECTED),
            Map.entry(14, DeliveryStatus.REJECTED),
            Map.entry(15, DeliveryStatus.REJECTED),
            Map.entry(16, DeliveryStatus.REJECTED),
            Map.entry(17, DeliveryStatus.REJECTED),
            Map.entry(22, DeliveryStatus.REJECTED));

    private final Settings settings;
    private final RestClient restClient;

    public NetgsmSmsProvider(Settings settings, RestClient restClient) {
        this.settings = settings;
        this.restClient = restClient;
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public SendResult send(SmsMessage message) {
        if (settings.otpEndpoint() && message.type() == MessageType.OTP && message.recipients().size() == 1
                && message.scheduledAt() == null) {
            return sendOtp(message);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgheader", sender(message));
        body.put("messages", message.recipients().stream()
                .map(to -> Map.of("msg", message.text(), "no", to.national()))
                .toList());
        body.put("encoding", switch (TextEncoding.detect(message.text())) {
            case GSM -> "UTF-8";
            case TURKISH -> "TR";
            case UNICODE -> "UNICODE";
        });
        body.put("iysfilter", switch (message.type()) {
            case TRANSACTIONAL, OTP -> "0";
            case COMMERCIAL_INDIVIDUAL -> "11";
            case COMMERCIAL_MERCHANT -> "12";
        });
        if (message.scheduledAt() != null) {
            body.put("startdate", SCHEDULE.format(message.scheduledAt().atZone(ProviderHttp.ISTANBUL)));
        }
        if (settings.appName() != null) {
            body.put("appname", settings.appName());
        }
        return post("/sms/rest/v2/send", body, SEND_ERRORS, message);
    }

    private SendResult sendOtp(SmsMessage message) {
        Map<String, Object> body = Map.of(
                "msgheader", sender(message),
                "msg", TextEncoding.transliterate(message.text()),
                "no", message.recipients().get(0).national());
        return post("/sms/rest/v2/otp", body, OTP_ERRORS, message);
    }

    private SendResult post(String path, Map<String, Object> body, Map<String, FailureKind> errors,
            SmsMessage message) {
        ProviderHttp.Response response = ProviderHttp.exchange(ID, request(path, body));
        JsonNode json = ProviderHttp.parseJson(response.body());
        if (json == null || !json.has("code")) {
            throw response.isSuccess()
                    ? ProviderHttp.unreadable(ID, response, new IllegalStateException("no code field"))
                    : ProviderHttp.statusFailure(ID, response);
        }
        String code = json.path("code").asString();
        if (code.equals("00") || code.equals("01") || code.equals("02")) {
            return new SendResult(new MessageId(ID, json.path("jobid").asString()), message.recipients());
        }
        throw new SmsProviderException(ID, errors.getOrDefault(code, FailureKind.UNKNOWN), code,
                json.path("description").asString(""));
    }

    @Override
    public List<DeliveryReport> fetchReports(MessageId messageId) {
        ProviderHttp.Response response = ProviderHttp.exchange(ID,
                request("/sms/rest/v2/report", Map.of("jobids", List.of(messageId.value()))));
        JsonNode json = ProviderHttp.parseJson(response.body());
        if (json == null) {
            throw ProviderHttp.statusFailure(ID, response);
        }
        String code = json.path("code").asString();
        if (code.equals("60")) {
            return List.of();
        }
        if (!code.equals("00")) {
            FailureKind kind = switch (code) {
                case "30" -> FailureKind.AUTHENTICATION;
                case "70" -> FailureKind.INVALID_REQUEST;
                case "80" -> FailureKind.RATE_LIMITED;
                default -> code.startsWith("1") ? FailureKind.TRANSIENT : FailureKind.UNKNOWN;
            };
            throw new SmsProviderException(ID, kind, code, json.path("description").asString(""));
        }
        List<DeliveryReport> reports = new ArrayList<>();
        for (JsonNode job : json.path("jobs")) {
            int status = job.path("status").asInt(-1);
            reports.add(new DeliveryReport(messageId, PhoneNumber.of(job.path("number").asString()),
                    REPORT_STATUSES.getOrDefault(status, DeliveryStatus.UNKNOWN), String.valueOf(status),
                    reportTime(job.path("deliveredDate").asString(""))));
        }
        return reports;
    }

    private RestClient.RequestHeadersSpec<?> request(String path, Object body) {
        return restClient.post()
                .uri(path)
                .headers(headers -> headers.setBasicAuth(settings.username(), settings.password()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(ProviderHttp.toJson(body));
    }

    private String sender(SmsMessage message) {
        return message.sender() != null ? message.sender() : settings.sender();
    }

    private static @Nullable Instant reportTime(String value) {
        try {
            return value.isBlank() ? null
                    : LocalDateTime.parse(value, REPORT_TIME).atZone(ProviderHttp.ISTANBUL).toInstant();
        }
        catch (DateTimeParseException e) {
            return null;
        }
    }
}
