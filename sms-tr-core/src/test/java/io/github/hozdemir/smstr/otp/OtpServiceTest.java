package io.github.hozdemir.smstr.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.hozdemir.smstr.DeliveryReport;
import io.github.hozdemir.smstr.FailureKind;
import io.github.hozdemir.smstr.MessageId;
import io.github.hozdemir.smstr.MessageType;
import io.github.hozdemir.smstr.PhoneNumber;
import io.github.hozdemir.smstr.ProviderId;
import io.github.hozdemir.smstr.SendResult;
import io.github.hozdemir.smstr.SmsClient;
import io.github.hozdemir.smstr.SmsMessage;
import io.github.hozdemir.smstr.SmsProviderException;

class OtpServiceTest {

    private static final String PHONE = "05321234567";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T10:00:00Z"));
    private final RecordingSmsClient sms = new RecordingSmsClient();
    private final OtpPolicy policy = new OtpPolicy(6, Duration.ofMinutes(3), 3, Duration.ofSeconds(60),
            "Kodunuz: {code}");
    private final OtpService otp = new OtpService(sms, new InMemoryOtpStore(clock), policy, clock);

    @Test
    void sendsAnOtpMessageAndVerifiesTheCodeOnce() {
        OtpSendResult result = otp.send(PHONE);

        assertThat(result).isInstanceOf(OtpSendResult.Sent.class);
        SmsMessage message = sms.sent.get(0);
        assertThat(message.type()).isEqualTo(MessageType.OTP);
        assertThat(message.recipients()).containsExactly(PhoneNumber.of(PHONE));
        String code = sms.lastCode();
        assertThat(code).hasSize(6).containsOnlyDigits();

        assertThat(otp.verify(PHONE, code)).isEqualTo(OtpVerification.VERIFIED);
        assertThat(otp.verify(PHONE, code)).isEqualTo(OtpVerification.NOT_FOUND);
    }

    @Test
    void wrongCodesBurnTheCodeAfterMaxAttempts() {
        otp.send(PHONE);
        String code = sms.lastCode();

        assertThat(otp.verify(PHONE, "000000x")).isEqualTo(OtpVerification.INVALID_CODE);
        assertThat(otp.verify(PHONE, "000000y")).isEqualTo(OtpVerification.INVALID_CODE);
        assertThat(otp.verify(PHONE, "000000z")).isEqualTo(OtpVerification.INVALID_CODE);
        assertThat(otp.verify(PHONE, code)).isEqualTo(OtpVerification.TOO_MANY_ATTEMPTS);
        assertThat(otp.verify(PHONE, code)).isEqualTo(OtpVerification.NOT_FOUND);
    }

    @Test
    void codeExpiresAfterTtl() {
        otp.send(PHONE);
        clock.advance(Duration.ofMinutes(3));

        assertThat(otp.verify(PHONE, sms.lastCode())).isEqualTo(OtpVerification.EXPIRED);
    }

    @Test
    void resendIsThrottledUntilCooldownPasses() {
        otp.send(PHONE);
        clock.advance(Duration.ofSeconds(20));

        assertThat(otp.send(PHONE)).isEqualTo(new OtpSendResult.Throttled(Duration.ofSeconds(40)));
        assertThat(sms.sent).hasSize(1);

        clock.advance(Duration.ofSeconds(40));
        assertThat(otp.send(PHONE)).isInstanceOf(OtpSendResult.Sent.class);
        assertThat(sms.sent).hasSize(2);
    }

    @Test
    void resendReplacesThePreviousCode() {
        otp.send(PHONE);
        String first = sms.lastCode();
        clock.advance(Duration.ofSeconds(60));
        otp.send(PHONE);
        String second = sms.lastCode();

        if (!first.equals(second)) {
            assertThat(otp.verify(PHONE, first)).isEqualTo(OtpVerification.INVALID_CODE);
        }
        assertThat(otp.verify(PHONE, second)).isEqualTo(OtpVerification.VERIFIED);
    }

    @Test
    void purposesAreIndependent() {
        PhoneNumber phone = PhoneNumber.of(PHONE);
        otp.send(phone, "login");
        String login = sms.lastCode();
        otp.send(phone, "password-reset");

        assertThat(otp.verify(phone, "password-reset", login)).isNotEqualTo(OtpVerification.NOT_FOUND);
        assertThat(otp.verify(phone, "login", login)).isEqualTo(OtpVerification.VERIFIED);
    }

    @Test
    void failedSendLeavesNoPendingCodeAndNoCooldown() {
        sms.failNext = true;

        assertThatThrownBy(() -> otp.send(PHONE)).isInstanceOf(SmsProviderException.class);
        assertThat(otp.verify(PHONE, "123456")).isEqualTo(OtpVerification.NOT_FOUND);
        assertThat(otp.send(PHONE)).isInstanceOf(OtpSendResult.Sent.class);
    }

    private static final class RecordingSmsClient implements SmsClient {

        private static final Pattern CODE = Pattern.compile("Kodunuz: (\\d+)");

        final List<SmsMessage> sent = new ArrayList<>();
        boolean failNext;

        @Override
        public SendResult send(SmsMessage message) {
            if (failNext) {
                failNext = false;
                throw new SmsProviderException(ProviderId.of("test"), FailureKind.TRANSIENT, null, "down");
            }
            sent.add(message);
            return new SendResult(new MessageId(ProviderId.of("test"), "1"), message.recipients());
        }

        String lastCode() {
            Matcher matcher = CODE.matcher(sent.get(sent.size() - 1).text());
            assertThat(matcher.matches()).isTrue();
            return matcher.group(1);
        }

        @Override
        public List<DeliveryReport> deliveryReports(MessageId messageId) {
            return List.of();
        }

        @Override
        public SmsClient using(ProviderId provider) {
            return this;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
