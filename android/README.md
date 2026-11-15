# MTS Staff — Android app

Native Android app (Kotlin, Android Studio project) for the MTS staff web app.
It opens the web app full-screen and adds what a plain browser tab can't do well on Android:

| Feature in the web app | What the Android app does |
|---|---|
| Period reminders, new arrangements | Real Android notifications (asks permission on Android 13+) |
| Excel / report downloads | Saves to the phone's **Downloads** folder, with a "tap to open" notification |
| Share (reports, sheets) | Android share sheet: WhatsApp, Gmail, Drive… (a copy is also saved in Downloads) |
| WhatsApp arrangement messages | Opens WhatsApp directly |
| Print sheet | Android print / Save as PDF |
| Copy text | Android clipboard |
| Smart Assistant voice | Android speech recognition (Hindi / English) and text-to-speech |
| Light / dark theme | Status bar follows the app's theme |
| Back button | Closes a dialog or the menu first, then returns to the home screen |
| No internet on first start | Native "No internet" screen with *Try again* (after that the app opens offline from its saved copy) |

The address the app opens is set in `app/build.gradle.kts` (`APP_URL`). Because the app shows the live website,
every change pushed to GitHub Pages appears in the app automatically; a new APK is only needed for changes to
the Android code itself.

## Open in Android Studio
1. **File → Open…** and choose this `android` folder.
2. Let Gradle sync, then **Run ▶** to install on a phone or emulator.
3. **Build → Generate Signed App Bundle / APK…** for a release build.

## Signing
Release signing is read from `android/keystore.properties` (not committed):

```
storeFile=mts-staff-release.jks
storePassword=…
keyAlias=mts-staff
keyPassword=…
```

Keep the `.jks` file and passwords safe: every future update must be signed with the same key, or phones will refuse to install it over the old version.

## Automatic builds (GitHub Actions)
`.github/workflows/android.yml` builds the APK on every push that changes `android/` and publishes it on the
repository's **Releases** page (tag `android-latest`). To have it signed automatically, add these repository secrets
(Settings → Secrets and variables → Actions): `MTS_KEYSTORE_BASE64` (the .jks file in base64),
`MTS_KEYSTORE_PASSWORD`, `MTS_KEY_ALIAS`, `MTS_KEY_PASSWORD`.

## Details
- Package: `com.meridiantheschool.mtsstaff` · minimum Android 7.0 (API 24) · target API 35
- Version: change `versionCode` (must go up by 1 each release) and `versionName` in `app/build.gradle.kts`
