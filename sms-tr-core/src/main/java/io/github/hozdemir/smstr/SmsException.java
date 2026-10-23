package io.github.hozdemir.smstr;

import org.jspecify.annotations.Nullable;

/** Base type of every exception this library throws. */
public class SmsException extends RuntimeException {

    public SmsException(String message) {
        super(message);
    }

    public SmsException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
