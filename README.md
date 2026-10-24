# SMS TR Spring Boot Starter

[![Maven Central](https://img.shields.io/maven-central/v/io.github.hozdemir.smstr/sms-tr-spring-boot-starter)](https://central.sonatype.com/artifact/io.github.hozdemir.smstr/sms-tr-spring-boot-starter)
[![CI](https://github.com/hozdemir/sms-tr-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/hozdemir/sms-tr-spring-boot-starter/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

A Spring Boot 4 starter for Turkish SMS providers. Netgsm, İleti Merkezi, Verimor, Mutlucell and VatanSMS sit behind one API. Switching providers is a one-line setting. Retry, failover, OTP and delivery reports work out of the box.

- **Provider switching and failover.** When the default provider fails, the next one is tried. The kind of failure decides whether that happens.
- **Safe retries.** Temporary failures are retried. A request that was sent but got no answer (a read timeout) is not retried, so a customer never receives the same SMS twice.
- **OTP.** Generating, sending and verifying codes is built in. Codes are stored hashed, with an attempt limit, an expiry and a resend cooldown.
- **Delivery reports.** Query them (pull) or receive them through a webhook (push). Pushed reports are published as Spring events.
- **İYS.** Choose the message type once (`TRANSACTIONAL`, `OTP`, `COMMERCIAL_INDIVIDUAL`, `COMMERCIAL_MERCHANT`). The starter fills in each provider's own İYS fields.
- **Extensible.** A new provider is one bean that implements one interface.

Requirements: Java 17+, Spring Boot 4.x.

## Supported providers

| Provider | Website | API documentation |
|---|---|---|
| Netgsm | [netgsm.com.tr](https://www.netgsm.com.tr) | [netgsm.com.tr/dokuman](https://www.netgsm.com.tr/dokuman/) |
| İleti Merkezi | [iletimerkezi.com](https://www.iletimerkezi.com) | [iletimerkezi.com/en/docs/api](https://www.iletimerkezi.com/en/docs/api) |
| Verimor | [verimor.com.tr](https://www.verimor.com.tr) | [docs.verimor.com.tr](https://docs.verimor.com.tr), [github.com/verimor/SMS-API](https://github.com/verimor/SMS-API) |
| Mutlucell | [mutlucell.com.tr](https://www.mutlucell.com.tr) | [mutlucell.com.tr/api](https://www.mutlucell.com.tr/api/) |
| VatanSMS | [vatansms.net](https://www.vatansms.net) | [vatansms.net/sms-api](https://www.vatansms.net/sms-api) |

## Installation

```xml
<dependency>
    <groupId>io.github.hozdemir.smstr</groupId>
    <artifactId>sms-tr-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

Gradle: `implementation("io.github.hozdemir.smstr:sms-tr-spring-boot-starter:0.1.0")`

## Configuration

A provider is enabled when its credentials are set. With a single provider, `default-provider` is not needed.

```yaml
sms:
  default-provider: netgsm
  fallback: [verimor]            # tried in order when netgsm fails

  netgsm:
    username: 8501234567         # subscriber number (abone numarası)
    password: ${NETGSM_PASSWORD} # password of an "API Kullanıcısı" (API user) sub-user
    sender: MYBRAND
    otp-endpoint: false          # true: OTPs use Netgsm's priority OTP route (needs an OTP package)

  verimor:
    username: 908501234567
    password: ${VERIMOR_PASSWORD}
    sender: MYBRAND              # optional; Verimor uses the account's first header when omitted

  iletimerkezi:
    api-key: ${ILETIMERKEZI_KEY}
    api-hash: ${ILETIMERKEZI_HASH} # the hash shown in the panel next to the key
    sender: MYBRAND

  mutlucell:
    username: myuser
    password: ${MUTLUCELL_PASSWORD}
    sender: MYBRAND
    brand-code: "123456"         # İYS brand code, only for commercial messages

  vatansms:
    api-id: ${VATANSMS_API_ID}   # Hesabım > API Bilgilerimi Görüntüle (My Account > View API Details)
    api-key: ${VATANSMS_API_KEY}
    sender: MYBRAND

  retry:
    max-attempts: 3              # total attempts per provider
    initial-delay: 500ms
    multiplier: 2.0
    max-delay: 5s
    retry-ambiguous: false
  timeout:
    connect: 3s
    read: 10s
```

Missing or conflicting settings stop the application at startup, and the message names the property. Examples: `sms.netgsm.password must be set` and `choose one with sms.default-provider`.

## Usage

```java
@Service
class OrderNotifications {

    private final SmsClient sms;

    OrderNotifications(SmsClient sms) {
        this.sms = sms;
    }

    void shipped(String phone) {
        sms.send(phone, "Your order has shipped.");
    }

    void campaign(List<PhoneNumber> recipients) {
        SendResult result = sms.send(SmsMessage.builder()
                .to(recipients)
                .text("20% off this weekend only")
                .type(MessageType.COMMERCIAL_INDIVIDUAL)
                .scheduledAt(Instant.parse("2026-11-01T07:00:00Z"))
                .build());
    }
}
```

Numbers may be written as `+90 532 123 45 67`, `0532 123 4567`, `905321234567` or `5321234567`. Each provider receives the format it expects.

To force one provider, call `sms.using("verimor").send(...)`. That call retries but does not fail over.

### Failures, retry and failover

Every failure is an `SmsProviderException`. Its `kind()` decides retry and failover, and `providerCode()` gives the provider's own error code.

| `FailureKind` | Example | Retry same provider | Fail over to next |
|---|---|---|---|
| `TRANSIENT` | Could not connect, 502/503, provider system error | yes | yes |
| `RATE_LIMITED` | HTTP 429, Netgsm `80`/`85` | yes | yes |
| `AUTHENTICATION` | Wrong password, IP not allowed | no | yes |
| `INSUFFICIENT_CREDIT` | Out of credit | no | yes |
| `SENDER_NOT_ALLOWED` | Header not defined, no İYS permission, no OTP package | no | yes |
| `INVALID_REQUEST` | Invalid number or text | no | no |
| `AMBIGUOUS` | Read timeout, 500/504; the message may have gone out | no* | no* |
| `UNKNOWN` | Unrecognised error code; the message was not accepted | no | yes |

\* Enable with `sms.retry.retry-ambiguous=true`, which accepts the risk of duplicate messages.

### OTP

```java
OtpSendResult sent = otpService.send(PhoneNumber.of(phone), "login");
if (sent instanceof OtpSendResult.Throttled t) {
    // wait t.retryAfter()
}

OtpVerification result = otpService.verify(PhoneNumber.of(phone), "login", enteredCode);
if (result.isVerified()) { ... }   // otherwise INVALID_CODE, EXPIRED, TOO_MANY_ATTEMPTS, NOT_FOUND
```

```yaml
sms:
  otp:
    length: 6
    ttl: 3m
    max-attempts: 5
    resend-cooldown: 60s
    template: "Doğrulama kodunuz: {code}"
```

Without a `template`, the message is Turkish (`Doğrulama kodunuz: {code}`, "Your verification code"), since recipients are Turkish numbers.

The default `InMemoryOtpStore` suits a single instance. With several instances, define a shared `OtpStore` bean, for example on Redis. The only operation that must be atomic is `incrementAttempts`.

### Delivery reports

To query (pull):

```java
List<DeliveryReport> reports = sms.deliveryReports(result.messageId());
```

A `MessageId` carries the provider that sent the message. The query reaches the right provider even when the message went out through failover.

To receive reports by push, first enable the webhook:

```yaml
sms:
  webhook:
    enabled: true
    token: ${SMS_WEBHOOK_TOKEN}   # at least 16 characters
```

Then enter `https://your-app/sms/webhooks/{provider}/{token}` as the callback URL in the provider's panel. To listen for incoming reports:

```java
@EventListener
void on(SmsEvent.DeliveryReportReceived event) { ... }
```

`SmsEvent.Sent` and `SmsEvent.Failed` are published as well.

### Sending asynchronously

`SmsClient.send` blocks while it retries and fails over, which can take seconds. To keep that off a request thread, call it from your own executor; the starter does not add one.

```java
@Async
public void orderShipped(String phone) {
    sms.send(phone, "Your order has shipped.");
}
```

Enable it with `@EnableAsync`. On Java 21+, `spring.threads.virtual.enabled=true` makes Spring Boot's task executor use virtual threads, so blocking calls are cheap. A failure inside an `@Async` method has no caller to reach, so listen for `SmsEvent.Failed` to see it.

This is in-process: a message still waiting when the application stops is lost. If messages must survive restarts, store them first (a transactional outbox in your database) and send from that table.

### Provider differences

| | Netgsm | İleti Merkezi | Verimor | Mutlucell | VatanSMS |
|---|---|---|---|---|---|
| Pull reports | yes; each job id once per minute | yes | yes; 20 requests per minute | yes; **polling more often than every 15 minutes gets the account blocked** | no; the response format is undocumented |
| Push reports (webhook) | no | yes; one URL, set in the panel | yes; one URL, set in OİM | no | no |
| Dedicated OTP route | yes; enable with `otp-endpoint: true` | no | no | no | no |
| Turkish characters | automatic | panel setting | automatic | automatic | automatic |
| Insufficient credit | shows in the report, not at send time | error at send time | error at send time | error at send time | undocumented |

The starter enforces Mutlucell's 15-minute limit itself. Querying the same package sooner never reaches Mutlucell and fails with `RATE_LIMITED`. Verimor only accepts sends from server IPs allowed in OİM. VatanSMS documents its requests but not its responses or error codes, so its failures are classified by HTTP status, and anything else counts as `UNKNOWN`. Every API contract is documented with sources in [docs/provider-apis.md](docs/provider-apis.md).

## Adding a provider

Implement `SmsProvider` and register it as a bean. Routing, retry, failover and OTP then work with no other setup.

```java
@Component
class AcmeSmsProvider implements SmsProvider, DeliveryReportQuery {

    @Override
    public ProviderId id() {
        return ProviderId.of("acme");
    }

    @Override
    public SendResult send(SmsMessage message) {
        // Make one attempt; the starter handles retries.
        // Throw SmsProviderException with an accurate FailureKind.
    }

    @Override
    public List<DeliveryReport> fetchReports(MessageId messageId) { ... }
}
```

Make it the default with `sms.default-provider=acme`, or add it to `sms.fallback`. If the provider pushes reports, also implement `DeliveryReportWebhook`. For the HTTP code, `ProviderHttp.exchange` and `ProviderHttp.statusFailure` classify network failures.

Every bean the starter defines (`SmsClient`, `OtpStore`, `ResiliencePolicy`, the built-in providers) backs off when you define your own bean of the same type.

## Examples

[`sms-tr-samples`](sms-tr-samples) is a runnable application that CI builds and tests on every change:

- [`OrderNotifications`](sms-tr-samples/src/main/java/io/github/hozdemir/smstr/samples/OrderNotifications.java) sends a transactional notice and a scheduled commercial campaign, then checks delivery.
- [`OtpController`](sms-tr-samples/src/main/java/io/github/hozdemir/smstr/samples/OtpController.java) runs a login OTP flow over HTTP, including the resend cooldown (`429` with `Retry-After`).
- [`SmsEventLogger`](sms-tr-samples/src/main/java/io/github/hozdemir/smstr/samples/SmsEventLogger.java) reacts to sent, failed and delivery report events.
- [`OutboxSmsProvider`](sms-tr-samples/src/main/java/io/github/hozdemir/smstr/samples/OutboxSmsProvider.java) is a custom provider that logs messages instead of sending them. It is useful in development and tests.
- [`application-prod.yml`](sms-tr-samples/src/main/resources/application-prod.yml) configures Netgsm with Verimor as fallback, plus the webhook.

Run it without any provider account; messages go to the log:

```bash
./mvnw -pl sms-tr-samples -am install -DskipTests
java -jar sms-tr-samples/target/sms-tr-samples-*-SNAPSHOT.jar

curl -X POST localhost:8080/otp/send -H 'Content-Type: application/json' -d '{"phone":"05321234567"}'
# read the code from the log, then:
curl -X POST localhost:8080/otp/verify -H 'Content-Type: application/json' -d '{"phone":"05321234567","code":"<code>"}'
```

## Modules

- `sms-tr-core` does not depend on Spring Boot. It holds the domain model, the client, OTP and the providers.
- `sms-tr-spring-boot-autoconfigure` holds the auto-configuration and the properties.
- `sms-tr-spring-boot-starter` is the single dependency an application adds.
- `sms-tr-samples` holds the examples. It is not published.

## Building from source

Requires Java 17+. The Maven wrapper downloads Maven itself.

```bash
./mvnw verify
```

Maintainers: the release process is in [RELEASING.md](RELEASING.md).

## License

Apache License 2.0
