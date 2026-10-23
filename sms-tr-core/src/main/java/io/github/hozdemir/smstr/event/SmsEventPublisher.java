package io.github.hozdemir.smstr.event;

@FunctionalInterface
public interface SmsEventPublisher {

    SmsEventPublisher NONE = event -> {
    };

    void publish(SmsEvent event);
}
