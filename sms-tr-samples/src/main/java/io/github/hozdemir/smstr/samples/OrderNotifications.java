package io.github.hozdemir.smstr.samples;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import io.github.hozdemir.smstr.DeliveryReport;
import io.github.hozdemir.smstr.DeliveryStatus;
import io.github.hozdemir.smstr.MessageType;
import io.github.hozdemir.smstr.PhoneNumber;
import io.github.hozdemir.smstr.SendResult;
import io.github.hozdemir.smstr.SmsClient;
import io.github.hozdemir.smstr.SmsMessage;

/** Everyday sending: a transactional notice, a scheduled campaign, and checking delivery. */
@Service
public class OrderNotifications {

    private final SmsClient sms;

    public OrderNotifications(SmsClient sms) {
        this.sms = sms;
    }

    /** Any common spelling of a Turkish mobile number works. */
    public SendResult orderShipped(String phone, String trackingCode) {
        return sms.send(phone, "Your order has shipped. Tracking code: " + trackingCode);
    }

    /** Commercial messages reach only recipients with İYS consent; the starter sets each provider's İYS fields. */
    public SendResult weekendCampaign(List<PhoneNumber> recipients, Instant sendAt) {
        return sms.send(SmsMessage.builder()
                .to(recipients)
                .text("20% off this weekend only")
                .type(MessageType.COMMERCIAL_INDIVIDUAL)
                .scheduledAt(sendAt)
                .build());
    }

    /** Asks the provider that sent the message, even if it was a fallback provider. */
    public boolean delivered(SendResult sent) {
        List<DeliveryReport> reports = sms.deliveryReports(sent.messageId());
        return !reports.isEmpty() && reports.stream().allMatch(r -> r.status() == DeliveryStatus.DELIVERED);
    }
}
