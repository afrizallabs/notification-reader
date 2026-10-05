# Requirements: Android Notification Listener to Google Sheets

## 1. Overview

An Android application built with Flutter that captures expense transaction notifications from user-selected apps and sends them directly to a Google Apps Script web app. The Apps Script validates the payload and appends a row to a configured Google Spreadsheet.

```text
Android NotificationListenerService
              |
              | HTTPS POST (JSON)
              v
       Google Apps Script
              |
              | appendRow(...)
              v
       Google Spreadsheet
```

The spreadsheet is the user's transaction record. The solution does not require a custom server/API, Docker, homelab, user accounts, or an application-managed transaction database.

## 2. Goals and Scope

### Goals

- Capture notifications only from source apps explicitly selected by the user.
- Extract the timestamp, merchant, amount, source app, and raw notification text.
- Send each eligible transaction over HTTPS to the user's configured Apps Script deployment.
- Append the transaction as a new row in the configured Google Sheet.
- Make setup, permission, connection, and delivery status understandable to the user.

### Out of scope for the MVP

- A transaction-management backend, custom API server, or application-managed transaction database.
- In-app transaction history, expense reports, budgets, categories, or editing records already written to Sheets.
- Reading SMS, email, call history, accessibility content, or screen contents.
- Accessing bank accounts or initiating transfers/payments.
- Cloud services other than the user's Google Apps Script and Google Spreadsheet.
- iOS support. iOS does not provide a general equivalent for reading notifications from other apps.

## 3. Users and Platform

- **Primary user:** an Android device owner who wants expense notifications recorded in their own Google Spreadsheet.
- **MVP platform:** Android 11 (API 30) or later, with a Flutter/Dart UI and a native Kotlin `NotificationListenerService`.
- **UI language:** Indonesian.
- **Destination:** a Google Spreadsheet selected/configured by the user and written through their Apps Script web app.
- **Initial currency:** IDR. The MVP `Amount` field is an IDR amount; supporting multiple currencies requires an explicit schema change.

## 4. Primary User Flow

1. The user creates or copies the supplied Google Apps Script into their Google account, configures the target spreadsheet and sheet, sets a shared secret, and deploys the script as a web app.
2. In the Flutter app, the user enters the Apps Script deployment URL and shared secret, chooses which source apps to monitor, and tests the connection.
3. The app explains that notification data, including raw notification text when enabled, will be sent to Google over HTTPS and stored in the configured spreadsheet.
4. The user explicitly enables raw-text forwarding (required for the default five-column output) and grants Android notification-listener access through system settings.
5. Android receives a notification from a selected app. The native listener filters it and extracts the available transaction fields.
6. The Android app sends a JSON `HTTPS POST` to the configured Apps Script endpoint. Apps Script validates the shared secret and payload, then appends a row to the target sheet.
7. The app records delivery status locally as operational status only (for example, last successful delivery and last error); it does not keep a local transaction history.
8. If delivery fails temporarily, the app retries using Android's background-work scheduling. Permanent configuration or validation errors are shown to the user.

## 5. Functional Requirements

### 5.1 Apps Script and spreadsheet setup

- **FR-01** The solution must provide setup instructions for creating/deploying the Apps Script web app and configuring its target spreadsheet and worksheet.
- **FR-02** The Apps Script must expose a `doPost(e)` endpoint that accepts a JSON request body over HTTPS.
- **FR-03** The Apps Script must validate the shared secret and required fields before writing to Sheets.
- **FR-04** The Apps Script must append one row per accepted notification to the configured worksheet. It must not overwrite existing rows.
- **FR-05** The worksheet's default header order must be `Timestamp`, `Merchant`, `Amount`, `Source`, `Raw`.
- **FR-06** The Apps Script must return a machine-readable success or error response. It must not report success unless the row has been appended.
- **FR-07** The Apps Script must use the configured spreadsheet ID and worksheet name, and report configuration errors without exposing secrets.

### 5.2 Flutter setup and consent

- **FR-08** Before requesting notification access, the app must explain that selected notification content is sent to the user's Google Apps Script and written to a Google Spreadsheet.
- **FR-09** The app must explain the fields sent, including that `Raw` contains notification text, and obtain explicit user consent before sending raw text.
- **FR-10** The app must provide fields to configure the Apps Script deployment URL and shared secret, plus an action to test connectivity/authentication.
- **FR-11** The app must validate the URL format and reject non-HTTPS endpoints.
- **FR-12** The shared secret must be stored securely on the device and must not be displayed again in plain text after it has been saved.
- **FR-13** The user must be able to select, change, and disable monitored source apps. No source app may be selected without user consent.
- **FR-14** The app must show notification-listener permission status, destination configuration status, and automatic-forwarding status.
- **FR-15** If permission is revoked, the app must stop processing new notifications and indicate that automatic forwarding is inactive.

