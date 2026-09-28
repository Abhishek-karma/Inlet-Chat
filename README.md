# Android AI Assistant

A minimal Android AI assistant focused on reliable conversation.

## Features

- Text chat
- Real streaming responses (token-level, with stop)
- Retry, regenerate, and edit-and-resend
- Answer versions: regenerating keeps previous answers switchable on the message
- Follow-up suggestion chips after each answer (silent on failure)
- Conversation history with pin, rename, and delete
- Multiple saved OpenAI-compatible providers with a top-bar switcher
- Configurable provider (name, base URL, API key, model)
- Image and text-file attachments (downscaled/copied locally; images sent as
  data-URL parts, text files inlined as context)
- Real model reasoning shown in a collapsible section when the provider
  streams it (`reasoning_content`), with a settings toggle
- Voice upgrades: TTS speed, auto-play toggle, manual per-message playback,
  markdown stripped before speaking
- Optional web search: configure a SearXNG/Brave/Tavily-compatible service in
  settings, toggle per conversation; results ground the answer and sources are
  listed under it
- Connection test for provider configuration
- Voice input (SpeechRecognizer) and optional voice output (TTS)
- Light/dark appearance (System / Light / Dark) and text-size setting
- Richer message markdown: headings, list items, and tables alongside code
  blocks, links, and emphasis; offline LaTeX and Mermaid diagram rendering
- Time-of-day greeting on the home screen
- Share a conversation as plain text from history

The application does not use an autonomous agent architecture.

## Architecture

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

## Documentation

- `AGENTS.md` — development rules
- `design.md` — visual and interaction specification

## Building

Requirements: JDK 21, Android SDK (compileSdk 35).

```text
./gradlew :app:assembleDebug          # debug build
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:lintDebug              # lint
./gradlew :app:assembleRelease        # minified release build
```

The release build uses R8 minification and resource shrinking. It is produced
unsigned — no signing configuration is included for V1. Add a signing config
before installing a release build on a device.

## Configuration

- applicationId: `com.assistant.app`
- minSdk: 26, targetSdk: 35
- version: 1.0.0 (versionCode 1)

## Provider Configuration

In the app's settings screen, configure an OpenAI-compatible provider:

1. Set a display name, base URL (e.g. `https://api.openai.com/v1`), API key, and model.
2. Use the connection test to verify the configuration before chatting.
3. The API key is stored locally in EncryptedSharedPreferences
   (`androidx.security.crypto`, with `allowBackup=false`). It is never logged,
   never committed, and only sent to the configured provider endpoint.

## Manual Device Verification

This build environment had no Android device or emulator attached. The
automated suite (unit/UI tests, lint) covers chat, streaming, stop/retry,
version switching, suggestions, history pin/rename, provider configuration,
appearance, error mapping, persistence, and voice state.
The following items require a real device and remain:

**USER-SIDE VERIFICATION REQUIRED**

- [ ] first launch
- [ ] provider setup
- [ ] normal response
- [ ] long response
- [ ] stop response
- [ ] network disabled during generation
- [ ] invalid API key
- [ ] invalid endpoint
- [ ] app backgrounded during generation
- [ ] app restored after process death
- [ ] conversation reopening
- [ ] voice input
- [ ] voice output
- [ ] light theme
- [ ] dark theme
- [ ] keyboard behavior

## Development

Read `AGENTS.md` first, then `design.md` for UI work.

Follow those documents before implementing features.

Do not add features or architecture outside the documented scope without a clear product requirement.

## Release

A release is created only after the checklist in `AGENTS.md` passes, including real-device smoke testing.
