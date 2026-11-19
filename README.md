# Jarvis

A Siri-style voice assistant for Android that runs on your phone, for free. It understands Hindi, English and Hinglish.

- **Instant commands** from fast rules: alarms, timers, calls, apps, music, volume, flashlight, weather, directions, search.
- **An on-device LLM** (Qwen3.5 2B through [llama.cpp](https://github.com/ggml-org/llama.cpp)) handles everything else: free-form requests like "Rahul ko message karo ki main late hoon", and conversation.
- **Natural turn-taking:** it keeps listening when a sentence sounds unfinished and asks follow-up questions.

**Status:** work in progress. Phase 2 (the LLM brain) is being tuned on a Galaxy S23. See [docs/PLAN.md](docs/PLAN.md).

## Build

Requirements: Android Studio Ladybug (2024.2) or newer with NDK 28.2.13676358 and CMake 3.22.1 (SDK Manager), and a 64-bit Android 12+ phone.

```sh
git clone --recursive https://github.com/hardik979/jarvis.git
# already cloned without --recursive?  git submodule update --init
```

Open the folder in Android Studio and press **Run**. From the command line (needs JDK 17+, e.g. Android Studio's bundled `jbr`):

```sh
./gradlew installDebug
```

The first build compiles llama.cpp and takes a few minutes.

## First run

1. Open **Jarvis** and allow the microphone. Optionally allow calls, contacts and location.
2. Tap **Open settings** and choose Jarvis as the **Digital assistant app**.
3. Under **Brain**, tap **Download** (1.2 GB, once). Without it, Jarvis uses the rules only.
4. Swipe up from a bottom corner (or long-press Home) to call Jarvis.

Optional: Settings → Advanced features → Side button → Double press → Open app → **Ask Jarvis**.

## Benchmarks

`tools/llm-bench` runs llama.cpp on a phone over adb and measures speed, RAM, Hindi token cost and command accuracy for candidate models.
