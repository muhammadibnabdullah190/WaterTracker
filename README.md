# Water Tracker

Starter Android Kotlin project with manual water logging, voice input, barcode/QR scanning, photo capture, a test notification, and system pickers for audio and contacts.

## Build the APK
Push these files to the `main` or `master` branch. GitHub Actions runs the workflow in `.github/workflows/android.yml`.

After the workflow succeeds:
1. Open the repository's **Actions** tab.
2. Open the **Build Water Tracker APK** run.
3. Download the **WaterTracker-debug-apk** artifact.

## Permission approach
- Camera: requested only when opening the barcode/QR scanner.
- Microphone: requested only for voice logging.
- Notifications: requested when testing a notification (Android 13+).
- Contacts: uses the Android contact picker; no broad contacts permission.
- Music/audio: uses the system document picker; no broad audio permission.
- Phone: opens the dialler with ACTION_DIAL; it does not place calls or request CALL_PHONE.

## Current limitations
This is a starter build. Water totals are held in memory and reset when the app process is recreated. The photo button uses Android's system camera picker. Test on a physical device before relying on it.


## Build APK

The Android APK is built automatically by GitHub Actions whenever changes are pushed to the `main` branch. The workflow can also be started manually from the Actions tab using **Build Water Tracker APK**.
