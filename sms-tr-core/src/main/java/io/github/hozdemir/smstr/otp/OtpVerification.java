package io.github.hozdemir.smstr.otp;

public enum OtpVerification {

    /** Correct code; it is now consumed. */
    VERIFIED,
    INVALID_CODE,
    EXPIRED,
    /** Too many wrong guesses; the code is burned and a new one must be requested. */
    TOO_MANY_ATTEMPTS,
    /** No code is pending for this number and purpose. */
    NOT_FOUND;

    public boolean isVerified() {
        return this == VERIFIED;
    }
}
