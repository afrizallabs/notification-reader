# Tech Stack Requirements: Flutter to Google Sheets Notification Forwarder

This document complements [requirements-notification-listener-pencatat-pengeluaran.md](./requirements-notification-listener-pencatat-pengeluaran.md). It defines the implementation stack for the flow in which an Android notification listener sends transaction data directly to a Google Apps Script web app, which appends a row to Google Sheets.

## 1. Stack Decisions

| Area | Selected technology | Requirements |
|---|---|---|
| Client application | Flutter and Dart | Build setup, consent, source selection, connection testing, and delivery-status screens in Flutter. |
| MVP platform | Android | The MVP targets Android only. Keep Android-specific code behind platform boundaries. |
| Notification listener | Native Android `NotificationListenerService` in Kotlin | Android must receive notifications even when the Flutter UI is closed. |
| Background HTTP delivery | AndroidX WorkManager with a Kotlin worker | Enqueue network work outside the notification callback and use bounded retry/backoff for transient failures. WorkManager's internal scheduling persistence is platform infrastructure, not an application-managed transaction database. |
| App-to-native communication | Flutter `MethodChannel` | Flutter configures/reads listener status and endpoint configuration, tests the connection, pauses/resumes forwarding, and clears pending work. |
| HTTP client | Android `HttpsURLConnection` or another small, maintained Android HTTP client | POST JSON to the configured HTTPS Apps Script URL, apply timeouts, parse the response, and avoid logging sensitive payloads. Do not add a general backend/API. |
| Apps Script endpoint | Google Apps Script, JavaScript V8 runtime | Implement `doPost(e)`, validate the request, and append a row to a configured Google Spreadsheet. |
| Destination storage | Google Spreadsheet | The spreadsheet is the transaction record and source of truth. No local transaction database is required. |
| Flutter state management | Riverpod (`flutter_riverpod`) | Separate screen state and app configuration/status access from widgets. |
| Navigation | `go_router` | Manage setup, consent, source selection, status, and settings routes. |
| Localization and formatting | `intl` | Format UI dates/numbers for Indonesian users; send canonical ISO 8601 timestamps and numeric IDR amounts. |
| Secure configuration | Android Keystore-backed encrypted storage | Store the Apps Script URL and shared secret securely; do not put the secret in source code, plain preferences, logs, or spreadsheet rows. |
| Flutter testing | `flutter_test` and `integration_test` | Test configuration, consent, validation, status states, and Flutter/native integration. |
| Native Android testing | JUnit and AndroidX Test | Test notification filtering, payload construction, WorkManager retry/error paths, and secure configuration access. |
| Apps Script testing | Apps Script test functions and deployment smoke tests | Test validation, spreadsheet selection, append behavior, and success/error response contracts. |

Pin compatible Flutter/Dart and Android toolchain versions at project setup. Pin Flutter packages in `pubspec.lock`; do not add a local SQLite dependency such as Drift for transaction storage.

## 2. Architecture and Data Flow

```text
Android NotificationListenerService (Kotlin)
        |
        | filters selected packages; extracts minimum fields
        v
AndroidX WorkManager (Kotlin worker)
        |
        | HTTPS POST, JSON
        v
Google Apps Script doPost(e)
        |
        | validates token and payload; appendRow(...)
        v
Google Spreadsheet

Flutter UI (Dart) -- MethodChannel --> native configuration/status
```

### Data flow requirements

- The native listener receives Android notification callbacks and filters them against the user-selected source-package allowlist.
- The listener must not perform network I/O in the callback. It creates a bounded payload and enqueues a Kotlin WorkManager job.
- The worker reads the configured endpoint/secret from protected native storage and sends the JSON payload over HTTPS.
- WorkManager retries only transient errors according to bounded backoff. Permanent errors must be marked failed and made visible in app status.
- The worker marks a delivery successful only after validating the Apps Script response body.
- There is no Flutter inbox, transaction repository, local transaction database, or local transaction history.
- WorkManager may persist pending work using its own Android-managed storage so work can survive process termination. The app must keep payload scope and retention bounded and clear pending work when the user disables forwarding or clears configuration.
- Avoid logging payload bodies, raw notification text, endpoint tokens, or response bodies that could echo sensitive values.

