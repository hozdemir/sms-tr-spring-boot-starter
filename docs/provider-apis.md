# Turkish SMS provider HTTP API contracts

Research date: 2026-10-23. Every fact below cites the official source it came from. Items marked **UNVERIFIED** could not be confirmed from an official source. Items marked **CONFLICT** are cases where two official sources disagree.

Sources were read as raw text (HTML, Markdown, JS bundles, Git repos), not through summaries.

Contents:

1. Netgsm
2. İleti Merkezi
3. Verimor
4. Mutlucell
5. Cross-provider comparison (abstraction impact)

---

## 1. Netgsm

**Primary source.** https://www.netgsm.com.tr/dokuman/ . The page is a React app. Its full Markdown content ships inside https://www.netgsm.com.tr/dokuman/bundle.js , and that bundle is what was read. Section names below are the doc's own headings ("SMS Gönderimi", "SMS Sorgulama > JSON POST Rapor", "OTP SMS"). Netgsm lists its official SDKs in the same doc: https://pypi.org/project/netgsm-sms/ and https://www.npmjs.com/package/@netgsm/sms .

### 1.1 Auth (all REST v2 endpoints)

- HTTP Basic auth. Username is the subscriber number (abone numarası, e.g. `850xxxxxxx`, `312XXXXXXX`). Password is the password of a sub-user of type "API Kullanıcısı". Source: dokuman, "SMS Gönderimi > Authentication" and the "Tanımlar" table.
- Since 2024-02-15 a sub-user with API rights is mandatory; the main account password no longer works. Source: dokuman, "Duyurular 22.01.2024" and "01.11.2023".
- HTTPS is mandatory on `api.netgsm.com.tr` since version 2.0 (2023-03-13). HTTP GET is not supported for sending since 1.8 (2022-04-19). Source: dokuman, "Sürüm Notları".
- Optional IP allowlist. A request from a non-allowed IP returns error `30`. Source: dokuman, send error table.

### 1.2 Send SMS

`POST https://api.netgsm.com.tr/sms/rest/v2/send` with `Content-Type: application/json`. Source: dokuman, "SMS Gönderimi".

Request (verbatim sample from the doc):

```json
{
    "msgheader": "BASLIK",
    "messages": [
        { "msg": "test1", "no": "510xxxxxxx" },
        { "msg": "test2", "no": "510xxxxxxx" }
    ],
    "encoding": "TR",
    "iysfilter": "0",
    "appname": "uygulama adiniz"
}
```

Scheduled variant adds `"startdate": "110420260100"` and `"stopdate": "110420262200"`.

| Field | Required | Meaning |
|---|---|---|
| `msgheader` | yes | Sender header (başlık), 3 to 11 chars, must be defined on the account. |
| `messages[]` | yes | Array of `{ "msg", "no" }`. Each element carries its own text and number, so one request covers 1:n and n:n. |
| `messages[].no` | yes | Format `510xxxxxxx` (10 digits, no leading `0` or `90`). International: prefix `00`, e.g. `00421xxxxxxx`. |
| `messages[].msg` | yes | Text. Standard max 912 chars. Newline is the literal `\n` inside the JSON string. |
| `encoding` | no | `"UTF-8"` = no Turkish chars (default, max 912), `"TR"` = Turkish chars (max 883), `"UNICODE"` = emoji (max 391). |
| `startdate` | no | Scheduled start, format `ddMMyyyyHHmm`. Empty = send now. |
| `stopdate` | no | End of send window, `ddMMyyyyHHmm`. Empty = start + 21 hours. |
| `iysfilter` | no | `"0"` = informational, no İYS check (default when omitted). `"11"` = commercial to individuals (bireysel), İYS checked. `"12"` = commercial to merchants (tacir), İYS checked. |
| `partnercode` | no | Reseller (bayi) code. |
| `appname` | no | Integrating application name. |
| `referansID` | no | Client-generated reference for later tracking. Shows up in report rows as `referansID`. |

Duplicate filter: the same text to the same number within one hour is blocked and reported as "Mükerrer" (status `13`). Support can disable it. Source: dokuman, "SMS Gönderimi".

Max recipients per request: **UNVERIFIED** (not stated in the doc).

### 1.3 Send response

Success (HTTP 200):

```json
{ "code": "00", "jobid": "17377215342605050417149344", "description": "queued" }
```

Error:

```json
{ "code": "XX", "jobid": null, "description": "error message desc." }
```

- One `jobid` per request (batch level), not per recipient. Since 2025-03-11 it is a long string (e.g. 26 digits); it used to be 10 digits. Treat it as an opaque string. Source: dokuman, "Duyurular 11.03.2025".
- HTTP status codes listed: `200` (processed), `406` (request not acceptable). Source: dokuman, "HTTP Status Kodları".

`code` values:

| Code | Meaning | Class |
|---|---|---|
| `00` | Accepted. | success |
| `01` | Accepted, but `startdate` was invalid and was replaced with system time. | success (warning) |
| `02` | Accepted, but `stopdate` was invalid and was replaced (start + 24h if stop < start). | success (warning) |
| `20` | Message text problem, or over the max char count. | permanent |
| `30` | Invalid username/password, no API permission, or IP not allowed. | permanent (auth) |
| `40` | Sender header (`msgheader`) not defined on the account. | permanent (header) |
| `50` | Account cannot send İYS-checked messages. | permanent (config) |
| `51` | No İYS brand info defined on the subscription. | permanent (config) |
| `70` | Invalid request; a parameter is wrong or a required one is missing. | permanent |
| `80` | Send limit exceeded ("Gönderim sınır aşımı"). | transient (rate limit) |
| `85` | Duplicate-send limit: more than 20 jobs to the same number within 1 minute. | transient (rate limit) |

Source: dokuman, "SMS Gönderimi > Hata Durumları".

Notes:

- Insufficient credit is **not** a synchronous send error in this table. It appears per number in the delivery report as status `14`. Source: dokuman, "JSON POST Rapor > status".
- A system-error code (`100`) is documented for the OTP and report endpoints but not for `/send`. Third-party clients treat `100` on send as retryable. **UNVERIFIED** for `/send`.

### 1.4 Delivery report (pull)

`POST https://api.netgsm.com.tr/sms/rest/v2/report`, Basic auth, `Content-Type: application/json` is mandatory (since 2024-08-06). Source: dokuman, "SMS Sorgulama > JSON POST Rapor" and "Duyurular 06.08.2024".

Requests:

