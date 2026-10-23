package io.github.hozdemir.smstr.provider.mutlucell;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
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
import io.github.hozdemir.smstr.http.TextEncoding;
import io.github.hozdemir.smstr.spi.DeliveryReportQuery;
import io.github.hozdemir.smstr.spi.SmsProvider;

/**
 * Mutlucell XML API ({@code https://www.mutlucell.com.tr/toplusmsapi/}). Answers are plain text. There is no
 * delivery report webhook, and Mutlucell blocks accounts that poll a package more often than every
 * {@link #MIN_REPORT_INTERVAL}, so this provider refuses such queries itself.
 */
public final class MutlucellSmsProvider implements SmsProvider, DeliveryReportQuery {

    public static final String DEFAULT_BASE_URL = "https://smsgw.mutlucell.com";
    public static final Duration MIN_REPORT_INTERVAL = Duration.ofMinutes(15);

    /**
     * @param password the account password or the API key Mutlucell issued
     * @param brandCode İYS brand code; required only for commercial messages
     */
    public record Settings(String username, String password, String sender, @Nullable String brandCode) {
    }

    private static final ProviderId ID = ProviderId.MUTLUCELL;
    private static final DateTimeFormatter SCHEDULE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Map<String, FailureKind> ERRORS = Map.of(
            "20", FailureKind.INVALID_REQUEST,
            "21", FailureKind.SENDER_NOT_ALLOWED,
            "22", FailureKind.INSUFFICIENT_CREDIT,
            "23", FailureKind.AUTHENTICATION,
            "24", FailureKind.TRANSIENT,
            "25", FailureKind.TRANSIENT,
            "30", FailureKind.AUTHENTICATION,
            "34", FailureKind.AUTHENTICATION);

    private static final Map<String, DeliveryStatus> STATUSES = Map.ofEntries(
            Map.entry("0", DeliveryStatus.UNDELIVERED),
            Map.entry("1", DeliveryStatus.PENDING),
            Map.entry("2", DeliveryStatus.PENDING),
            Map.entry("3", DeliveryStatus.DELIVERED),
            Map.entry("4", DeliveryStatus.PENDING),
            Map.entry("5", DeliveryStatus.EXPIRED),
            Map.entry("6", DeliveryStatus.UNDELIVERED),
            Map.entry("7", DeliveryStatus.REJECTED),
            Map.entry("12", DeliveryStatus.UNDELIVERED),
            Map.entry("13", DeliveryStatus.UNDELIVERED),
            Map.entry("15", DeliveryStatus.UNDELIVERED),
            Map.entry("16", DeliveryStatus.UNDELIVERED),
            Map.entry("17", DeliveryStatus.UNDELIVERED),
            Map.entry("19", DeliveryStatus.UNDELIVERED),
            Map.entry("21", DeliveryStatus.REJECTED),
            Map.entry("22", DeliveryStatus.REJECTED));

    private final Settings settings;
    private final RestClient restClient;
    private final Clock clock;
    private final Map<String, Instant> lastReportQuery = new ConcurrentHashMap<>();

    public MutlucellSmsProvider(Settings settings, RestClient restClient) {
        this(settings, restClient, Clock.systemUTC());
    }

    public MutlucellSmsProvider(Settings settings, RestClient restClient, Clock clock) {
        this.settings = settings;
        this.restClient = restClient;
        this.clock = clock;
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public SendResult send(SmsMessage message) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><smspack");
        attribute(xml, "ka", settings.username());
        attribute(xml, "pwd", settings.password());
        attribute(xml, "org", message.sender() != null ? message.sender() : settings.sender());
        attribute(xml, "charset", switch (TextEncoding.detect(message.text())) {
            case GSM -> "default";
            case TURKISH -> "turkish";
            case UNICODE -> "unicode";
        });
        if (message.scheduledAt() != null) {
            attribute(xml, "tarih", SCHEDULE.format(message.scheduledAt().atZone(ProviderHttp.ISTANBUL)));
        }
        if (message.type().isCommercial()) {
            if (settings.brandCode() == null) {
                throw new SmsProviderException(ID, FailureKind.SENDER_NOT_ALLOWED, null,
                        "Commercial messages need sms.mutlucell.brand-code (İYS brand code)");
            }
            attribute(xml, "brandCode", switch (message.type()) {
                case COMMERCIAL_MERCHANT -> settings.brandCode() + "_TACIR";
                default -> settings.brandCode();
            });
        }
        xml.append("><mesaj><metin>").append(escape(message.text())).append("</metin><nums>")
                .append(message.recipients().stream().map(PhoneNumber::withCountryCode)
                        .collect(Collectors.joining(",")))
                .append("</nums></mesaj></smspack>");

        String body = post("/smsgw-ws/sndblkex", xml.toString());
        // "$<package id>#<credits spent>"
        if (body.startsWith("$") && body.indexOf('#') > 1) {
            String packageId = body.substring(1, body.indexOf('#'));
            String credits = body.substring(body.indexOf('#') + 1);
            if (credits.matches("0+(\\.0+)?")) {
                throw new SmsProviderException(ID, FailureKind.INVALID_REQUEST, null,
                        "Mutlucell charged nothing for package " + packageId + "; it drops numbers it cannot deliver to");
            }
            return new SendResult(new MessageId(ID, packageId), message.recipients());
        }
        throw error(body);
    }

    @Override
    public List<DeliveryReport> fetchReports(MessageId messageId) {
        Instant now = clock.instant();
        lastReportQuery.values().removeIf(at -> at.plus(MIN_REPORT_INTERVAL).isBefore(now));
        Instant previous = lastReportQuery.putIfAbsent(messageId.value(), now);
        if (previous != null) {
            throw new SmsProviderException(ID, FailureKind.RATE_LIMITED, null, "Package " + messageId.value()
                    + " was queried at " + previous + "; Mutlucell blocks accounts that poll more often than every "
                    + MIN_REPORT_INTERVAL.toMinutes() + " minutes");
        }

        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><smsrapor");
        attribute(xml, "ka", settings.username());
        attribute(xml, "pwd", settings.password());
        attribute(xml, "id", messageId.value());
        xml.append("/>");
        String body = post("/smsgw-ws/gtblkrprtex", xml.toString());
        if (body.matches("\\d+")) {
            throw error(body);
        }
        // One "<number> <status>" line per recipient.
        List<DeliveryReport> reports = new ArrayList<>();
        for (String line : body.split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length == 2) {
                reports.add(new DeliveryReport(messageId, PhoneNumber.of(parts[0]),
                        STATUSES.getOrDefault(parts[1], DeliveryStatus.UNKNOWN), parts[1], null));
            }
        }
        return reports;
    }

    private String post(String path, String xml) {
        ProviderHttp.Response response = ProviderHttp.exchange(ID, restClient.post()
                .uri(path)
                .contentType(new MediaType(MediaType.TEXT_XML, java.nio.charset.StandardCharsets.UTF_8))
                .body(xml));
        if (!response.isSuccess()) {
            throw ProviderHttp.statusFailure(ID, response);
        }
        return response.body().strip();
    }

    private static SmsProviderException error(String body) {
        FailureKind kind = ERRORS.get(body);
        return kind != null ? new SmsProviderException(ID, kind, body, "Mutlucell error " + body)
                : new SmsProviderException(ID, FailureKind.AMBIGUOUS, null, "Unreadable response: " + body);
    }

    private static void attribute(StringBuilder xml, String name, String value) {
        xml.append(' ').append(name).append("=\"").append(escape(value)).append('"');
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
