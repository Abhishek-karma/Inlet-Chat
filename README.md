# Inlet Chat

[![Release](https://github.com/Abhishek-karma/Repo/actions/workflows/release.yml/badge.svg)](https://github.com/Abhishek-karma/Repo/actions/workflows/release.yml)

A minimal Android AI chat application focused on reliable, private conversation with OpenAI-compatible providers.

## Overview

Inlet Chat is a bring-your-own-key (BYOK) chat client for Android. It provides token-level streaming, conversation history, voice input and output, attachments, and optional web-grounded answers — with API keys stored only on the device.

Inlet Chat is a chat application, not an agent platform: it has no autonomous agent loop, no background execution, and no local model inference.

## Features

**Conversation**

- Token-level streaming responses with stop control
- Retry, regenerate, and edit-and-resend
- Answer versions — regenerating keeps previous answers, switchable on the message
- Follow-up suggestion chips after each answer (fail silently)
- Conversation history with pin, rename, delete, and plain-text sharing
- Markdown rendering: headings, lists, tables, code blocks, links, and emphasis, plus offline LaTeX and Mermaid diagrams

**Providers**

- Multiple saved OpenAI-compatible providers with a top-bar switcher
- Configurable name, base URL, API key, and model, with a connection test
- Collapsible model reasoning when the provider streams `reasoning_content`, with a settings toggle
- Optional web search grounding, no API key required — a SearXNG-compatible endpoint if you configure one, otherwise a keyless fallback — toggled per conversation, with sources listed under the answer

**Attachments and voice**

- Image and text-file attachments — images are downscaled and sent as data-URL parts, text files are inlined as context
- Voice input via `SpeechRecognizer` and voice output via TTS, with playback speed, auto-play, and per-message playback controls

**Appearance**

- System, light, and dark themes with a text-size setting
- Time-of-day greeting on the home screen

## Architecture

Single-module application built with Kotlin, Jetpack Compose (Material 3), Room, DataStore, OkHttp, and coroutines.

```text
Compose UI
    ↓
ViewModel
    ↓
Chat Logic
    ↓
LLM Provider
    ↓
OpenAI-compatible API
```

## Requirements

- JDK 17 or newer (JDK 21 supported)
- Android SDK with compileSdk 35

## Build and Test

```bash
./gradlew :app:assembleDebug        # debug build
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:lintDebug            # lint
./gradlew :app:assembleRelease      # minified release build
```

The release build applies R8 minification and resource shrinking and is produced unsigned. Add a signing configuration before installing or distributing it.

## Configuration

| Setting | Value |
| --- | --- |
| Application ID | `com.aistudio.inletchat.wzptbq` |
| Namespace | `com.assistant.app` |
| minSdk / targetSdk | 26 / 35 |
| Version | 1.0.0 (versionCode 1) |
| JVM target | 11 |

## Security and Privacy

- API keys are configured in the app's settings and stored locally in `EncryptedSharedPreferences` (`androidx.security.crypto`, backed by Android KeyStore AES-256-GCM, with `allowBackup=false`).
- Keys are never logged, never written to plain DataStore or Room, and never committed to version control.
- Keys are sent only to the configured provider endpoint over TLS/HTTPS.
- The app contains no hardcoded provider keys, no analytics, and no third-party services.

## Testing

The automated suite (unit and UI tests, lint) covers chat, streaming, stop/retry, version switching, follow-up suggestions, history pin/rename, provider configuration, appearance, error mapping, persistence, and voice state.

The following behaviors additionally require verification on a real device:

<details>
<summary>Manual device checklist</summary>

- [ ] First launch
- [ ] Provider setup
- [ ] Normal and long responses
- [ ] Stop response
- [ ] Network disabled during generation
- [ ] Invalid API key
- [ ] Invalid endpoint
- [ ] App backgrounded during generation
- [ ] App restored after process death
- [ ] Conversation reopening
- [ ] Voice input
- [ ] Voice output
- [ ] Light and dark themes
- [ ] Keyboard behavior

</details>

## Release

A release is cut only after the full release checklist passes, including real-device smoke testing.

Pushing a tag matching `v*` (for example `v1.0.0`) triggers the release workflow in [`.github/workflows/release.yml`](.github/workflows/release.yml). The workflow runs the unit tests, builds the minified release APK, and publishes a GitHub release with the APK attached.
