package io.github.hozdemir.smstr.samples;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.hozdemir.smstr.DeliveryReport;
import io.github.hozdemir.smstr.DeliveryStatus;
import io.github.hozdemir.smstr.MessageId;
import io.github.hozdemir.smstr.ProviderId;
import io.github.hozdemir.smstr.SendResult;
import io.github.hozdemir.smstr.SmsMessage;
import io.github.hozdemir.smstr.spi.DeliveryReportQuery;
import io.github.hozdemir.smstr.spi.SmsProvider;

/**
 * A custom provider: one bean implementing {@link SmsProvider}. It logs messages and keeps them in memory,
 * which is handy in development and tests. Because it is the only provider in the {@code dev} profile, the
 * starter makes it the default without any {@code sms.*} settings.
 */
@Component
@Profile("dev")
public class OutboxSmsProvider implements SmsProvider, DeliveryReportQuery {

    private static final Logger log = LoggerFactory.getLogger(OutboxSmsProvider.class);
    private static final ProviderId ID = ProviderId.of("outbox");

    private final List<SmsMessage> messages = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public SendResult send(SmsMessage message) {
        messages.add(message);
        MessageId id = new MessageId(ID, String.valueOf(ids.incrementAndGet()));
        log.info("SMS {} to {}: {}", id, message.recipients(), message.text());
        return new SendResult(id, message.recipients());
    }

    @Override
    public List<DeliveryReport> fetchReports(MessageId messageId) {
        int index = Integer.parseInt(messageId.value()) - 1;
        return messages.get(index).recipients().stream()
                .map(to -> new DeliveryReport(messageId, to, DeliveryStatus.DELIVERED, "outbox", null))
                .toList();
    }

    public List<SmsMessage> messages() {
        return List.copyOf(messages);
    }
}
