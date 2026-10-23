package io.github.hozdemir.smstr;

import java.util.regex.Pattern;

/**
 * Stable key of an SMS provider, used in configuration ({@code sms.default-provider=netgsm})
 * and webhook paths.
 */
public record ProviderId(String value) {

    private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9-]*");

    public static final ProviderId NETGSM = new ProviderId("netgsm");
    public static final ProviderId ILETIMERKEZI = new ProviderId("iletimerkezi");
    public static final ProviderId VERIMOR = new ProviderId("verimor");
    public static final ProviderId MUTLUCELL = new ProviderId("mutlucell");

    public ProviderId {
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Provider id must be lowercase kebab-case: " + value);
        }
    }

    public static ProviderId of(String value) {
        return new ProviderId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
