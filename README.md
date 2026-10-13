# ForgeFit V4.3 — Android

Personal workout logger for Android, with Chest, Back and Legs workout selection, exercise-specific rest timers, and locally stored workout history.

## Build
Open the project in Android Studio and build a debug APK, or use **Actions → Build ForgeFit APK** in GitHub. The resulting APK is available under workflow artifacts.

## Important
- Android application ID: `com.forgefit.personal`.
- Do not uninstall the app or clear app data if you want to preserve workout history.
- Updating over an installed APK requires the same signing key. GitHub Actions debug APKs may use a different signing key from Android Studio debug builds or builds on different runners; do not rely on debug APKs for seamless upgrades.
- Existing browser HTML history does not automatically migrate into Android app storage.
- Rest timers may pause if Android suspends the app.
