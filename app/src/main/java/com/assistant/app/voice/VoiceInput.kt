package com.assistant.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Only [NoMatch] gets special UI treatment; the rest degrade to text. */
enum class VoiceInputError { NoMatch, MicUnavailable, PermissionDenied, Busy, Unknown }

sealed interface VoiceInputEvent {
    /** Partial text arrived; recognition is still running. */
    data object Transcribing : VoiceInputEvent

    data class Transcript(val text: String) : VoiceInputEvent

    data class Failed(val kind: VoiceInputError) : VoiceInputEvent
}

/**
 * Speech-to-text as a pure input path into the normal chat pipeline. Recognition
 * runs only while the user explicitly asked for it: no background listening, no
 * wake word, no service.
 *
 * The platform work is behind [Engine] so tests inject a fake.
 */
class VoiceInput(private val engine: Engine) {

    interface Engine {
        fun isAvailable(): Boolean

        /**
         * Starts one recognition attempt, delivering events on the main thread.
         * Calling [start] again before an attempt finishes replaces it.
         */
        fun start(onEvent: (VoiceInputEvent) -> Unit)

        fun stop()
    }

    val isAvailable: Boolean get() = engine.isAvailable()

    fun start(onEvent: (VoiceInputEvent) -> Unit) = engine.start(onEvent)

    fun stop() = engine.stop()

    /** An engine for devices (and Robolectric tests) without recognition. */
    companion object {
        fun unavailable(): VoiceInput = VoiceInput(object : Engine {
            override fun isAvailable(): Boolean = false
            override fun start(onEvent: (VoiceInputEvent) -> Unit) = Unit
            override fun stop() = Unit
        })
    }
}

/**
 * [VoiceInput.Engine] over [SpeechRecognizer], created on first use and reused.
 * Only the attempt whose callback is current delivers events, so a cancelled
 * attempt cannot leak results.
 */
class AndroidVoiceInput(context: Context) : VoiceInput.Engine {

    private val appContext = context.applicationContext

    private var recognizer: SpeechRecognizer? = null

    /** Callback of the current attempt; set by [start], cleared by events/stop. */
    private var callback: ((VoiceInputEvent) -> Unit)? = null

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun start(onEvent: (VoiceInputEvent) -> Unit) {
        stop()
        callback = onEvent
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(Listener())
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        sr.startListening(intent)
    }

    override fun stop() {
        callback = null
        recognizer?.stopListening()
    }

    private inner class Listener : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            callback?.invoke(VoiceInputEvent.Transcribing)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            val event = if (text.isBlank()) {
                VoiceInputEvent.Failed(VoiceInputError.NoMatch)
            } else {
                VoiceInputEvent.Transcript(text)
            }
            deliver(event)
        }

        override fun onError(error: Int) {
            val event = VoiceInputEvent.Failed(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    -> VoiceInputError.NoMatch

                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceInputError.PermissionDenied
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceInputError.Busy
                    else -> VoiceInputError.Unknown
                },
            )
            deliver(event)
        }

        // Required by the interface but not part of our minimal flow.
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        /** One-shot delivery to the current attempt, then the attempt is over. */
        private fun deliver(event: VoiceInputEvent) {
            val active = callback
            callback = null
            active?.invoke(event)
        }
    }
}
