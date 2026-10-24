package io.github.hozdemir.smstr.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.ResourceAccessException;

import io.github.hozdemir.smstr.FailureKind;
import io.github.hozdemir.smstr.ProviderId;

class ProviderHttpTest {

    static Object[][] transportFailures() {
        return new Object[][] {
                { new ConnectException("Connection refused"), FailureKind.TRANSIENT },
                { new SSLHandshakeException("PKIX path building failed"), FailureKind.TRANSIENT },
                { new HttpTimeoutException("request timed out"), FailureKind.AMBIGUOUS },
                { new IOException("Connection reset"), FailureKind.AMBIGUOUS } };
    }

    @ParameterizedTest
    @MethodSource("transportFailures")
    void failuresBeforeTheRequestIsSentAreSafeToRepeat(IOException cause, FailureKind expected) {
        var failure = ProviderHttp.transportFailure(ProviderId.NETGSM, new ResourceAccessException("I/O error", cause));

        assertThat(failure.kind()).isEqualTo(expected);
    }
}
