package io.github.hozdemir.smstr.autoconfigure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import io.github.hozdemir.smstr.DeliveryReportWebhookHandler;
import io.github.hozdemir.smstr.ProviderId;
import io.github.hozdemir.smstr.SmsException;
import io.github.hozdemir.smstr.spi.WebhookRequest;

/** Serves {@code {sms.webhook.path}/{provider}/{token}} for providers that push delivery reports. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RouterFunction.class)
@ConditionalOnBooleanProperty("sms.webhook.enabled")
class SmsWebhookConfiguration {

    @Bean
    RouterFunction<ServerResponse> smsWebhookRoutes(DeliveryReportWebhookHandler handler, SmsProperties properties) {
        SmsProperties.Webhook webhook = properties.webhook();
        String token = webhook.token();
        if (token == null || token.length() < 16) {
            throw new IllegalStateException("sms.webhook.token must be set to a secret of at least 16 characters "
                    + "when sms.webhook.enabled=true; it authenticates provider callbacks.");
        }
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        String pattern = webhook.path() + "/{provider}/{token}";
        return RouterFunctions.route()
                .GET(pattern, request -> handle(handler, expected, request))
                .POST(pattern, request -> handle(handler, expected, request))
                .build();
    }

    private static ServerResponse handle(DeliveryReportWebhookHandler handler, byte[] expectedToken,
            ServerRequest request) throws ServletException, IOException {
        byte[] token = request.pathVariable("token").getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(token, expectedToken)) {
            return ServerResponse.status(HttpStatus.NOT_FOUND).build();
        }
        Map<String, List<String>> parameters = new HashMap<>(request.params());
        WebhookRequest webhookRequest = new WebhookRequest(request.method().name(), parameters,
                request.body(byte[].class),
                request.headers().contentType().map(MediaType::toString).orElse(null));
        try {
            handler.handle(ProviderId.of(request.pathVariable("provider")), webhookRequest);
            return ServerResponse.ok().contentType(MediaType.TEXT_PLAIN).body("OK");
        }
        catch (IllegalArgumentException | SmsException e) {
            return ServerResponse.badRequest().contentType(MediaType.TEXT_PLAIN).body(e.getMessage());
        }
    }
}