```json
{ "jobids": ["17377215342605050417149344", "17377215342605050417149345"] }
```

```json
{ "jobids": ["..."], "startdate": "07.04.2026 00:00:00", "stopdate": "08.04.2026 23:59:59" }
```

```json
{ "startdate": "07.04.2026 00:00:00", "stopdate": "08.04.2026 23:59:59" }
```

Fields: `jobids` (max 50 per request), `startdate`/`stopdate` (format `dd.MM.yyyy HH:mm:ss`, must be used together, range max 3 months), `appname`, `pagenumber` (default 1), `pagesize`.

Limits: each jobid can be queried once per minute; max 60 requests per minute; reports kept for 3 months. Exceeding returns `"query limit: 1 jobid and 60 total per minute"`. The error table separately says code `80` = "max 10 queries per minute". These two limits disagree (**CONFLICT** inside the same doc); design for the stricter one.

Success response:

```json
{
    "code": "00",
    "jobs": [
        {
            "jobid": "17377215342605050417149344",
            "number": "510xxxxxxx",
            "status": 1,
            "operator": 40,
            "msglen": 1,
            "deliveredDate": "2026-04-07 08:55:47",
            "errorCode": 0,
            "referansID": ""
        }
    ],
    "description": "success"
}
```

One row per recipient, keyed by `jobid` + `number`. There is no per-message id.

`status`:

| Code | Meaning |
|---|---|
| `0` | Waiting to be delivered (pending) |
| `1` | Delivered |
| `2` | Timed out |
| `3` | Invalid or restricted number |
| `4` | Could not be sent to operator |
| `11` | Not accepted by operator |
| `12` | Send error |
| `13` | Duplicate (mükerrer) |
| `14` | Insufficient credit / package |
| `15` | Number on blacklist |
| `16` | İYS rejection |
| `17` | İYS error |
| `22` | No permission for international sending |

`operator`: `10` Vodafone, `20` Türk Telekom, `30` Turkcell, `40` Netgsm STH, `41` Netgsm Mobil, `60` Türk Telekom Sabit, `70` undefined, `160` KKTC Vodafone, `212`-`215` international, `880` KKTC Turkcell.

`errorCode` (operator-level detail): `0` none, `101` inbox full, `102` off or out of coverage, `103` busy, `104` line inactive, `105` wrong number, `106` SMS rejected / blacklist, `111` timeout, `112` device blocks SMS, `113` device unsupported, `114` routing failed, `115` destination operator timeout, `116` unidentified subscriber, `117` illegal subscriber, `119` system error.

Report request errors: `30` auth (permanent), `60` no records found, `70` invalid query or range over 3 months (permanent), `80` rate limit (transient), `100`-`110` system error (transient).

Source for all of 1.4: dokuman, "SMS Sorgulama > JSON POST Rapor".

### 1.5 Delivery report push (webhook)

**Not documented for SMS.** The doc documents push only for inbound SMS ("Gelen SMS > URL'e Yönlendirme"), WhatsApp, voice messages and İYS consent changes. There is no SMS delivery-report webhook in the official doc. A Netgsm integration must poll `/sms/rest/v2/report`.

### 1.6 OTP

`POST https://api.netgsm.com.tr/sms/rest/v2/otp`, Basic auth. Source: dokuman, "OTP SMS".

```json
{ "msgheader": "BASLIK", "msg": "code: 1234", "no": "510XXXXXXX" }
```

Success: `{ "jobid": "17377215342605050417149344", "code": "00", "description": "success" }`. Error: `{ "code": "XX", "description": "..." }`.

