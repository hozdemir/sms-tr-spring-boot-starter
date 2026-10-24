package io.github.hozdemir.smstr.samples;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Runs with the {@code dev} profile by default, where {@link OutboxSmsProvider} records messages instead of
 * sending them. Start with {@code --spring.profiles.active=prod} and real credentials to send for real.
 */
@SpringBootApplication
public class SampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }
}