### 5.3 Notification capture and payload

- **FR-16** The native listener may process notifications only from package names selected by the user.
- **FR-17** The listener must ignore notifications that do not appear to describe a transaction and notifications that cannot be safely identified as an expense.
- **FR-18** For an eligible notification, the app must construct a JSON payload containing:
  - `timestamp`: notification receipt time in ISO 8601 format (the MVP does not attempt source-specific extraction of transaction time);
  - `merchant`: detected merchant/payee, or a clearly defined fallback when unavailable;
  - `amount`: numeric IDR amount, without locale-specific display formatting;
  - `source`: human-readable source app name;
  - `raw`: original notification text, included only after the user has enabled and consented to raw-text forwarding.
- **FR-19** If a required field such as amount cannot be parsed safely, the app must not send a success-shaped or fabricated transaction. It must report the notification as skipped/unrecognized in operational status.
- **FR-20** The app must not retain a transaction history locally. Notification text and transaction data may be held only as long as needed to submit and retry delivery.
- **FR-21** The app should avoid forwarding the same active/updated Android notification repeatedly when its notification key indicates it is an update. The MVP does not guarantee deduplication across distinct notifications from a source app.
- **FR-22** Processing must work when the Flutter UI is closed, subject to Android service and background-work limitations.

### 5.4 Delivery, retry, and status

- **FR-23** The app must send the payload to Apps Script using HTTPS POST with JSON content.
- **FR-24** Network submission must run outside the notification callback and must not block notification display or source-app interaction.
- **FR-25** Temporary network failures and retryable server errors must be retried with bounded exponential backoff through Android background-work scheduling.
- **FR-26** Authentication, invalid payload, missing spreadsheet/sheet, and other non-retryable errors must be surfaced as actionable errors rather than retried indefinitely.
- **FR-27** The app must mark a submission successful only after receiving and validating an Apps Script success response.
- **FR-28** The app must show at least the time of the last successful delivery and the most recent actionable delivery error. It must not present these indicators as a full transaction ledger.
- **FR-29** The app must provide a way to disable forwarding and clear its locally stored endpoint/secret and pending work.

## 6. Non-functional Requirements

### Privacy and security

- **NFR-01** All notification data sent to Google must use HTTPS. The app must reject non-HTTPS endpoint URLs.
- **NFR-02** The app must send data only to the endpoint explicitly configured by the user.
- **NFR-03** Raw notification forwarding must require clear disclosure and explicit user consent because it may contain balances, partial account/card details, or other sensitive text.
- **NFR-04** The shared secret must be stored using Android-protected secure storage and must not be written to logs, analytics, crash reports, or error messages.
- **NFR-05** The Apps Script must store the expected shared secret in Script Properties, compare it safely, and never return it in responses.
- **NFR-06** Apps Script deployment instructions must explain that the web app needs access from the Android client and that the shared secret is an application-level protection, not a replacement for careful endpoint sharing or rotation.
- **NFR-07** The app and Apps Script must not send notification content to analytics, third-party telemetry, or any endpoint other than the configured Apps Script.
- **NFR-08** The app must send the minimum payload required by the configured sheet. Raw text is sent only when enabled; the user must be able to turn it off.
- **NFR-09** Apps Script execution logs and Android logs must not contain the shared secret or full raw notification text.

### Reliability and performance

- **NFR-10** Parsing or network failure for one notification must not crash the listener or prevent later notifications from being processed.
- **NFR-11** Notification callbacks must remain lightweight; network requests and retry delays must run in a worker.
- **NFR-12** Retry scheduling must be bounded and respect Android background-work policies. The app must expose when pending delivery has failed or is delayed.
- **NFR-13** The app must handle Apps Script response errors, quota/rate limits, invalid JSON, timeout, and unavailable network without claiming success.
- **NFR-14** Notification formats may vary or change. Parsers must fail safely rather than inventing merchant or amount data.
- **NFR-15** The app must not promise immediate or guaranteed delivery when the device is offline, Android stops background work, or Apps Script quotas are exceeded.

