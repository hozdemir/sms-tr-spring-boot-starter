package io.github.hozdemir.smstr.samples;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.hozdemir.smstr.PhoneNumber;
import io.github.hozdemir.smstr.otp.OtpSendResult;
import io.github.hozdemir.smstr.otp.OtpService;
import io.github.hozdemir.smstr.otp.OtpVerification;

/**
 * A login OTP flow over HTTP.
 *
 * <pre>
 * curl -X POST localhost:8080/otp/send   -H 'Content-Type: application/json' -d '{"phone":"05321234567"}'
 * curl -X POST localhost:8080/otp/verify -H 'Content-Type: application/json' -d '{"phone":"05321234567","code":"123456"}'
 * </pre>
 */
@RestController
@RequestMapping("/otp")
public class OtpController {

    private static final String PURPOSE = "login";

    public record SendRequest(String phone) {
    }

    public record VerifyRequest(String phone, String code) {
    }

    private final OtpService otp;

    public OtpController(OtpService otp) {
        this.otp = otp;
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody SendRequest request) {
        OtpSendResult result = otp.send(PhoneNumber.of(request.phone()), PURPOSE);
        if (result instanceof OtpSendResult.Throttled throttled) {
            long seconds = Math.max(1, throttled.retryAfter().toSeconds());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                    .body(Map.of("retryAfterSeconds", seconds));
        }
        OtpSendResult.Sent sent = (OtpSendResult.Sent) result;
        return ResponseEntity.accepted().body(Map.of("expiresAt", sent.expiresAt().toString()));
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, String>> verify(@RequestBody VerifyRequest request) {
        OtpVerification result = otp.verify(PhoneNumber.of(request.phone()), PURPOSE, request.code());
        return ResponseEntity.status(result.isVerified() ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
                .body(Map.of("result", result.name()));
    }

    /** {@link PhoneNumber#of} rejects anything that is not a Turkish mobile number. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
