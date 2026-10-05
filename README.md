# Expense Notification Forwarder

Flutter Android app that forwards selected expense notifications to a Google Apps Script endpoint. The script appends each accepted transaction to the user's Google Spreadsheet.

```text
Android NotificationListenerService → HTTPS POST → Apps Script → Google Sheets
```

This project has no custom server/API, Docker, homelab, or application-managed transaction database. Google Sheets is the transaction record. The app supports Android 11 (API 30) and later.

## Requirements

- Flutter stable and Dart SDK compatible with the `pubspec.yaml` SDK constraint.
- Android SDK and Android build tools supported by the Flutter release used.
- JDK 17.
- A Google account with access to Google Apps Script and Google Sheets.

## Run

1. Complete the Google Apps Script setup in [docs/SETUP.md](docs/SETUP.md).
2. Follow the setup guide to configure Apps Script Properties and the app's endpoint and shared secret.
3. Install Flutter stable, Android SDK, and JDK 17. This workspace was created without those tools; if the Flutter-generated Android Gradle wrapper files are missing, run `flutter create --platforms=android .` and review the generated changes to ensure the Kotlin listener and Android configuration are preserved.
4. From the project root, run:

   ```powershell
   flutter pub get
   flutter analyze
   flutter test
   node scripts/test_apps_script.js
   flutter build apk --debug
   ```

5. Install the APK on an Android 11+ device.
6. Configure the Apps Script `/exec` URL and shared secret in the app.
7. Test the endpoint, choose the notification source apps, grant notification access in Android settings, and explicitly choose whether raw notification text may be sent.
8. Enable automatic forwarding.

## Documentation

- [Setup and deployment](docs/SETUP.md)
- [Architecture, payload, and privacy](docs/ARCHITECTURE.md)
- [Product requirements](requirements-notification-listener-pencatat-pengeluaran.md)
- [Flutter/Android tech stack requirements](requirements-tech-stack-notification-listener-flutter.md)

## Important limitations

- Notification wording varies by source app. The current parser is conservative and accepts expense-related text with a recognizable amount; it is not a bank-specific parser. Verify it with the actual source apps before relying on it. The timestamp currently records Android notification receipt time.
- The Apps Script token must contain 32–256 characters. The Android app rejects shorter or longer values.
- Delivery depends on Android background-work policies, network access, and Apps Script/Sheets quotas.
- Exactly-once delivery is not guaranteed. If Apps Script appends a row but its response is lost, a retry may append a duplicate.
- The shared secret is a bearer credential embedded in a client application. Treat it as a deterrent, not a substitute for restricting and rotating endpoint credentials.
