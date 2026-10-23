package io.github.hozdemir.smstr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumberTest {

    @ParameterizedTest
    @ValueSource(strings = { "5321234567", "05321234567", "905321234567", "+905321234567", "00905321234567",
            "+90 (532) 123 45 67", "0532-123-45-67" })
    void normalizesCommonSpellings(String raw) {
        PhoneNumber number = PhoneNumber.of(raw);

        assertThat(number.national()).isEqualTo("5321234567");
        assertThat(number.withCountryCode()).isEqualTo("905321234567");
        assertThat(number.withLeadingZero()).isEqualTo("05321234567");
        assertThat(number.e164()).isEqualTo("+905321234567");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "2121234567", "0212 123 45 67", "53212345", "+15551234567", "53212345678" })
    void rejectsNonTurkishMobileNumbers(String raw) {
        assertThatIllegalArgumentException().isThrownBy(() -> PhoneNumber.of(raw));
    }
}
