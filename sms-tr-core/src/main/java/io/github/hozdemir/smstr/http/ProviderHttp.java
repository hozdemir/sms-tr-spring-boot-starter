package io.github.hozdemir.smstr.http;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import io.github.hozdemir.smstr.FailureKind;
import io.github.hozdemir.smstr.ProviderId;
import io.github.hozdemir.smstr.SmsProviderException;

/** HTTP plumbing shared by the built-in providers. */
public final class ProviderHttp {

    /** Providers read and write timestamps in Turkish local time. */
    public static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

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

    /**
     * Classify a transport or HTTP status failure. Failures before the request left the machine are
     * {@link FailureKind#TRANSIENT}; failures after it may have been processed are {@link FailureKind#AMBIGUOUS}.
     */
    public static SmsProviderException failure(ProviderId provider, RestClientException e) {
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            FailureKind kind = switch (status) {
                case 401, 403 -> FailureKind.AUTHENTICATION;
                case 429 -> FailureKind.RATE_LIMITED;
                case 502, 503 -> FailureKind.TRANSIENT;
                default -> status >= 500 ? FailureKind.AMBIGUOUS : FailureKind.UNKNOWN;
            };
            return new SmsProviderException(provider, kind, "HTTP " + status,
                    response.getResponseBodyAsString(), e);
        }
        if (e instanceof ResourceAccessException && neverSent(e)) {
            return new SmsProviderException(provider, FailureKind.TRANSIENT, null, "Could not connect: " + e.getMessage(), e);
        }
        return new SmsProviderException(provider, FailureKind.AMBIGUOUS, null, e.getMessage(), e);
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

    /** Whether the text needs a Turkish character set to arrive intact. */
    public static boolean hasTurkishCharacters(String text) {
        return text.chars().anyMatch(c -> "çÇğĞıİöÖşŞüÜ".indexOf(c) >= 0);
    }
}