### Compatibility and localization

- **NFR-16** The interface must support system text scaling, adequate contrast, consistent navigation, and screen-reader labels.
- **NFR-17** Parsing must handle common Indonesian amount formats, then send a canonical numeric IDR value rather than localized display text.
- **NFR-18** The timestamp sent to Apps Script must be unambiguous and include timezone information or use UTC in ISO 8601 format.
- **NFR-19** The implementation must follow Android notification-listener and background-work requirements for supported Android versions.

## 7. Apps Script Contract

### Request

`POST {configured Apps Script deployment URL}`

Content type: `application/json`

Example:

```json
{
  "token": "configured-shared-secret",
  "timestamp": "2026-10-05T16:30:00+07:00",
  "merchant": "STARBUCKS",
  "amount": 125000,
  "source": "BCA",
  "raw": "Pembayaran QRIS..."
}
```

The `token` is used only for request validation and must not be written to the spreadsheet. If raw-text forwarding is disabled, `raw` must be empty or omitted according to the agreed script contract.

### Spreadsheet row

The default worksheet columns are:

| Timestamp | Merchant | Amount | Source | Raw |
|---|---|---:|---|---|
| `2026-10-05T16:30:00+07:00` | `STARBUCKS` | `125000` | `BCA` | `Pembayaran QRIS...` |

### Response

- Success response: machine-readable JSON with a success indicator and no secret.
- Failure response: machine-readable error code and safe message; do not include secrets or raw notification contents.
- The Android client must validate the response body, not treat any HTTP response as success.

## 8. Screens

1. **Setup** — Apps Script URL, shared secret, connection test, and short setup guidance.
2. **Data and consent** — what is captured/sent, raw-text consent, and destination disclosure.
3. **Source apps** — choose apps whose notifications can be forwarded.
4. **Status** — notification permission, forwarding state, last successful delivery, and actionable error.
5. **Settings** — edit endpoint/secret, pause/resume forwarding, revoke guidance, and clear configuration/pending work.

The MVP does not require a transaction list, reports, categories, or local transaction editing; Google Sheets is the record of transactions.

## 9. MVP Acceptance Criteria

- [ ] The user can configure an Apps Script URL and shared secret and test the connection.
- [ ] The setup explains that transaction data is sent to Google Sheets through Apps Script.
- [ ] Raw notification text is sent only after explicit consent.
- [ ] The user can grant/revoke Android notification access and select monitored source apps.
- [ ] Notifications from unselected source apps are not forwarded.
- [ ] A supported expense notification produces an HTTPS JSON POST with the agreed fields.
- [ ] Apps Script validates the secret and required data, then appends exactly one row with the default five columns.
- [ ] Invalid amounts and non-transaction notifications do not create fabricated spreadsheet rows.
- [ ] Temporary network failures are retried within bounded Android background-work policies; permanent errors are visible.
- [ ] The app reports success only after validating the Apps Script success response.
- [ ] No custom server/API or application-managed transaction database is required.
- [ ] The endpoint secret and raw notification text are not written to application logs.
- [ ] The user can disable forwarding and clear local configuration and pending payloads.

## 10. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Notification text changes or is ambiguous | Per-source parsing where practical; reject unsafe amounts rather than inventing values |
| Raw text contains sensitive information | Explicit consent, clear field disclosure, opt-out, HTTPS, and no raw-text logging |
| Apps Script URL or secret is misconfigured | Connection test, actionable error response, and setup guide |
| Public web app endpoint is abused | Validate a user-specific shared secret, keep it in Script Properties and Android secure storage, and provide rotation instructions |
| Device is offline or Android delays work | Bounded persistent background-work retries, delivery status, and no promise of immediate delivery |
| Apps Script quotas or Sheets permissions block writes | Surface service errors and document Google quotas and authorization requirements |
| Repeated notification updates create duplicate rows | Filter obvious updates using Android notification identity; document that cross-notification deduplication is not guaranteed in the MVP |

## 11. Decisions Required Before Implementation

- Which source apps and notification formats must be supported first.
- Whether raw-text forwarding is enabled during setup (recommended: require an explicit choice; default off until consent).
- Merchant fallback behavior when no merchant can be identified.
- Whether Apps Script and the spreadsheet are created by the user manually or through a guided setup.
- Retry limit/retention behavior for pending work and whether the user can manually retry.
- Exact deployment access settings and shared-secret rotation instructions for the Apps Script web app.
