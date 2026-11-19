# Jarvis: Plan

An on-device, Siri-style voice assistant for Android. Built and tested on a Galaxy S23 (Snapdragon 8 Gen 2, 8 GB RAM).

## Goals

- Everyday commands feel instant. Everything else is handled by a small on-device LLM.
- Free to run: on-device model, the phone's built-in speech engines, free web APIs that need no key.
- Understands and speaks **Hindi, English and Hinglish**.
- Open source once it's solid. Build in public (YouTube video + reels per phase).

## Decisions

| Area | Decision |
|---|---|
| App | Kotlin, Jetpack Compose, minSdk 31. Single `app` module until a split pays off. |
| Invocation | Registered as the **default digital assistant** (`VoiceInteractionService`). The session opens `AssistantActivity` as an overlay. A second launcher entry, "Ask Jarvis", is for Samsung's side-key double-press. Wake word comes in Phase 3. |
| Speech-to-text | Android `SpeechRecognizer`, bound explicitly to Google's recognizer. `hi-IN` handles code-mixed Hinglish; `en-IN` is selectable. Later: sherpa-onnx for fully offline STT. |
| Text-to-speech | Android `TextToSpeech`, Google engine preferred. Voice is picked per sentence: Devanagari → `hi-IN`, Latin script → `en-IN`. Later: Piper/Kokoro via sherpa-onnx. |
| Brain | Three levels: **rules** (instant) → **LLM tool call** (fuzzy commands) → **LLM chat** (questions). |
| LLM runtime | llama.cpp (GGUF, Q4_0) via JNI, behind an `LlmEngine` interface so MediaPipe/LiteRT-LM can be swapped in. JSON-grammar-constrained tool calls. Cached system prompt. |
| Model | Picked by benchmark on the S23, including Hindi/Hinglish quality. Candidates: Qwen3 1.7B, Gemma 3n E2B, Llama 3.2 3B. Check for newer releases at benchmark time. |
| Online tools | Allowed, always optional: Open-Meteo (weather), Wikipedia, more later. |
| Distribution | GitHub releases first. Play Store restricts SMS, call log and accessibility permissions. |

## Architecture

```
TRIGGER  assistant gesture │ side-key → "Ask Jarvis" │ (Phase 3) "Hey Jarvis"
            │
HEAR     SpeechInput: SpeechRecognizer → partial + final text, mic level for the orb
            │
THINK    Brain
           ① rules matcher ──match──────────────────────┐
           ② LLM → tool call (JSON) → tool executor ────┤
           ③ LLM chat answer                            │
           ▲ context: memory, notifications, screen, time, location
            │
SPEAK    SpeechOutput: sentence split → hi-IN / en-IN voice → TTS
SHOW     AssistantActivity: bottom card + orb over the current app
```

## Roadmap

- [x] **Phase 0: Voice loop.** Project setup, assistant role, STT → echo → TTS in Hindi and English. Verified on the S23. (The llama.cpp benchmark moves to the start of Phase 2.)
- [ ] **Phase 1: MVP.** Rule parser (`brain/parse`, unit-tested) → `Command` → `CommandExecutor`. Done: time, date, battery, flashlight, volume, music controls, alarm, timer, open app, call, navigation, weather, web/YouTube search, Wi-Fi/Bluetooth panels, small talk. Still to do: SMS, brightness, calendar.
- [ ] **Phase 2: Brain.** On-device LLM: tool calling with grammar-constrained JSON, chat, follow-ups, speaking while the reply streams in.
- [ ] **Phase 3: Jarvis mode.** Wake word, "what did I miss?" notification digest, voice replies to messaging apps, personal memory.
- [ ] **Phase 4: Superpowers.** Screen awareness, chained commands, routines, fine-tuned model (LoRA on our tool format).
- [ ] **Phase 5: Ship.** README, benchmarks, licence, APK on GitHub releases, video.

## Gotchas and notes

- **Recognition service.** Android requires an assistant to declare a `RecognitionService`. Ours is a stub, and `SpeechInput` binds Google's recognizer explicitly instead of using the system default. Verified on the S23 (Android 12+): with Jarvis as the assistant, `voice_recognition_service` stays on Google's recognizer, so other apps' voice input is unaffected.
- **Turn-taking.** Google's recognizer ends a session about 0.15 s after it decides you stopped. `TurnTaking.wantsMore` keeps listening through pauses when the sentence sounds unfinished ("Mummy ko…") or is missing a detail. Replies can carry a `FollowUp` so Jarvis listens again after asking something.
- **Screen context without accessibility.** `VoiceInteractionSession.onHandleAssist` gives the assistant the current app's view structure (and a screenshot, if the user allows it). This is the Play-safe route for "summarise this" in Phase 4.
- **Samsung side key.** Double-press → Open app works on all One UI versions. Press-and-hold may only offer Bixby or the power menu, depending on the One UI version.
- **Wake word licence.** openWakeWord's code is Apache-2.0, but its pretrained models are CC BY-NC-SA. Train our own model or use sherpa-onnx keyword spotting for the public release.
- **Name.** "Jarvis" is a Marvel character. Fine for a personal project; choose a different public name before release.
- **Model files** are downloaded on first run and never bundled in the APK.
