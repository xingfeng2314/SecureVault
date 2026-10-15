# SecureVault

English | [简体中文](README.zh-CN.md)

SecureVault is an offline-first password manager and private vault for Android, built with Kotlin and Jetpack Compose. Everything is encrypted on the device; nothing leaves your phone unless you turn on encrypted Google Drive backup.

## Features

- **Password vault**: save accounts with tags, search, copy password (clipboard marked sensitive and cleared after 30 s) and view password.
- **Photo & document vault**: store photos of important cards and files such as PDF and Word, with tags, rename and long-press delete. PDFs open inside the app.
- **Password generator** using `SecureRandom`.
- **Breach check** via the free [Pwned Passwords](https://haveibeenpwned.com/API/v3#PwnedPasswords) k-anonymity API. Only the first 5 characters of the SHA-1 hash are sent.
- **Fingerprint unlock** with an Android Keystore key that is invalidated when new fingerprints are enrolled.
- **Auto-lock** 30 seconds after the app goes to the background, and screenshots are blocked (`FLAG_SECURE`).
- **Encrypted backup** to a local `.svbak` file or automatically to your own Google Drive (keeps the last 3 copies).

## Security design

- Master password → PBKDF2-HMAC-SHA256 (210,000 iterations) → key that wraps a random 256-bit data key.
- Data and files are encrypted with AES-256-GCM, in 1 MB chunks with a unique IV per chunk.
- Backups are encrypted before upload; the Drive job never has access to your keys.
- Google Drive access uses the `drive.file` scope, so the app can only see files it created.
- Android system backup is disabled (`allowBackup="false"`).

If you forget the master password, your data cannot be recovered.

## Build

Requirements: Android Studio, JDK 17, Android 8.0 (API 26) or later.

```bash
git clone https://github.com/xingfeng2314/SecureVault.git
```

Open the project in Android Studio, sync Gradle and run.

### Google Drive backup (optional)

The OAuth client in this repo is bound to the original signing key, so builds signed with your own key need their own client:

1. In [Google Cloud Console](https://console.cloud.google.com/), enable the Google Drive API.
2. Create an OAuth client of type **Android** with package `com.morterain.securevault` and your signing certificate SHA-1 (`./gradlew signingReport`).
3. Configure the OAuth consent screen and add your account as a test user, or publish it.

## Roadmap

- TOTP codes
- Autofill service
- Security report
- Argon2id key derivation
- Restore from Google Drive

## License

Not yet specified.
