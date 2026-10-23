package io.github.hozdemir.smstr;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One text sent to one or more recipients.
 *
 * @param sender originator header (başlık); {@code null} uses the provider's configured default
 * @param scheduledAt send later; {@code null} sends immediately
 */
public record SmsMessage(
        List<PhoneNumber> recipients,
        String text,
        @Nullable String sender,
        @Nullable Instant scheduledAt,
        MessageType type) {

    public SmsMessage {
        recipients = List.copyOf(recipients);
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("At least one recipient is required");
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("Message text must not be blank");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final List<PhoneNumber> recipients = new ArrayList<>();
        private @Nullable String text;
        private @Nullable String sender;
        private @Nullable Instant scheduledAt;
        private MessageType type = MessageType.TRANSACTIONAL;

        private Builder() {
        }

        public Builder to(String... numbers) {
            for (String number : numbers) {
                recipients.add(PhoneNumber.of(number));
            }
            return this;
        }

        public Builder to(PhoneNumber... numbers) {
            recipients.addAll(List.of(numbers));
            return this;
        }

        public Builder to(List<PhoneNumber> numbers) {
            recipients.addAll(numbers);
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder sender(String sender) {
            this.sender = sender;
            return this;
        }

        public Builder scheduledAt(Instant scheduledAt) {
            this.scheduledAt = scheduledAt;
            return this;
        }

        public Builder type(MessageType type) {
            this.type = type;
            return this;
        }

        public SmsMessage build() {
            if (text == null) {
                throw new IllegalArgumentException("Message text is required");
            }
            return new SmsMessage(recipients, text, sender, scheduledAt, type);
        }
    }
}
