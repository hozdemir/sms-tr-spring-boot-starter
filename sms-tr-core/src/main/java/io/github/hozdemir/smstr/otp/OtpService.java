package io.github.hozdemir.smstr.otp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import io.github.hozdemir.smstr.MessageType;
import io.github.hozdemir.smstr.PhoneNumber;
import io.github.hozdemir.smstr.SendResult;
import io.github.hozdemir.smstr.SmsClient;
import io.github.hozdemir.smstr.SmsMessage;

/**
 * Sends and verifies one-time passwords. A {@code purpose} ("login", "password-reset") keeps codes for
 * different flows to the same number apart.
 */
public final class OtpService {

    public static final String DEFAULT_PURPOSE = "default";

    private final SmsClient smsClient;
    private final OtpStore store;
    private final OtpPolicy policy;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OtpService(SmsClient smsClient, OtpStore store, OtpPolicy policy, Clock clock) {
        this.smsClient = smsClient;
        this.store = store;
        this.policy = policy;
        this.clock = clock;
    }

    public OtpSendResult send(String phoneNumber) {
        return send(PhoneNumber.of(phoneNumber), DEFAULT_PURPOSE);
    }

    public OtpSendResult send(PhoneNumber to, String purpose) {
        String key = key(to, purpose);
        Instant now = clock.instant();
        String code = generateCode();
        String salt = HexFormat.of().formatHex(randomBytes(16));
        OtpStore.Entry entry = new OtpStore.Entry(hash(salt, code), salt, now, now.plus(policy.ttl()));

        Optional<OtpStore.Entry> recent = store.saveUnlessRecent(key, entry, policy.resendCooldown());
        if (recent.isPresent()) {
            return new OtpSendResult.Throttled(Duration.between(now, recent.get().sentAt().plus(policy.resendCooldown())));
        }

        SmsMessage message = SmsMessage.builder()
                .to(to)
                .text(policy.template().replace(OtpPolicy.CODE_PLACEHOLDER, code))
                .type(MessageType.OTP)
                .build();
        try {
            SendResult result = smsClient.send(message);
            return new OtpSendResult.Sent(result, entry.expiresAt());
        }
        catch (RuntimeException e) {
            store.remove(key, entry);
            throw e;
        }
    }

    public OtpVerification verify(String phoneNumber, String code) {
        return verify(PhoneNumber.of(phoneNumber), DEFAULT_PURPOSE, code);
    }

    public OtpVerification verify(PhoneNumber phoneNumber, String purpose, String code) {
        String key = key(phoneNumber, purpose);
        Optional<OtpStore.Entry> found = store.find(key);
        if (found.isEmpty()) {
            return OtpVerification.NOT_FOUND;
        }
        OtpStore.Entry entry = found.get();
        if (!clock.instant().isBefore(entry.expiresAt())) {
            store.remove(key, entry);
            return OtpVerification.EXPIRED;
        }
        if (store.incrementAttempts(key) > policy.maxAttempts()) {
            store.remove(key, entry);
            return OtpVerification.TOO_MANY_ATTEMPTS;
        }
        boolean matches = MessageDigest.isEqual(
                hash(entry.salt(), code).getBytes(StandardCharsets.US_ASCII),
                entry.codeHash().getBytes(StandardCharsets.US_ASCII));
        if (!matches) {
            return OtpVerification.INVALID_CODE;
        }
        // Only the request that removes the code may use it; a concurrent request with the same code gets NOT_FOUND.
        return store.remove(key, entry) ? OtpVerification.VERIFIED : OtpVerification.NOT_FOUND;
    }

    private static String key(PhoneNumber phoneNumber, String purpose) {
        return purpose + ":" + phoneNumber.withCountryCode();
    }

    private String generateCode() {
        StringBuilder code = new StringBuilder(policy.length());
        for (int i = 0; i < policy.length(); i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }

    private byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        random.nextBytes(bytes);
        return bytes;
    }

    private static String hash(String salt, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((salt + code).getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
