# SMS TR Spring Boot Starter

Türkiye'deki SMS sağlayıcıları için Spring Boot 4 starter'ı. Netgsm, İleti Merkezi, Verimor ve Mutlucell tek bir API arkasında çalışır. Sağlayıcı değiştirmek bir satırlık ayardır. Retry, fallback, OTP ve teslimat raporu da kutudan çıkar çıkmaz hazırdır.

- **Sağlayıcı değiştirme ve fallback.** Varsayılan sağlayıcı başarısız olursa sıradakine geçilir. Geçişe hata türü karar verir.
- **Güvenli retry.** Geçici hatalar tekrar denenir. İstek gittikten sonra cevap gelmezse (read timeout) tekrar denenmez, böylece müşteriye aynı SMS iki kez gitmez.
- **OTP.** Kod üretme, gönderme ve doğrulama hazırdır. Kod hash'lenerek saklanır. Deneme limiti, süre sonu ve tekrar gönderme bekleme süresi uygulanır.
- **Teslimat raporu.** Raporlar sorgulanabilir (pull) ya da webhook ile alınır (push). Push ile gelen raporlar Spring event olarak yayınlanır.
- **İYS.** Mesaj türünü (`TRANSACTIONAL`, `OTP`, `COMMERCIAL_INDIVIDUAL`, `COMMERCIAL_MERCHANT`) bir kez seçersiniz. Her sağlayıcının kendi İYS alanını starter doldurur.
- **Genişletilebilir.** Yeni bir sağlayıcı, tek bir arayüzü implemente eden bir bean'dir.

Gereksinimler: Java 17+, Spring Boot 4.x.

## Kurulum

```xml
<dependency>
    <groupId>io.github.hozdemir.smstr</groupId>
    <artifactId>sms-tr-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

Gradle: `implementation("io.github.hozdemir.smstr:sms-tr-spring-boot-starter:0.1.0")`

## Yapılandırma

Bir sağlayıcının kimlik bilgilerini girdiğinizde o sağlayıcı etkinleşir. Tek sağlayıcı varsa `default-provider` gerekmez.

```yaml
sms:
  default-provider: netgsm
  fallback: [verimor]            # netgsm başarısız olursa sırayla denenir

  netgsm:
    username: 8501234567         # abone numarası
    password: ${NETGSM_PASSWORD} # "API Kullanıcısı" alt kullanıcısının şifresi
    sender: MARKAM
    otp-endpoint: false          # true: OTP'ler Netgsm'in öncelikli OTP hattından gider (OTP paketi gerekir)

  verimor:
    username: 908501234567
    password: ${VERIMOR_PASSWORD}
    sender: MARKAM               # boş bırakılırsa hesaptaki ilk başlık kullanılır

  iletimerkezi:
    api-key: ${ILETIMERKEZI_KEY}
    api-hash: ${ILETIMERKEZI_HASH} # panelde key'in yanında gösterilen hash
    sender: MARKAM

  mutlucell:
    username: kullanici
    password: ${MUTLUCELL_PASSWORD}
    sender: MARKAM
    brand-code: "123456"         # yalnızca ticari ileti için (İYS marka kodu)

  retry:
    max-attempts: 3              # sağlayıcı başına toplam deneme
    initial-delay: 500ms
    multiplier: 2.0
    max-delay: 5s
    retry-ambiguous: false
  timeout:
    connect: 3s
    read: 10s
```

Eksik ya da çelişkili ayar varsa uygulama açılırken durur ve eksik property'nin adını söyler. Örnek mesajlar: `sms.netgsm.password must be set` ve `choose one with sms.default-provider`.

## Kullanım

```java
@Service
class SiparisBildirimi {

    private final SmsClient sms;

    SiparisBildirimi(SmsClient sms) {
        this.sms = sms;
    }

    void kargoyaVerildi(String telefon) {
        sms.send(telefon, "Siparişiniz kargoya verildi.");
    }

    void kampanya(List<PhoneNumber> aliciler) {
        SendResult result = sms.send(SmsMessage.builder()
                .to(aliciler)
                .text("Hafta sonuna özel %20 indirim")
                .type(MessageType.COMMERCIAL_INDIVIDUAL)
                .scheduledAt(Instant.parse("2026-11-01T07:00:00Z"))
                .build());
    }
}
```

Numaralar `+90 532 123 45 67`, `0532 123 4567`, `905321234567` ya da `5321234567` biçiminde verilebilir. Her sağlayıcıya kendi beklediği formatta gönderilir.

Belirli bir sağlayıcıyı zorlamak için `sms.using("verimor").send(...)` kullanın. Bu çağrı retry yapar, fallback yapmaz.

### Hatalar, retry ve fallback

Her hata bir `SmsProviderException` olarak gelir. Exception'ın `kind()` değeri retry ve fallback davranışını belirler. `providerCode()` sağlayıcının kendi hata kodunu verir.

| `FailureKind` | Örnek | Aynı sağlayıcıda tekrar | Sonraki sağlayıcıya geç |
|---|---|---|---|
| `TRANSIENT` | Bağlantı kurulamadı, 502/503, sağlayıcı sistem hatası | evet | evet |
| `RATE_LIMITED` | HTTP 429, Netgsm `80`/`85` | evet | evet |
| `AUTHENTICATION` | Yanlış şifre, izin verilmeyen IP | hayır | evet |
| `INSUFFICIENT_CREDIT` | Kredi yetersiz | hayır | evet |
| `SENDER_NOT_ALLOWED` | Başlık tanımsız, İYS yetkisi yok, OTP paketi yok | hayır | evet |
| `INVALID_REQUEST` | Geçersiz numara veya metin | hayır | hayır |
| `AMBIGUOUS` | Read timeout, 500/504. Mesaj gitmiş olabilir | hayır* | hayır* |
| `UNKNOWN` | Tanınmayan hata kodu, mesaj kabul edilmedi | hayır | evet |

\* `sms.retry.retry-ambiguous=true` ile açılır. Bu durumda çift gönderim olasılığını kabul etmiş olursunuz.

### OTP

```java
OtpSendResult sent = otpService.send(PhoneNumber.of(telefon), "login");
if (sent instanceof OtpSendResult.Throttled t) {
    // t.retryAfter() kadar beklenmeli
}

