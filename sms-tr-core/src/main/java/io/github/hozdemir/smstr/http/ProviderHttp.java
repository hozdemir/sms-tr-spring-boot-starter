package io.github.hozdemir.smstr.http;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;

import org.jspecify.annotations.Nullable;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import io.github.hozdemir.smstr.FailureKind;
import io.github.hozdemir.smstr.ProviderId;
import io.github.hozdemir.smstr.SmsProviderException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP plumbing shared by the built-in providers. */
public final class ProviderHttp {

    /** Providers read and write timestamps in Turkish local time. */
    public static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

    /** Status and body of a response, whatever the status; providers put error codes in bodies of 4xx responses. */
    public record Response(int status, String body) {

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    private ProviderHttp() {
    }

    public static RestClient restClient(RestClient.Builder builder, String baseUrl, Duration connectTimeout,
            Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    /** Execute the request and return the response regardless of status; transport failures are classified. */
    public static Response exchange(ProviderId provider, RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.exchange((req, res) -> new Response(res.getStatusCode().value(),
                    new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)), true);
        }
        catch (RestClientException e) {
            throw transportFailure(provider, e);
        }
    }

    /**
     * Failures before the request left the machine are {@link FailureKind#TRANSIENT}; failures after it may
     * have been processed are {@link FailureKind#AMBIGUOUS}.
     */
    static SmsProviderException transportFailure(ProviderId provider, RestClientException e) {
        if (e instanceof ResourceAccessException && neverSent(e)) {
            return new SmsProviderException(provider, FailureKind.TRANSIENT, null,
                    "Could not connect: " + e.getMessage(), e);
        }
        return new SmsProviderException(provider, FailureKind.AMBIGUOUS, null, String.valueOf(e.getMessage()), e);
    }

    private static boolean neverSent(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException
                    || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }

    /** Classification of an HTTP status whose body the provider could not interpret. */
    public static SmsProviderException statusFailure(ProviderId provider, Response response) {
        int status = response.status();
        FailureKind kind = switch (status) {
            case 401, 403 -> FailureKind.AUTHENTICATION;
            case 429 -> FailureKind.RATE_LIMITED;
            case 502, 503 -> FailureKind.TRANSIENT;
            default -> status >= 500 ? FailureKind.AMBIGUOUS : FailureKind.UNKNOWN;
        };
        return new SmsProviderException(provider, kind, "HTTP " + status, abbreviate(response.body()));
    }

    /** A 2xx response whose body does not match the documented format; the request may have been accepted. */
    public static SmsProviderException unreadable(ProviderId provider, Response response, Exception cause) {
        return new SmsProviderException(provider, FailureKind.AMBIGUOUS, null,
                "Unreadable response: " + abbreviate(response.body()), cause);
    }

    /** The body as JSON, or {@code null} when it is not JSON (some providers answer errors in plain text). */
    public static @Nullable JsonNode parseJson(String body) {
        try {
            return JsonMapper.shared().readTree(body);
        }
        catch (JacksonException e) {
            return null;
        }
    }

    public static String toJson(Object value) {
        return JsonMapper.shared().writeValueAsString(value);
    }

    static String abbreviate(String body) {
        String trimmed = body.strip();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "...";
    }
}
