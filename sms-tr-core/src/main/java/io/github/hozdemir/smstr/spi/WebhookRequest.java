package io.github.hozdemir.smstr.spi;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/** A provider callback, independent of the web stack that received it. */
public record WebhookRequest(String method, Map<String, List<String>> parameters, byte[] body,
        @Nullable String contentType) {

    public WebhookRequest {
        parameters = Map.copyOf(parameters);
    }

    public @Nullable String parameter(String name) {
        List<String> values = parameters.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
