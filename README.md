# Jarvis

A Siri-style voice assistant for Android that runs on your phone, for free. It understands Hindi, English and Hinglish.

**Status:** Phase 0, the voice loop: it hears you and speaks back. See [docs/PLAN.md](docs/PLAN.md) for the full plan.

## Build

Requirements: Android Studio Ladybug (2024.2) or newer, and an Android 12+ phone with USB debugging on.

1. Open this folder in Android Studio, then wait for Gradle sync to finish.
2. Plug in the phone and press **Run**.

From the command line (needs JDK 17+, e.g. Android Studio's bundled `jbr`):

```sh
./gradlew installDebug
```

## First run

1. Open **Jarvis**, allow the microphone.
2. Tap **Open settings** and choose Jarvis as the **Digital assistant app**.
3. Swipe up from a bottom corner (or long-press Home) to call Jarvis.

Optional: Settings → Advanced features → Side button → Double press → Open app → **Ask Jarvis**.