OtpVerification result = otpService.verify(PhoneNumber.of(telefon), "login", girilenKod);
if (result.isVerified()) { ... }   // aksi halde INVALID_CODE, EXPIRED, TOO_MANY_ATTEMPTS, NOT_FOUND
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

Varsayılan `InMemoryOtpStore` tek instance için uygundur. Birden fazla instance çalışıyorsa paylaşılan bir `OtpStore` bean'i tanımlayın, örneğin Redis üzerinde. Atomik olması gereken tek işlem `incrementAttempts` metodudur.

### Teslimat raporu

Raporu sorgulamak (pull) için:

```java
List<DeliveryReport> reports = sms.deliveryReports(result.messageId());
```

`MessageId` gönderen sağlayıcının bilgisini taşır. Bu yüzden mesaj fallback ile başka bir sağlayıcıdan gittiyse bile sorgu doğru sağlayıcıya yapılır.

Push ile almak için önce webhook'u açın:

```yaml
sms:
  webhook:
    enabled: true
    token: ${SMS_WEBHOOK_TOKEN}   # en az 16 karakter
```

Ardından sağlayıcı panelinde callback adresi olarak `https://uygulamaniz/sms/webhooks/{provider}/{token}` adresini girin. Gelen raporları dinlemek için:

```java
@EventListener
void on(SmsEvent.DeliveryReportReceived event) { ... }
```

`SmsEvent.Sent` ve `SmsEvent.Failed` olayları da yayınlanır.

### Sağlayıcı farkları

| | Netgsm | İleti Merkezi | Verimor | Mutlucell |
|---|---|---|---|---|
| Pull rapor | evet. Her job id dakikada bir sorgulanabilir | evet | evet. Dakikada 20 istek | evet. **15 dakikadan sık sorgulamak hesabı bloke ettirir** |
| Push rapor (webhook) | yok | evet. Panelden tek URL | evet. OİM'den tek URL | yok |
| Ayrı OTP hattı | evet. `otp-endpoint: true` ile açılır | yok | yok | yok |
| Türkçe karakter | otomatik | panel ayarı | otomatik | otomatik |
| Kredi yetersizliği | raporda görünür, gönderimde hata vermez | gönderimde hata | gönderimde hata | gönderimde hata |

Mutlucell'in 15 dakika sınırını starter da uygular. Aynı paket daha erken tekrar sorgulanırsa istek Mutlucell'e gitmez, `RATE_LIMITED` hatası döner. Verimor gönderimleri için sunucu IP'nizin OİM'de izinli olması gerekir. Her API sözleşmesinin kaynaklı ayrıntıları [docs/provider-apis.md](docs/provider-apis.md) dosyasındadır.

## Yeni sağlayıcı eklemek

`SmsProvider` arayüzünü implemente edip bean olarak kaydedin. Routing, retry, fallback ve OTP başka bir ayar gerektirmeden çalışır.

```java
@Component
class AcmeSmsProvider implements SmsProvider, DeliveryReportQuery {

    @Override
    public ProviderId id() {
        return ProviderId.of("acme");
    }

    @Override
    public SendResult send(SmsMessage message) {
        // Tek deneme yapın. Retry'ı starter yönetir.
        // Hataları doğru FailureKind ile SmsProviderException olarak fırlatın.
    }

    @Override
    public List<DeliveryReport> fetchReports(MessageId messageId) { ... }
}
```

Sağlayıcıyı `sms.default-provider=acme` ile varsayılan yapabilir ya da `sms.fallback` listesine ekleyebilirsiniz. Push rapor destekliyorsa `DeliveryReportWebhook` arayüzünü de implemente edin. HTTP kodu yazarken `ProviderHttp.exchange` ve `ProviderHttp.statusFailure` metotları ağ hatalarını sınıflandırır.

Starter'ın tanımladığı her bean (`SmsClient`, `OtpStore`, `ResiliencePolicy`, yerleşik sağlayıcılar) aynı tipte kendi bean'inizi tanımladığınızda geri çekilir.

## Modüller

- `sms-tr-core` Spring Boot'a bağlı değildir. Domain modeli, istemci, OTP ve sağlayıcılar buradadır.
- `sms-tr-spring-boot-autoconfigure` auto-configuration ve properties'i içerir.
- `sms-tr-spring-boot-starter` uygulamaya eklenecek tek bağımlılıktır.

## Geliştirme ve yayın

```bash
./mvnw verify
```

Maven Central'a yayın için şunlar gerekir:

1. Central Portal'da `io.github.hozdemir` namespace'i doğrulanmış olmalı.
2. Bir GPG anahtarı olmalı.
3. GitHub secret'ları tanımlanmalı: `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`.

Bunlar hazırsa `v0.1.0` gibi bir tag push'lamak `.github/workflows/release.yml` workflow'unu çalıştırır. İlk sürümler Portal'a yüklenir ve Portal'da "Publish" ile elle yayınlanır (`autoPublish=false`).

## Lisans

Apache License 2.0