## 3. Flutter Application Requirements

- Implement only the screens required for setup, data consent, source-app selection, status, and settings.
- Use Riverpod for view state and a typed platform-channel service for native operations.
- Widgets must not call platform channels directly; route native calls through a service/provider layer.
- Validate that the configured endpoint is an HTTPS URL before saving or testing it.
- Provide clear loading, success, permission-denied, configuration-error, network-error, and pending-work states.
- Make raw notification forwarding an explicit consent setting. When disabled, omit the raw text or send an empty value according to the documented request contract.
- Do not implement transaction history, categories, reports, budgets, or transaction editing in the MVP.

## 4. Native Android Requirements

- Implement `NotificationListenerService` in Kotlin and declare the required Android service permission/configuration.
- The user must grant notification access through Android system settings; the app cannot grant it programmatically.
- Persist the source-app allowlist and forwarding preferences in app-private storage. Forward only selected sources.
- Keep the notification callback short: identify/serialize the necessary fields and enqueue work. Do not execute HTTP calls or wait for the network on the callback thread.
- Use WorkManager constraints and exponential backoff for transient connectivity/server failures. Define maximum retry/retention behavior and surface exhausted retries.
- Use explicit timeouts and safe response parsing. Treat a malformed response as failure, not success.
- Use Android Keystore-backed encryption for the shared secret. The secret must not be embedded in Dart assets, build configuration checked into version control, or source code.
- Expose typed `MethodChannel` operations for:
  - read listener permission status;
  - read/set selected source packages;
  - read/save/delete Apps Script endpoint configuration;
  - test endpoint connectivity/authentication;
  - enable/disable forwarding;
  - read last success, pending/failure status, and safe error code;
  - clear pending work and local endpoint credentials.
- Version the channel contract and validate every value crossing the Flutter/native boundary.
- Test service behavior when Flutter is closed, permission is revoked, device is offline, endpoint returns an error, and the worker is retried.

## 5. HTTP and Apps Script Contract

### Request

- Method: `POST`
- URL: user-configured Apps Script web app deployment URL.
- Transport: HTTPS only.
- Content type: `application/json`.
- Required transaction fields: `timestamp`, `merchant`, `amount`, `source`, `raw`.
- Include a `token` field in the JSON body for shared-secret validation because Apps Script web-app event objects do not provide a general request-header API.
- Do not put the token in the spreadsheet row or return it in a response.

Example request:

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

### Apps Script behavior

- Implement a `doPost(e)` handler in the user's Google Apps Script project.
- Store the expected token, spreadsheet ID, and worksheet name in Apps Script Properties, not in the client-visible response.
- Validate the token, required fields, amount type/range, and timestamp before writing.
- Use a script lock around spreadsheet append operations if needed to prevent concurrent write issues.
- Append one row in the agreed order: `Timestamp`, `Merchant`, `Amount`, `Source`, `Raw`.
- Return a machine-readable JSON success/error response. Never return success before the row is appended.
- Return safe error codes/messages; do not include the token or raw text in error responses.
- Document Apps Script deployment access, spreadsheet permissions, quota limitations, and shared-secret rotation.

### Client behavior

- Validate the response body, not only the HTTP status code.
- Retry timeouts, unavailable networks, and explicitly retryable server errors with bounded backoff.
- Do not endlessly retry invalid credentials, malformed payloads, or missing spreadsheet configuration.
- The MVP does not guarantee exactly-once delivery. A retry after the sheet append succeeds but before the client receives the response could create a duplicate row; document this limitation.

## 6. Data and Security Requirements

