# Changelog

All notable changes to L0 are documented here.

## [0.0.1] - 2026-10-02

### Initial Release

- **Streaming Conversations**: Token-level response streaming with cooperative cancellation (stop button), retry on error, message editing with resend, and draft staging.
- **Answer Regeneration & Versioning**: Re-generating an assistant response stores prior variations in local history with in-message version pagination.
- **Provider Support (BYOK)**:
  - Direct integration with Google Gemini via API key.
  - OpenAI-compatible chat completions endpoint support with model listing (`/models`) and connection test capabilities.
  - Quick-switch provider switcher in the top bar with saved profile management.
  - Local and self-hosted model support (e.g. Ollama, LM Studio) over private/loopback network addresses (`localhost`, `127.0.0.1`, `10.0.2.2`, `*.local`, `*.lan`, `*.home`, `*.internal`).
  - Collapsible reasoning/thinking display when providers return `reasoning_content`.
- **Follow-Up Suggestions**: Automatic, contextual follow-up chips after successful answers, with natural-language refusal and error detection. Tapping a suggestion populates the composer without auto-sending.
- **Web Search Grounding**: Optional DuckDuckGo web search integration per conversation with source citations listed alongside generated answers.
- **Multimodal Attachments**:
  - Image attachments via camera or photo picker with automatic downscaling (max dimension 1280px) and memory-safe compression.
  - Plain-text document attachments (Markdown, JSON, code up to 100 KB) inlined as context.
- **Voice Capabilities**: Voice speech recognition input via Android `SpeechRecognizer` and text-to-speech (TTS) playback with adjustable speed and per-message playback controls.
- **Local Conversation History**: Offline SQLite storage via Room database supporting conversation pinning, search, renaming, deletion, and transcript export.
- **Privacy & Security**:
  - API keys stored in `EncryptedSharedPreferences` backed by the Android KeyStore (`AES-256-GCM`) with `allowBackup="false"`.
  - Keys are never logged, never synced to third-party servers, and sent only to the configured provider endpoint.
  - Strict HTTPS by default, permitting cleartext HTTP exclusively for local development and private network ranges.
- **Appearance & Design**: Material Design 3 interface with dynamic theming (system, light, dark), font scaling, and custom minimal L0 geometric adaptive launcher icon.
- **Release Automation**: Automated GitHub Actions CI and release workflows for testing, linting, R8 minification, signature verification, and checksum generation.