Restrictions (from the doc's OTP-vs-normal table):

- Requires an OTP package on the account (else error `60`).
- Single recipient only; bulk is not allowed.
- No scheduling.
- No Turkish characters.
- Single segment: max 155 chars with an alphanumeric header (error `20` otherwise).
- Domestic mobile only; no international, no landline. Delivered within 3 minutes on a priority route.
- Unfiltered: no İYS check, no blacklist, no duplicate filter. So no İYS field exists on this endpoint.

Errors: `20` text/length (permanent), `30` auth (permanent), `40`-`41` sender header (permanent), `50`-`52` number (permanent), `60` no OTP package (permanent), `70` bad input (permanent), `100` system error (transient).

### 1.7 İYS for transactional messages

On `/send`, transactional or OTP-like content should send `"iysfilter": "0"` (also the default when the field is omitted). The `/otp` endpoint has no İYS field. Source: dokuman, "SMS Gönderimi > Tanımlar".

---

## 2. İleti Merkezi

**Primary sources.**

- New docs (last_updated 2026-08-05): https://www.iletimerkezi.com/en/docs/api/send-sms , `/authentication`, `/error-codes`, `/get-report`, `/webhooks`, `/overview`. Each page has a raw Markdown twin at the same URL with `.md`, which is what was read.
- Machine-readable manifest built from the same docs: https://www.iletimerkezi.com/api/manifest.json (generated 2026-09-26).
- Legacy docs, same content in Turkish: https://www.toplusmsapi.com/sms/gonder/json/ , https://www.toplusmsapi.com/sms/rapor/liste/json/ , https://www.toplusmsapi.com/sms/rapor/liste/webhook/ , https://www.toplusmsapi.com/durum-kodlari-ve-mesajlar/ (source repo: https://github.com/iletimerkezi/apidocs-website ).
- Official SDK cross-check: https://github.com/iletimerkezi/iletimerkezi-php (`src/Services/SmsService.php`, base URL `https://api.iletimerkezi.com/v1/` in `src/Http/CurlHttpClient.php`).

### 2.1 Auth

- Credentials go in the JSON body at `request.authentication.{key, hash}`, not in a header.
- **The hash is issued by the panel.** You copy both API Key and API Hash from `panel.iletimerkezi.com > Settings > Security > API Access`. The docs say: "Do not generate the hash yourself... There is no MD5 / SHA / HMAC step in your code." Source: /en/docs/api/authentication. The official PHP SDK takes `$apiHash` as a constructor argument and sends it unchanged.
- Prerequisite: the "Allow API access" toggle (Settings > Security > Access Permissions) must be on, else every call returns `401`. Optional static IP allowlist also yields `401`. Source: /en/docs/api/authentication.
- There is no sandbox key. Sender `APITEST` works before a real header is approved; it forces `iys="0"` and replaces the text with a fixed verification string. Source: /en/docs/api/send-sms.

### 2.2 Send SMS

`POST https://api.iletimerkezi.com/v1/send-sms/json`, `Content-Type: application/json`. Source: /en/docs/api/send-sms.

```json
{
  "request": {
    "authentication": { "key": "API_KEY", "hash": "API_HASH" },
    "order": {
      "sender": "YOURBRAND",
      "sendDateTime": "23/04/2022 09:00",
      "iys": "0",
      "iysList": "BIREYSEL",
      "message": {
        "text": "Order #1234 prepared and shipped.",
        "receipents": { "number": ["905XXXXXXXXX", "905YYYYYYYYY"] }
      }
    }
  }
}
```

| Field | Required | Meaning |
|---|---|---|
| `order.sender` | yes | Panel-approved header, max 11 alphanumeric chars, no Turkish chars (error `460`). |
| `order.sendDateTime` | no | Scheduled time, `dd/MM/yyyy HH:mm`, Turkey local time. Seconds are not allowed: per the manifest, sending seconds makes the backend ignore the field and send immediately. The legacy doc sample shows it as an array (`["23/04/2022 09:00"]`) and as `[]` when empty; the new doc schema says string. Both appear in official samples (**CONFLICT**, prefer string). |
| `order.iys` | yes | `"1"` = commercial, İYS-checked. `"0"` = not checked (transactional). No default; omitting it yields `468`. |
| `order.iysList` | if `iys="1"` | `"BIREYSEL"` or `"TACIR"`. Ignored when `iys="0"`. |
| `order.message` | yes | Object, or an **array** of objects for different texts to different numbers in one order. |
| `order.message.text` | yes | SMS body. |
| `order.message.receipents.number[]` | yes | Spelled `receipents` (sic). `recipients` silently yields an empty list and `452`. Accepted formats: `905XXXXXXXXX`, `+905XXXXXXXXX`, `5XXXXXXXXX`. |

Multiple recipients: add numbers to the `number` array (same text), or send `message` as an array of `{text, receipents}` (different texts). One order and one sender per request. Bulk limit: 50,000 recipients per request. Transactional rate limit: 100 SMS per second per account, counted per recipient. Source: /en/docs/api/send-sms "Rate limits".

**Turkish character encoding has no request flag.** The character mode is an account-level panel setting (`panel.iletimerkezi.com/settings/sms/encoding`). Default is GSM-7; without enabling Turkish or Unicode mode, Turkish chars are transliterated (`ş` to `s`) and emoji are stripped. With it enabled, the mode is picked per message from its content. B186 is appended to every SMS, so limits are 155/153 (GSM-7), 150/148 (Turkish), 65/63 (Unicode), max 7 parts. Source: /en/docs/api/overview.

International numbers need "abroad mode" enabled in the panel. Source: /en/docs/api/send-sms.

### 2.3 Send response

The `response.status.code` mirrors the HTTP status. Source: legacy send doc ("HTTP yanıtının başlık bilgisine bakılarak da elde edilebilir") and /en/docs/api/send-sms.

Success:

```json
{ "response": { "status": { "code": 200, "message": "İşlem başarılı" }, "order": { "id": "312891245" } } }
```

Error (no `order`):

```json
{ "response": { "status": { "code": 401, "message": "Üyelik bilgileri hatalı" } } }
```

`order.id` is one id per request (batch). Messages are always Turkish; branch on the numeric code. Source: /en/docs/api/error-codes.

Codes relevant to send (full table at /en/docs/api/error-codes):

| Code | Meaning | Class |
|---|---|---|
| `200` | Success | success |
| `400` | Request could not be parsed (bad JSON) | permanent |
| `401` | Auth failed (bad key/hash, API toggle off, IP not allowed) | permanent (auth) |
| `402` | Insufficient balance | permanent (credit) |
| `404` | Method not found (wrong URL) | permanent |
| `422` | Validation failed | permanent |
| `450` | Sender not approved / not usable | permanent (header) |
| `451` | Duplicate order: same text to same recipients within 10 minutes | permanent for that payload (do not retry unchanged) |
| `452` | Invalid or missing recipients, or recipient on your blacklist | permanent (number) |
| `453` | Order too large (text over 7 parts) | permanent |
| `454` | Empty message text | permanent |
| `457` | Invalid `sendDateTime` format | permanent |
| `459` | Sender empty | permanent |
| `460` | Sender over 11 chars or has Turkish chars | permanent |
| `466` | Invalid number (bad prefix or digit count) | permanent (number) |
| `467` | Required document missing | permanent (config) |
| `468` | `iys` flag missing | permanent |
| `469` | `iysList` missing while `iys="1"` | permanent |
| `470` | İYS code missing | permanent (config) |
| `503` | Service temporarily unavailable | transient |

Rate limiting: the docs say "abnormal traffic may be temporarily throttled" and recommend exponential backoff, but name no status code for it (**UNVERIFIED**: whether a 429 exists).

### 2.4 Delivery report (pull)

`POST https://api.iletimerkezi.com/v1/get-report/json`. Source: /en/docs/api/get-report and legacy /sms/rapor/liste/json.

```json
{ "request": { "authentication": { "key": "...", "hash": "..." }, "order": { "id": 428, "page": 1, "rowCount": 5 } } }
```

`page` default 1. `rowCount` default and max 1000; page through larger orders.

Success:

```json
{
  "response": {
    "status": { "code": 200, "message": "İşlem başarılı" },
    "order": {
      "id": "312891245", "status": 114,
      "total": 1, "delivered": 1, "undelivered": 0, "waiting": 0,
      "submitAt": "2026-04-29 04:32:24", "sendAt": "2026-04-29 04:32:24",
      "sender": "eMarka Test", "price": 1,
      "message": [ { "number": "+905333128320", "status": 111 } ]
    }
  }
}
```

Per-recipient rows carry only `number` and `status`; there is **no per-message id** in the pull report.

`order.status`: `113` sending in progress, `114` sending finished (not "all delivered"; read the counters), `115` order failed.

`message[].status`: `110` sending (in carrier queue), `111` delivered, `112` not delivered.

Report errors: `400`, `401`, `404` (permanent); `455` order not found (permanent); `456` scheduled time not reached yet (transient, retry after `sendDateTime`).

There is also `POST /v1/get-reports/json` with `filter: {start, end, page}` (dates `Y-m-d`, max 10-day range, error `458`), which returns per-order aggregates only. Source: manifest.json, `get-reports` entry.

### 2.5 Delivery report push (webhook)

Supported. Source: /en/docs/api/webhooks and legacy /sms/rapor/liste/webhook.

- Configured in the panel only: Settings > API > Webhook (`panel.iletimerkezi.com/settings/api/webhooks`). One URL per account. HTTPS only. Not configurable per request.
- `POST` with `Content-Type: application/json`, one event per request:

```json
{ "report": { "id": 1599558518, "packet_id": 104525848, "status": "accepted", "to": "+905XXXXXXXXX", "body": "Message text" } }
```

- `report.id` = per-message id. `report.packet_id` = the `order.id` returned by send. `report.to` has a `+90` prefix.
- `status` is a string: `accepted` (queued), `delivered`, `undelivered`. Typically `accepted` first, then a final state.
- No signature or HMAC header. The docs recommend a secret token in the URL query string.
- Duplicates are possible; the idempotency key is `report.id + report.status`. No formal redelivery guarantee; the docs recommend reconciling with `get-report`.
- Reply `2xx` quickly.

### 2.6 OTP

No dedicated OTP endpoint. OTP goes through `send-sms` with `iys: "0"`. The manifest and docs list 11 endpoints and none is OTP-specific. Source: manifest.json `endpoints`, /en/docs/api/send-sms.

### 2.7 İYS for transactional messages

Send `"iys": "0"`. The field is mandatory and has no default. `iysList` is ignored when `iys="0"`. The docs classify OTP, login codes, password resets, order/delivery/payment notifications, appointment reminders and regulatory notices as transactional. Source: /en/docs/api/send-sms.

---

## 3. Verimor

**Primary sources.**

- Classic user guide: https://github.com/verimor/SMS-API/blob/master/user_guide.md (last commit 2026-03-18).
- New docs site https://docs.verimor.com.tr (source https://github.com/verimor/docs , last commit 2026-08-17): pages `/sms/kimlik-dogrulama`, `/sms/gonderme`, `/sms/rapor`, `/sms/hata-kodlari`, `/sms/otp`, `/sms/rate-limiting`.
- OpenAPI spec behind https://developer.verimor.com.tr/smsapi : https://github.com/verimor/api-documentation/blob/master/smsapi_swagger.yaml (last commit 2026-08-11).

### 3.1 Auth

- `username` and `password` in the JSON body for POST endpoints; in the query string for GET endpoints. Source: docs.verimor.com.tr/sms/kimlik-dogrulama.
- `username` is the 12-digit account phone number, e.g. `908501234567` (user guide). The new docs show an email-style username in samples. Both are official samples.
- The API password is created in OİM > SMS Ayarları. The sending server's IP must be registered on the same page; otherwise `401 Hesabınızda izinli IP ayarları yapılmamış`. Source: user guide intro.

### 3.2 Send SMS

`POST https://sms.verimor.com.tr/v2/send.json`, `Content-Type: application/json`. Source: user guide "SMS GÖNDERİMİ (POST)", docs.verimor.com.tr/sms/gonderme.

```json
{
  "username": "908501234567",
  "password": "xxxxxxx",
  "source_addr": "BASLIGIM",
  "valid_for": "48:00",
  "send_at": "2015-02-20 16:06:00",
  "custom_id": "123456789",
  "datacoding": "0",
  "is_commercial": false,
  "messages": [
    { "msg": "deneme123", "dest": "905311234567,905319876543", "id": "1234,1235" },
    { "msg": "test123", "dest": "905317654321", "id": "1236" }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `username`, `password` | yes | Credentials. |
| `source_addr` | no | Sender header. If empty, the first header on the account is used. Numeric headers are rejected over the API (`NUMERIC_SOURCE_ADDRESS_NOT_ALLOWED`). |
| `messages[]` | yes | Max 50,000 messages per request. Request body max 10 MB (`413`). |
| `messages[].dest` | yes | **Comma-separated string** of numbers, not an array. Format `905XXXXXXXXX` (12 digits; `INVALID_DESTINATION_ADDRESS` says "905121234567 gibi olmalı"). International: `00` or `+` prefix. |
| `messages[].msg` | yes | Text, UTF-8. Max 1071 chars (GSM) or 1043 (Turkish). Newline as `\n`. |
| `messages[].id` | no | Your per-message ids, comma-separated, one per number in `dest`; ignored if the counts differ. Comes back as `message_custom_id`. |
| `messages[].iys_recipient_type` | no | Per-message override (new docs only). |
| `send_at` | no | Scheduled time, `2015-02-20 16:06:00` or any ISO 8601. Empty = now. |
| `valid_for` | no | Validity `HH:MM`, default `24:00`, min `00:01`, max `48:00`. |
| `datacoding` | no | `0` GSM basic, `1` GSM Turkish (needed only for `Ş ş Ğ ğ ç ı İ`), `2` Unicode/UCS-2. If empty, the server picks `1` when Turkish letters are present, else `0`. Do not send `1` for international. |
| `is_commercial` | no | `true` for commercial (ticari) messages. |
| `iys_recipient_type` | if commercial | `BIREYSEL` or `TACIR`. |
| `custom_id` | no | Your campaign-level id; usable in status queries and pushed back as `campaign_custom_id`. |
| `add_ret` | no | New docs only: appends the header's opt-out text (`Ret: ...`) to each message. |

A GET variant exists (`GET https://sms.verimor.com.tr/v2/send?username=&password=&source_addr=&msg=&dest=&datacoding=&valid_for=&send_at=&is_commercial=&iys_recipient_type=`): one text, many comma-separated numbers, URL max 8,177 chars (`414`). Source: docs.verimor.com.tr/sms/gonderme.

Rate limit: `/v2/send` (GET + POST) shares a pool with `/v2/iys_consents.json`: 240 requests per minute per account/IP, burst 80; over the limit returns `429`. Source: docs.verimor.com.tr/sms/rate-limiting.

### 3.3 Send response

**CONFLICT on the success body.**

- User guide and OpenAPI spec: `200 OK` with a **plain-text body** holding only the campaign id, e.g. `20212`. Error: `400 Bad Request` with a plain-text body naming the error. The OpenAPI `/v2/send.json` response is `text/plain`, schema "Kampanya ID'si". Sources: user_guide.md, smsapi_swagger.yaml `/v2/send.json`.
- New docs site: shows a JSON body `{"campaign_id": 98765432, "custom_id": null, "status": "0"}`. Source: docs.verimor.com.tr/sms/gonderme "Yanıt".

Two of three official sources say plain text, and the spec is the most formal. A client should accept both: if the body parses as JSON, read `campaign_id`; otherwise parse the trimmed body as the id.

The campaign id is one id per request (batch). Per-message ids (`message_id`) only appear in status reports and pushes.

**Second conflict, on the error body text.** The user guide shows the code token as the body (`INSUFFICIENT_CREDITS`). The OpenAPI examples show Turkish phrases keyed by code (e.g. `MISSING_MESSAGE: "Mesaj eksik"`). Map on the code token, and fall back to HTTP status when the body is not a known token.

HTTP status codes (docs.verimor.com.tr/sms/hata-kodlari):

| HTTP | Meaning | Class |
|---|---|---|
| `200` | Success | success |
| `400` | Invalid parameter (body names the error code) | permanent, per table below |
| `401` | Auth failed, or no allowed IP configured | permanent (auth) |
| `403` | Unauthorized; OpenAPI: "İzinsiz DNS veya İzinsiz IP" | permanent (auth/IP) |
| `413` | Body over 10 MB | permanent |
| `422` | Unprocessable entity | permanent |
| `429` | Rate limit exceeded | transient |
| `500` | Server error | transient |

Send-time error codes (400 body). Union of user guide, new docs and OpenAPI:

| Code | Meaning | Class |
|---|---|---|
| `INVALID_SOURCE_ADDRESS` | Header not accepted | permanent (header) |
| `NUMERIC_SOURCE_ADDRESS_NOT_ALLOWED` | Non-alphanumeric header not allowed via API (BTK rule) | permanent (header) |
| `MISSING_MESSAGE` | No message text | permanent |
| `MESSAGE_TOO_LONG` | Message too long | permanent |
| `INVALID_PERIOD` / `INVALID_VALID_FOR` | `valid_for` outside 1 min to 48 h | permanent |
| `INVALID_DELIVERY_TIME` / `INVALID_SEND_AT` | `send_at` invalid or in the past | permanent |
| `INVALID_DATACODING` | Bad `datacoding` | permanent |
| `MISSING_DESTINATION_ADDRESS` | No recipient | permanent |
| `INVALID_DESTINATION_ADDRESS` / `INVALID_DESTINATION_ADDRESSES` | Bad number format | permanent (number) |
| `INVALID_UTF8` | Body not UTF-8 | permanent |
| `INVALID_JSON` | Bad JSON | permanent |
| `INVALID_SHORT_MESSAGE` | Invalid message (OpenAPI only) | permanent |
| `MUKERRER_RAPORLAMA` | Same SMS already sent within 24 hours | permanent for that payload |
| `INSUFFICIENT_CREDITS` | Insufficient balance | permanent (credit) |
| `FORBIDDEN_MESSAGE` | Banned word in message | permanent |
| `MESSAGE_COUNT_LIMIT_EXCEEDED` | Over 50,000 messages | permanent |
| `MISSING_IYS_BRAND_CODE` | Commercial send but header has no İYS brand code | permanent (config) |
| `AHS_AUTHORIZATION_ERROR` | İYS authorization error (grant Verimor AHS permission in İYS) | permanent (config) |
| `NO_AHS_BRAND_ERROR` | No İYS brand for the tax id | permanent (config) |
| `INVALID_IYS_RECIPIENT_TYPE` | Must be `BIREYSEL` or `TACIR` | permanent |
| `INVALID_IS_COMMERCIAL`, `INVALID_IYS_OPTION` | Bad İYS flags (OpenAPI only) | permanent |
| `COMMERCIAL_SENDING_ERROR_UNDER_150K` | Historic (2021) İYS rollout error | permanent |
| `INVALID_CONSENT_DATE`, `MISSING_CONSENT`, `MISSING_CONSENT_DATE`, `INVALID_RECIPIENT` | İYS consent-upload errors (`/v2/iys_consents.json`), listed in the same table | permanent |

### 3.4 Delivery report (pull)

`GET https://sms.verimor.com.tr/v2/status` with query `username`, `password`, and one of `id` (campaign id) or `custom_id`. Optional `dest` (comma-separated numbers) and `greater_than` (message_id cursor). Each call returns at most 100 messages; pass the last `message_id` as `greater_than` to page. Source: user guide "HTTP GET ile Gönderim Raporu Alımı", docs.verimor.com.tr/sms/rapor.

Rate limit: 20 requests per minute, burst 10, shared with balance, cancel, headers, blacklists, inbound and İYS campaign endpoints. The docs recommend push for volume. Source: docs.verimor.com.tr/sms/rate-limiting.

Success (`200`, JSON array, one element per message):

```json
[
  {
    "campaign_id": 20121,
    "campaign_custom_id": "123456789",
    "message_id": "13582302",
    "message_custom_id": "1234",
    "dest": "905319876543",
    "size": 1,
    "international_multiplier": 1,
    "credits": 1,
    "status": "DELIVERED",
    "gsm_error": "0",
    "sent_at": "2015-02-20 16:06:00",
    "done_at": "2015-02-20 16:06:00"
  }
]
```

Errors: `401` (plain text "Bu kampanya size ait değil", campaign belongs to someone else), `404` ("Bu idye sahip kampanya bulunamadı").

`status` values (user guide "Mesaj Durumu Alınırken..." and docs.verimor.com.tr/sms/rapor):

| Status | Meaning | Final? |
|---|---|---|
| `SENDING` | Being sent | no |
| `WAITING` | Sent, awaiting operator confirmation | no |
| `DELIVERED` | Delivered | yes, success |
| `SENT` | Delivered, but operator gives no DLR (some international routes) | yes, success (unconfirmed) |
| `NOT_DELIVERED` | Not delivered (usually inactive number) | yes, fail |
| `EXPIRED` | Not delivered within `valid_for` | yes, fail |
| `REJECTED` | Rejected by operator content check | yes, fail |
| `DOUBLE_SEND_ERROR` | Duplicate: same text, same day, same header, same number | yes, fail |
| `BLACKLISTED_DESTINATION_ADDRESS` | Recipient on your blacklist | yes, fail |
| `NOT_ALLOWED_BY_IYS` | No İYS consent | yes, fail |
| `INVALID_DESTINATION_ADDRESS` | Number not registered with any operator | yes, fail |
| `MISSING_TARIFF` | No tariff for the destination operator (international) | yes, fail |
| `ROUTE_NOT_AVAILABLE` | Account cannot send to this destination | yes, fail |
| `NETWORK_NOTCOVERED` | Account cannot send to this destination (international) | yes, fail |
| `SEND_ERROR` | Send error | yes, fail |
| `INTERNATIONAL_DENIED` | International sending disabled in OİM | yes, fail |

`gsm_error` (operator code when undelivered): `1` unknown subscriber, `6` absent (off / no signal), `11` teleservice not provisioned, `13` call barred (DND), `27` absent subscriber, `31` busy for MT SMS, `32` delivery failure, `34` system failure, `256` memory full, `257`/`258` equipment error, `500` provider general error, `502` no response (no DLR returned), `1030`, `1155`, `1157`, `1281`, `1536` operator general errors, `2048` timeout, `2049` IMSI blacklisted, `2050` destination blacklisted, `2051` text blacklisted, `2053` header needs extra approval, `4100` canceled within validity, `4101` validity expired, `4103` destination flooding, `4104` same text flooding. Source: user guide "Mesaj Hata Kodları (gsm_error)".

### 3.5 Delivery report push (webhook)

Supported. Source: user guide "PUSH ile Gönderim Raporu Alımı", docs.verimor.com.tr/sms/rapor.

- Configured in OİM only: SMS Ayarlarım > Rapor Push URL (https://oim.verimor.com.tr/sms_settings/edit). Account-wide, not per request.
- `POST`, `Content-Type: application/json`, body is a **JSON array** (may batch several messages). Each element has the same fields as the pull report plus `"type": "outbound"`.
- Expects `HTTP 200 OK`. Otherwise it retries 3 more times, 5 minutes apart, then gives up.
- No signature is documented. The same push URL setting area also handles inbound SMS pushes (`"type": "inbound"`); dispatch on `type`.

### 3.6 OTP

No dedicated OTP endpoint. OTP uses `/v2/send.json`. The docs recommend `valid_for: "00:05"` and `datacoding: 0`, use `messages[].id` for tracking, and batching OTPs into one POST per second at high volume. They state OTP goes over a high-priority route. How that route is selected is not documented (**UNVERIFIED**: whether it is account-level or content-based). Source: docs.verimor.com.tr/sms/otp.

### 3.7 İYS for transactional messages

Omit `is_commercial` or send `false`; do not send `iys_recipient_type`. The docs state OTP is outside İYS scope. Source: user guide send params, docs.verimor.com.tr/sms/otp.

---

## 4. Mutlucell

**Primary source.** The "Toplu SMS" XML API help (Help & Manual frameset) linked from https://www.mutlucell.com.tr/api/ : https://www.mutlucell.com.tr/toplusmsapi/ . Topic pages read: `hm_xml_kodunu_post_etme.htm`, `hm_xmlin_formati_nasil_olmalidir_.htm`, `hm_smspack_parametreleri.htm`, `hm_mesaj_elementi.htm`, `hm_metin_elementi.htm`, `hm_numbs_elementi.htm`, `hm_trke_karakterli_sms_gnderimi.htm`, `hm_hata_kodlari.htm`, `hm_paket_raporu.htm`, `hm_gnderim_raporlari.htm`, `hm_numaradan_rapor_sorgulama.htm`, `hm_ileri_tarihli_mesaji_iptal_etm.htm`, `hm_originatr_nedir.htm`, `hm_xml_olustururken_dikkat_edilme.htm` (all under `https://www.mutlucell.com.tr/toplusmsapi/`). The İYS consent API has its own docs at https://login.mutlucell.com/iysapi/docs.html (not needed for sending). There is no official SDK on GitHub. A community Laravel package (https://github.com/Ardakilic/laravel-mutlucell-sms) uses the same `smsgw-ws` endpoints, which corroborates them.

### 4.1 Auth

- Credentials are XML attributes: `ka` (username) and `pwd` (password, or the API key emailed to you if you created one). Source: `hm_smspack_parametreleri.htm`.

### 4.2 Send SMS

`POST https://smsgw.mutlucell.com/smsgw-ws/sndblkex`, `Content-Type: text/xml; charset=UTF-8`. Source: `hm_xml_kodunu_post_etme.htm`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<smspack ka="kullanici_adim" pwd="parolam" org="MUTLUCELL" charset="turkish" tarih="2011-08-27 18:30">
  <mesaj>
    <metin>Merhaba. Bu bir deneme mesajıdır.</metin>
    <nums>532 567 89 90, 05556667788, +905551112233, 905324441122</nums>
  </mesaj>
  <mesaj>
    <metin>Ikinci metin</metin>
    <nums>05333456789</nums>
  </mesaj>
</smspack>
```

`smspack` attributes (`*` = required). Source: `hm_smspack_parametreleri.htm`.

| Attribute | Meaning |
|---|---|
| `ka`* | Username. |
| `pwd`* | Password or API key. |
| `org`* | Originator (sender header), max 11 chars, no Turkish chars (`hm_originatr_nedir.htm`). The doc says it may be omitted if you have no originator. |
| `charset` | `default` (Turkish chars transliterated, 155 chars per SMS; used when omitted), `turkish` (keeps `Ş ş Ğ ğ ç ı İ`, 150 chars per SMS, these letters count double), `unicode` (65 chars per SMS). |
| `tarih` | Scheduled send, `yyyy-MM-dd HH:mm`. Omit for immediate. |
| `bitis` | Retry end time, `yyyy-MM-dd HH:mm`. Default retry window is 24 hours. |
| `IP` | Originating end-user system IP, for resellers serving many users. |
| `aboneid` | Subscriber id. |
| `bayiid` | Reseller application id. |
| `zamanayay` | Spread the package over N minutes (30 to 720). |
| `periyot` | Period in minutes (0 to 60) for spread sends. |
| `addLinkToEnd` | `true` appends an opt-out link (`SMSRET http://ret.im/...`, +29 chars). |
| `shortCodeKeyword` | Appends a short-code opt-out text. Needs activation, else error `20`. |
| `brandCode` | İYS brand code for **commercial** messages. `123456` checks BIREYSEL consents; `123456_TACIR` checks TACIR consents. |

Elements: `smspack` contains unlimited `mesaj` elements. Each `mesaj` has one `metin` (text, no length limit; split automatically) and one `nums` (comma-separated numbers). Source: `hm_mesaj_elementi.htm`, `hm_metin_elementi.htm`, `hm_numbs_elementi.htm`.

Phone format: "Numara formatı önemli değildir". `533 111 22 33`, `(90) 533 0001122`, `05420001100` and `905050101010` are all valid. Landlines and invalid numbers are filtered out server-side. Source: `hm_numbs_elementi.htm`.

XML body max 8 MB. XML special chars must be entity-escaped. Source: `hm_xml_olustururken_dikkat_edilme.htm`.

### 4.3 Send response

Plain-text body, not XML. Source: `hm_xml_kodunu_post_etme.htm`, `hm_hata_kodlari.htm`.

- Success: starts with `$`, e.g. `$34672#13.0`. `34672` is the package id (one per request, batch level). `13.0` after `#` is credits spent.
- Failure: a bare numeric code (no `$`).

| Code | Meaning | Class |
|---|---|---|
| `20` | Posted XML missing or malformed (also: short-code not activated) | permanent |
| `21` | You do not own the originator | permanent (header) |
| `22` | Insufficient credit (kontör) | permanent (credit) |
| `23` | Wrong username or password | permanent (auth) |
| `24` | Another operation of yours is active | transient |
| `25` | SMSC stopped; retry in 1 to 2 minutes | transient |
| `30` | Account not activated | permanent (config) |
| `34` | Account closed to API access | permanent (config) |

There is no synchronous invalid-number error; invalid numbers are silently dropped (`hm_numbs_elementi.htm`). HTTP status codes are not documented (**UNVERIFIED**; assume 200 with a code body).

Cancel a scheduled package: `POST https://smsgw.mutlucell.com/smsgw-ws/cnclblkex` with `<smsiptal ka="" pwd="" id="88512"/>`. Success is `$<refunded credits>`; errors are `20`, `23`, `27` (cancel failed). Source: `hm_ileri_tarihli_mesaji_iptal_etm.htm`.

### 4.4 Delivery report (pull)

**Package report:** `POST https://smsgw.mutlucell.com/smsgw-ws/gtblkrprtex`. Source: `hm_paket_raporu.htm`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<smsrapor ka="myname" pwd="mypwd" id="88512" />
```

Response: plain text, one line per recipient, `<number> <status>` separated by a space:

```
905334445566 0
905334445567 1
905334445568 3
```

No per-message id. Errors: `20` bad XML, `23` auth, `30` account not activated (all permanent).

**Polling restriction:** the first report is available 1 minute after sending. While messages are pending, poll **at most every 15 minutes**. More frequent polling is treated as an attack and **the account gets blocked**. Source: `hm_paket_raporu.htm`, `hm_gnderim_raporlari.htm`.

Status codes (same list on `hm_paket_raporu.htm` and `hm_numaradan_rapor_sorgulama.htm`):

| Code | Meaning | Final? |
|---|---|---|
| `0` | Not sent | yes, fail (**UNVERIFIED**: whether final) |
| `1` | Processing | no |
| `2` | Sent (to operator) | no |
| `3` | Successful (delivered) | yes, success |
| `4` | Waiting | no |
| `5` | Timed out | yes, fail |
| `6` | Failed | yes, fail |
| `7` | Rejected | yes, fail |
| `11` | Unknown | **UNVERIFIED** |
| `12` | No line (HAT YOK) | yes, fail |
| `13` | Erroneous | yes, fail |
| `15` | Unused number | yes, fail |
| `16` | Closed to SMS | yes, fail |
| `17` | Message memory full | yes, fail (**UNVERIFIED**: whether retried) |
| `18` | Roaming | **UNVERIFIED** |
| `19` | Teleservice closed | yes, fail |
| `20` | Number to be ported | **UNVERIFIED** |
| `21` | Blacklist | yes, fail |
| `22` | İYS rejection | yes, fail |

Finality was inferred from the label text; the doc does not classify these codes.

Other report endpoints:

- Summary per package over a date range (max 30 days): `POST .../smsgw-ws/gtsummaryex` with `<gonrapor ka="" pwd="" tarih="yyyy-MM-dd HH:mm" bitis="yyyy-MM-dd HH:mm"/>`. Returns tab-separated `paketid, tarih, durum, baslik, kredi, adet, basarili, basarisiz, zamanasimi, bekleyen, diger, iade`. Package `durum`: `2` waiting, `3` sent, `4` canceled. Source: `hm_gnderim_raporlari.htm`.
- Per number over a date range: `POST .../smsgw-ws/srchblkrprtapi` with `<gonrapor ka="" pwd="" gsmno="5339998877" tarih="" bitis=""/>`. Returns tab-separated `Tarih, Mesaj, Durum`. Source: `hm_numaradan_rapor_sorgulama.htm`.

### 4.5 Delivery report push (webhook)

**Not documented.** No push or callback mechanism appears in the SMS API help. Only polling is available.

### 4.6 OTP

**No dedicated OTP endpoint is documented.** OTP uses `sndblkex`.

### 4.7 İYS for transactional messages

Omit `brandCode`. Its presence is what triggers the İYS check (`hm_smspack_parametreleri.htm`, item 14). No explicit "transactional" flag exists.

---

## 5. Cross-provider comparison (abstraction impact)

| Concern | Netgsm | İleti Merkezi | Verimor | Mutlucell |
|---|---|---|---|---|
| Wire format | JSON | JSON (envelope `request`/`response`) | JSON in; **plain text** id out (see CONFLICT) | XML in; **plain text** out |
| Auth | HTTP Basic (subscriber no + API sub-user pwd) | Body `key` + panel-issued `hash` | Body `username`/`password`; IP allowlist mandatory | XML attrs `ka`/`pwd` |
| Send id granularity | 1 `jobid` per request | 1 `order.id` per request | 1 `campaign_id` per request; client can set `custom_id` + per-message `id` | 1 package id per request |
| Per-message id | none (row = jobid + number; optional client `referansID`) | only in webhook (`report.id`) | `message_id` in report + push | none |
| Multiple texts in one call | yes (`messages[]` of `{msg,no}`) | yes (`message` as array) | yes (`messages[]`, `dest` comma string) | yes (multiple `<mesaj>`) |
| Recipient format | `5XXXXXXXXX` | `905…`, `+905…`, `5…` | `905XXXXXXXXX` | any |
| Turkish chars | `encoding: "TR"` | **no flag**, account panel setting | `datacoding: 1` (auto if omitted) | `charset="turkish"` |
| Schedule | `startdate` `ddMMyyyyHHmm` | `sendDateTime` `dd/MM/yyyy HH:mm` | `send_at` `yyyy-MM-dd HH:mm:ss` / ISO 8601 | `tarih` `yyyy-MM-dd HH:mm` |
| Validity | `stopdate` | not exposed | `valid_for` `HH:MM` | `bitis` |
| Transactional İYS setting | `iysfilter: "0"` (default) | `iys: "0"` (**required**) | omit `is_commercial` | omit `brandCode` |
| Commercial İYS setting | `iysfilter` `11` / `12` | `iys:"1"` + `iysList` | `is_commercial:true` + `iys_recipient_type` | `brandCode="X"` / `"X_TACIR"` |
| Insufficient credit | async, report status `14` | sync `402` | sync `INSUFFICIENT_CREDITS` | sync `22` |
| Pull report | yes, per jobid (max 50 per call), 1/min per jobid | yes, per order, paged 1000 | yes, per campaign, paged 100, 20 req/min | yes, per package; **poll at most every 15 min or the account is blocked** |
| Push DLR | **no** | yes (panel URL, single JSON event, string status, no signature) | yes (OİM URL, JSON array, 3 retries) | **no** |
| Dedicated OTP | **yes** `/sms/rest/v2/otp` (1 recipient, no TR chars, 1 segment, OTP package) | no | no (normal send; claims priority route) | no |
| Rate limit signal | codes `80`/`85` | not specified (maybe `503`) | HTTP `429` | none; code `24`/`25` transient |
| Transient errors | `80`, `85`, `100`-`110` (report/OTP) | `503`, `456` (report) | `429`, `500` | `24`, `25` |

Points that should shape the common abstraction:

1. **Delivery reports must be pull-first.** Netgsm and Mutlucell have no SMS DLR webhook. Push should be an optional per-provider adapter, configured outside the API (panel/OİM) for İleti Merkezi and Verimor.
2. **Polling cadence is provider-specific and can be harmful.** Mutlucell blocks accounts that poll more often than every 15 minutes. Netgsm allows 1 query per jobid per minute. Verimor allows 20 status calls per minute across the account. A shared poller needs per-provider minimum intervals.
3. **The send result is a batch id everywhere.** Per-recipient tracking means (batch id, phone number) as the key. Only Verimor offers a provider message id in pull reports; İleti Merkezi offers one only via webhook.
4. **Phone number normalization differs.** Normalize to E.164 internally, then render per provider: Netgsm `5XXXXXXXXX`, Verimor `905XXXXXXXXX`, İleti Merkezi either, Mutlucell anything.
5. **Insufficient credit is asynchronous on Netgsm** (a per-number report status) and synchronous elsewhere. The error model needs both a send-time failure and a per-recipient delivery failure reason.
6. **İleti Merkezi cannot set the Turkish encoding per message.** The `encoding` option must be advisory or ignored for that provider (panel setting).
7. **İYS defaults diverge.** İleti Merkezi rejects a send without an explicit `iys`. A "message category" (transactional vs commercial with BIREYSEL/TACIR) in the common request maps cleanly to all four.
8. **OTP as a first-class operation** maps to a dedicated endpoint only on Netgsm. That endpoint enforces single recipient, no Turkish chars and a single segment, and needs an OTP package. Other providers fall back to normal send.
9. **Response parsing must tolerate plain text** (Mutlucell always; Verimor per user guide and OpenAPI spec, with a JSON variant shown on the new docs site).

---

## 6. VatanSMS

Added 2026-10-24.

**Primary sources.**

- Official API page: https://www.vatansms.net/sms-api (with per-language pages such as https://www.vatansms.net/sms-api/java-sms-gonderme-api). Read as raw HTML.
- Official SDKs linked from that page: https://github.com/vayztr/vatansmsnet-php , https://github.com/vayztr/vatansmsnet-dotnet , https://github.com/vayztr/vatansmsnet-delphi . The Packagist package `vatanyazilim/vatansmsnet-php` (https://github.com/vatanyazilim/vatansmsnet-php) has the same code, with default base URL `https://api.toplusmspaketleri.com/api/v1`.

The older XML endpoint `https://panel.vatansms.com/panel/smsgonder1Npost.php` (used by `fatihgirgic/laravel-sms-api`) is not mentioned on the official API page and is not used.

### 6.1 Auth

`api_id` and `api_key` in the JSON body of every request, taken from "Hesabım > API Bilgilerimi Görüntüle". Source: official API page.

A request with invalid credentials returns HTTP `401` with `{"message":"Unauthorized"}`. Source: observed on 2026-10-24 with a deliberately invalid key against `/api/v1/1toN` and `/api/v1/report/single`.

### 6.2 Send SMS

`POST https://api.vatansms.net/api/v1/1toN`, `Content-Type: application/json`. Source: official API page.

```json
{
  "api_id": "API_ID",
  "api_key": "API_KEY",
  "sender": "SMSBASLIGINIZ",
  "message_type": "normal",
  "message": "Bu bir test mesajıdır.",
  "message_content_type": "bilgi",
  "phones": ["5xxxxxxxxx", "5xxxxxxxxx"]
}
```

| Field | Meaning |
|---|---|
| `sender` | Header defined on the account. |
| `message_type` | `normal`, or `turkce` for Turkish characters. No Unicode value is documented (**UNVERIFIED**: emoji support). |
| `message_content_type` | `bilgi` (informational) or `ticari` (commercial). No BIREYSEL/TACIR distinction is documented (**UNVERIFIED**). |
| `phones` | Array of `5xxxxxxxxx` numbers. |
| `send_time` | Optional, `Y-m-d H:i:s`. Omit to send now. |

`POST /api/v1/NtoN` takes `phones` as `[{"phone","message"}]` for different texts.

### 6.3 Send response

Not documented by any official source. Each official SDK returns the raw body. The .NET SDK treats any non-2xx HTTP status as an error. The API page says `report_id` is "the `id` returned after each send", so the response has an `id`.

Third-party integrations check `status === "success"` and `status !== "error"` (e.g. https://github.com/mertcan-mutlu/gt_eticaret `hf-element/vatansms.php`, https://github.com/BYSiriusApps/SiriPlan-App `src/lib/sms.ts`). **UNVERIFIED**: the full success shape and every error code.

The implementation reads `status` and `id`, maps HTTP status through the shared table, and treats `status: "error"` as `UNKNOWN` (no retry, failover allowed).

### 6.4 Delivery report (pull)

`POST /api/v1/report/single` with `report_id` (result summary), and `POST /api/v1/report/detail?page=&pageSize=` with `report_id` (per-number detail, 20 per page by default, 1 to 100). Source: official API page.

**The response format and status values are not documented.** The provider therefore does not implement `DeliveryReportQuery`; guessing a status mapping could report undelivered messages as delivered.

### 6.5 Push / OTP

No webhook and no dedicated OTP endpoint are documented. OTP goes through `1toN` with `message_content_type: "bilgi"`.