- No custom server/API, Docker deployment, homelab, user-account system, or application-managed transaction database.
- Google Sheets is the durable transaction store. Local storage is limited to configuration, operational status, and bounded Android background-work payloads.
- Obtain explicit consent before forwarding raw notification text. Explain that it may contain balances or partial account/card details.
- Send transaction content only to the endpoint configured by the user and only over HTTPS.
- Store the shared secret using Android Keystore-backed encrypted storage. Provide a way to clear it and instructions to rotate it in Apps Script.
- Store the expected secret in Apps Script Properties. Deploy the web app with the access setting necessary for the Android client, and require the secret in every request.
- Treat the shared secret as a bearer credential: keep it private, rotate it if exposed, and do not consider it a substitute for reviewing deployment permissions.
- Do not include raw notification text, token, or financial payloads in Android/Apps Script logs, analytics, or crash reports.
- Do not introduce Firebase, analytics, cloud databases, or third-party data processors in the MVP.
- Clear queued payloads when forwarding is disabled or the user clears configuration, subject to Android's ability to cancel scheduled work.

## 7. Dependency and Build Requirements

Recommended Flutter dependencies:

- `flutter_riverpod` — state management.
- `go_router` — navigation.
- `intl` — locale-aware display formatting.

Use AndroidX WorkManager and native Android APIs for background delivery and notification listening. Add third-party dependencies only when necessary, maintained, licensed appropriately, and reviewed for data handling.

Build and quality checks:

- Run `dart format`, `flutter analyze`, Flutter unit/widget tests, Android native tests, and an Android debug build.
- Test amount parsing and timestamp conversion with representative Indonesian notification formats.
- Test platform-channel validation and all documented error states.
- Test Apps Script with invalid token, invalid/missing fields, unavailable worksheet, successful append, and transient failures.
- Do not put signing keys, endpoint tokens, spreadsheet credentials, or shared secrets in source control.
- Pin and document Flutter, Kotlin, JDK, Gradle/Android Gradle Plugin, Android SDK, and dependency versions used by the project.

## 8. Android Configuration

- Set `minSdk` to 30 (Android 11) or higher; use a higher minimum only if a selected Flutter/Android dependency requires it.
- Use a `targetSdk` that meets Android distribution requirements in effect at release time.
- Declare only the permissions and components required for notification access, networking, and WorkManager.
- Explain notification access and remote data transfer in onboarding and the store listing; do not hide the listener behavior.
- Test permission settings and background work on representative Android versions and device vendors.

## 9. Out of Scope

- iOS notification capture.
- Custom backend/API, Docker, homelab, Firebase backend, or server-side transaction database.
- Drift/SQLite transaction storage, transaction ledger UI, reports, budgets, categories, and editing rows in the app.
- Cloud AI/ML parsing, accessibility-service scraping, SMS/email access, or screen scraping.
- Exactly-once delivery across client/server failures. The MVP may produce a duplicate row if Apps Script appends successfully but the response is lost before the client receives it.

## 10. Stack Acceptance Criteria

- [ ] The Flutter app builds for Android using pinned and documented toolchain versions.
- [ ] The Kotlin notification listener works when the Flutter UI is closed and filters unselected source apps.
- [ ] Eligible notification events are enqueued for background HTTPS POST without networking in the notification callback.
- [ ] The request matches the agreed JSON contract and is sent only to the configured HTTPS endpoint.
- [ ] Apps Script validates the shared secret and appends the expected five-column row.
- [ ] The worker validates Apps Script responses, retries transient errors within bounded policy, and exposes permanent failures.
- [ ] The secret is stored in protected device storage and Script Properties, never in logs or spreadsheet rows.
- [ ] No application-managed transaction database or custom server/API is introduced.
- [ ] `flutter analyze`, relevant Flutter/native tests, Apps Script smoke tests, and an Android debug build pass.

## 11. Decisions Before Implementation

- Which source apps and notification formats are supported first.
- Minimum Android version: Android 11 (API 30); also select representative test devices.
- Exact parsing rules and merchant fallback when a merchant name is unavailable.
- Whether the raw-text consent is enabled during initial setup; it must require an explicit choice.
- Apps Script setup model: user-created script/spreadsheet versus a guided copy-and-configure flow.
- WorkManager payload size, retry limit, pending-work retention, and manual retry behavior.
- Apps Script web-app access settings and the process for configuring and rotating the shared secret.
