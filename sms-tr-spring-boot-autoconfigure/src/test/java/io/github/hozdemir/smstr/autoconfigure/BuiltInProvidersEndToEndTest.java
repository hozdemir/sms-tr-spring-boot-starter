package io.github.hozdemir.smstr.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.hozdemir.smstr.FailureKind;
import io.github.hozdemir.smstr.ProviderId;
import io.github.hozdemir.smstr.SmsClient;
import io.github.hozdemir.smstr.SmsProviderException;
import io.github.hozdemir.smstr.provider.netgsm.NetgsmSmsProvider;

/** Real HTTP against a local server: property binding, timeouts, retry and failover together. */
class BuiltInProvidersEndToEndTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class, SmsAutoConfiguration.class))
            .withPropertyValues("sms.retry.initial-delay=10ms", "sms.retry.max-delay=10ms");

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger netgsmCalls = new AtomicInteger();
    private volatile long netgsmDelayMillis;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sms/rest/v2/send", exchange -> {
            netgsmCalls.incrementAndGet();
            record(exchange);
            sleep(netgsmDelayMillis);
            respond(exchange, 200, "{\"code\":\"00\",\"jobid\":\"J1\",\"description\":\"queued\"}");
        });
        server.createContext("/v2/send.json", exchange -> {
            record(exchange);
            respond(exchange, 200, "777");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsThroughNetgsmConfiguredByProperties() {
        runner.withPropertyValues(netgsm(baseUrl())).run(context -> {
            assertThat(context).hasSingleBean(NetgsmSmsProvider.class);

            var result = context.getBean(SmsClient.class).send("05321234567", "Merhaba");

            assertThat(result.messageId().value()).isEqualTo("J1");
            assertThat(requests).singleElement().asString()
                    .contains("Basic ODUwMTIzNDU2NzpzZWNyZXQ=").contains("\"no\":\"5321234567\"");
        });
    }

    @Test
    void readTimeoutIsNotRetriedSoTheSmsIsNeverSentTwice() {
        netgsmDelayMillis = 1500;
        runner.withPropertyValues(netgsm(baseUrl()))
                .withPropertyValues(verimor(baseUrl()))
                .withPropertyValues("sms.default-provider=netgsm", "sms.fallback=verimor", "sms.timeout.read=200ms")
                .run(context -> {
                    assertThatThrownBy(() -> context.getBean(SmsClient.class).send("05321234567", "Merhaba"))
                            .isInstanceOfSatisfying(SmsProviderException.class,
                                    e -> assertThat(e.kind()).isEqualTo(FailureKind.AMBIGUOUS));
                    assertThat(netgsmCalls).hasValue(1);
                    assertThat(requests).hasSize(1);
                });
    }

    @Test
    void connectionRefusedIsRetriedThenFailsOver() throws IOException {
        runner.withPropertyValues(netgsm(closedPortUrl()))
                .withPropertyValues(verimor(baseUrl()))
                .withPropertyValues("sms.default-provider=netgsm", "sms.fallback=verimor")
                .run(context -> {
                    var result = context.getBean(SmsClient.class).send("05321234567", "Merhaba");

                    assertThat(result.provider()).isEqualTo(ProviderId.VERIMOR);
                    assertThat(result.messageId().value()).isEqualTo("777");
                });
    }

    @Test
    void incompleteCredentialsFailStartupNamingTheProperty() {
        runner.withPropertyValues("sms.netgsm.username=8501234567", "sms.netgsm.sender=BASLIK")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("sms.netgsm.password must be set"));
    }

    private String[] netgsm(String baseUrl) {
        return new String[] { "sms.netgsm.username=8501234567", "sms.netgsm.password=secret",
                "sms.netgsm.sender=BASLIK", "sms.netgsm.base-url=" + baseUrl };
    }

    private String[] verimor(String baseUrl) {
        return new String[] { "sms.verimor.username=908501234567", "sms.verimor.password=secret",
                "sms.verimor.base-url=" + baseUrl };
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String closedPortUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    private void record(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestHeaders().getFirst("Authorization") + " "
                + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        catch (IOException clientGone) {
            // The client timed out and closed the connection.
        }
        finally {
            exchange.close();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
