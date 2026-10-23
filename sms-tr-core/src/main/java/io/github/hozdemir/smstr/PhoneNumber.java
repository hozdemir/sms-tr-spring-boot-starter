package io.github.hozdemir.smstr;

import java.util.regex.Pattern;

/**
 * A Turkish mobile number, normalized once at creation.
 * <p>
 * Accepts the common spellings ({@code +90 532 123 45 67}, {@code 0532 123 4567},
 * {@code 905321234567}, {@code 5321234567}) and exposes each format providers expect.
 */
public final class PhoneNumber {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().]");
    private static final Pattern NATIONAL = Pattern.compile("5\\d{9}");

    private final String national;

    private PhoneNumber(String national) {
        this.national = national;
    }

    public static PhoneNumber of(String raw) {
        String digits = SEPARATORS.matcher(raw).replaceAll("");
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        if (digits.startsWith("0090")) {
            digits = digits.substring(4);
        }
        else if (digits.startsWith("90") && digits.length() == 12) {
            digits = digits.substring(2);
        }
        else if (digits.startsWith("0") && digits.length() == 11) {
            digits = digits.substring(1);
        }
        if (!NATIONAL.matcher(digits).matches()) {
            throw new IllegalArgumentException("Not a Turkish mobile number: " + raw);
        }
        return new PhoneNumber(digits);
    }

    /** {@code 5321234567} */
    public String national() {
        return national;
    }

    /** {@code 05321234567} */
    public String withLeadingZero() {
        return "0" + national;
    }

    /** {@code 905321234567} */
    public String withCountryCode() {
        return "90" + national;
    }

    /** {@code +905321234567} */
    public String e164() {
        return "+90" + national;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PhoneNumber that && national.equals(that.national);
    }

    @Override
    public int hashCode() {
        return national.hashCode();
    }

    @Override
    public String toString() {
        return e164();
    }
}
