# Architecture and Data Handling

## Components

- **Flutter/Dart:** setup, consent, source selection, connection test, and delivery status.
- **Kotlin `NotificationListenerService`:** receives notifications while the Flutter UI is closed, filters selected package names, and applies conservative expense parsing.
- **AndroidX WorkManager:** performs HTTPS submissions away from the notification callback and retries temporary failures with bounded exponential backoff.
- **Android Keystore:** protects the saved Apps Script URL and shared secret at rest.
- **Google Apps Script:** validates the token and payload, then appends one row to the configured worksheet.
- **Google Sheets:** durable transaction history.

Minimum supported Android version is **Android 11 (API 30)**. The Gradle application module sets `minSdk 30`.

## Request contract

The client sends an HTTPS `POST` with `Content-Type: application/json`:

```json
{
  "token": "user-configured-shared-secret",
  "timestamp": "2026-10-05T16:30:00+07:00",
  "merchant": "STARBUCKS",
  "amount": 125000,
  "source": "BCA",
  "raw": "Payment notification text"
}
```

`raw` is empty unless the user explicitly enables raw-text forwarding. The token is not written to the spreadsheet. An `action: "ping"` request checks token and worksheet configuration without writing a transaction.
The current MVP timestamp is the Android notification receipt time; it does not try to infer a transaction timestamp from source-specific text.

Apps Script appends these columns in order:

```text
Timestamp | Merchant | Amount | Source | Raw
```

## Reliability and limits

- WorkManager input data contains only the transaction fields needed for a pending delivery. The endpoint URL and shared token are read from encrypted app-private storage when the worker runs.
- Pending raw text may be held temporarily by Android WorkManager until delivery succeeds or the work is cleared. This is not a transaction database or history; payload size is bounded.
- Revoking raw-text consent cancels pending deliveries. Workers also re-check the current consent before sending.
- Transient network/server errors use bounded retry. Invalid credentials and other non-retryable errors are surfaced.
- Apps Script's Content Service may redirect a successful POST response. The client follows only HTTPS redirects to Google Apps Script/Googleusercontent hosts.
- Delivery is **at least once**, not exactly once. A lost response after a successful append can cause a duplicate row on retry.
- The notification parser is heuristic, not bank-specific. It rejects unlabelled ambiguous amounts and common income/failure/refund indicators; source-specific validation is needed before relying on it.
- An expense-like notification that cannot be parsed safely is skipped and its timestamp is shown as operational status; its notification content is not retained for that status.

## Privacy and security

- Only user-selected notification sources are processed.
- Notification content is sent only to the user-configured HTTPS Apps Script URL.
- Raw notification text requires explicit opt-in and may include sensitive balance/account details.
- The shared secret is encrypted with an AES key held by Android Keystore. It is never put in WorkManager input data or app logs.
- The Apps Script token is stored in Script Properties and is not included in the row or responses.
- The secret remains a bearer credential in a client-side application. Rotate it if exposed and restrict deployment access as much as the use case allows.
- Apps Script and Android code avoid logging raw transaction payloads, notification text, and secrets.
- Clearing app configuration cancels pending work and removes local credentials; it does not delete rows already written to Google Sheets.
