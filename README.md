# L0

[![CI](https://github.com/Abhishek-karma/L0/actions/workflows/ci.yml/badge.svg)](https://github.com/Abhishek-karma/L0/actions/workflows/ci.yml)
[![Release](https://github.com/Abhishek-karma/L0/actions/workflows/release.yml/badge.svg)](https://github.com/Abhishek-karma/L0/actions/workflows/release.yml)

A minimal, private Android AI chat client focused on fast token-level streaming with OpenAI-compatible and Google Gemini providers.

## Overview

L0 is a bring-your-own-key (BYOK) AI chat client for Android. It supports direct token-level streaming, local SQLite conversation history, voice input and output, multimodal image and text attachments, and optional keyless web search — with API keys stored securely on-device.

L0 communicates directly from the Android device to the configured AI provider endpoint with no proxy or intermediary servers.

## Features

- **Streaming conversation**: Low-latency token streaming with stop, retry, regenerate, and edit-and-resend capabilities.
- **Answer versions**: Regenerating an answer preserves previous variations, switchable on the message card.
- **Follow-up suggestions**: Contextual follow-up suggestions generated automatically after answers (with natural-language refusal and error detection). Tapping a suggestion stages it in the composer for review without auto-sending.
- **AI providers**:
  - Saved provider profiles, each holding multiple saved models; the active model is switched from the navigation drawer.
  - Built-in presets for Google Gemini, OpenAI, OpenRouter, Groq, and Naga.
  - Custom OpenAI-compatible endpoints with models listing (`/models`) and connection testing.
  - Local/self-hosted LLM support (such as Ollama, LM Studio, or local servers reachable via `localhost`, `10.0.2.2`, or `.local`, `.lan`, `.home`, `.internal` private domains).
  - Collapsible model reasoning visualization for reasoning/thinking models.
  - Think control in the composer for reasoning settings. Capability is detected from the selected model's id (Gemini models get a thinking budget, known reasoning models get effort levels); every other model never receives reasoning parameters.
- **Web search grounding**: Optional DuckDuckGo web search integration toggled per conversation, with cited web sources displayed alongside answers.
- **Multimodal attachments**:
  - Image attachments (camera capture or gallery pick): automatic downscaling (max dimension 1280px) and JPEG compression.
  - Text-like document attachments (Markdown, JSON, code files up to 100 KB) inlined as context.
- **Voice integration**: Speech recognition input and text-to-speech (TTS) voice playback with per-message controls and playback speed adjustment.
- **Conversation management**: Search across messages, pin conversations, rename, delete, and export transcripts to plain text.
- **Appearance**: Follows Material Design 3 guidelines with light, dark, and system themes, custom font scaling, and dynamic splash icon.

## Security & Network Model

- **API key storage**: Stored exclusively on-device in `EncryptedSharedPreferences` backed by the Android KeyStore (`AES-256-GCM`), with `android:allowBackup="false"`. Keys are never logged and never included in backups.
- **Network security**: Enforces TLS/HTTPS by default for all remote endpoints. Cleartext HTTP is strictly constrained to loopback and private local network ranges (`localhost`, `127.0.0.1`, `10.0.2.2`, `*.local`, `*.lan`, `*.home`, `*.internal`) for self-hosted development and offline inference. Remote endpoints require HTTPS.
- **Direct communication**: Requests go straight to the configured provider endpoint with no third-party tracking or telemetry.

## Requirements

- Android 8.0+ (API level 26 minimum, API level 35 target)
- JDK 17
- Gradle 8.13+ / Android Gradle Plugin 8.9+

## Building and Testing

Build debug APK:
```bash
./gradlew :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/L0-debug.apk`

Run local JVM unit and Robolectric tests:
```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:testReleaseUnitTest
```

Run Android Lint:
```bash
./gradlew :app:lintDebug
```

Build minified release APK:
```bash
./gradlew :app:assembleRelease
```

## Release Configuration

GitHub Actions workflows:
- **CI** (`.github/workflows/ci.yml`): Runs lint, unit tests, and builds `L0-debug.apk` on pushes and pull requests to `main`.
- **Release** (`.github/workflows/release.yml`): Triggered by pushing a version tag (e.g. `v0.0.1`) or manual dispatch. Runs release unit tests, lint, signs the release APK, verifies signature with `apksigner`, computes SHA-256 checksums, and attaches `L0-<version>.apk` to the GitHub release.

For local signed release builds, configure `keystore.properties` at the repository root with:
```properties
storeFile=/path/to/release.keystore
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```
If `keystore.properties` is absent, release builds remain unsigned.
