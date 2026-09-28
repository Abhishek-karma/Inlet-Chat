package com.assistant.app.voice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.concurrent.atomic.AtomicLong

/**
 * One selectable speech voice.
 *
 * [id] is the stable key persisted in preferences: engine, voice name and
 * locale joined, so it survives a restart and cannot collide across engines.
 * [isNatural] marks Google's higher-quality on-device voices, which settings
 * lists first.
 */
data class VoiceOption(
    val id: String,
    val label: String,
    val locale: String,
    val isNatural: Boolean,
) {
    companion object {
        const val NATURAL_QUALITY = Voice.QUALITY_VERY_HIGH

        fun from(engine: String, voice: Voice): VoiceOption {
            val locale = voice.locale
            val region = locale.displayCountry
            return VoiceOption(
                id = "$engine:${voice.name}:${locale.toLanguageTag()}",
                label = if (region.isNullOrBlank()) {
                    locale.displayLanguage
                } else {
                    "${locale.displayLanguage} ($region)"
                },
                locale = locale.toLanguageTag(),
                isNatural = voice.quality >= NATURAL_QUALITY && !voice.isNetworkConnectionRequired,
            )
        }

        /** The engine's own voice name, the only thing telling two voices apart. */
        fun shortName(id: String): String =
            id.substringAfter(':').substringBeforeLast(':')
    }
}

/**
 * Text-to-speech of completed assistant messages, on top of the normal text
 * pipeline. If TTS is unavailable or fails, the chat simply stays silent.
 *
 * The platform work is behind [Engine] so tests inject a fake.
 */
class VoiceOutput(private val engine: Engine) {

    interface Engine {
        fun isAvailable(): Boolean

        /**
         * Delivers the selectable voices once the engine has initialized, to
         * [onReady] on the main thread. Empty when no engine supplies a voice.
         */
        fun voices(onReady: (List<VoiceOption>) -> Unit)

        /**
         * Speaks [text] at [speechRate] (1.0 = normal) using [voiceId] when it
         * still exists, replacing any utterance in flight. [onDone] runs
         * exactly once per call — after playback, or immediately when the
         * utterance could not be played. [stop] does not invoke it.
         */
        fun speak(text: String, speechRate: Float, voiceId: String?, onDone: () -> Unit)

        fun stop()
    }

    val isAvailable: Boolean get() = engine.isAvailable()

    fun voices(onReady: (List<VoiceOption>) -> Unit) = engine.voices(onReady)

    fun speak(text: String, speechRate: Float = 1.0f, voiceId: String? = null, onDone: () -> Unit) =
        engine.speak(text, speechRate, voiceId, onDone)

    fun stop() = engine.stop()

    /** An engine for devices (and Robolectric tests) without TTS. */
    companion object {
        fun unavailable(): VoiceOutput = VoiceOutput(object : Engine {
            override fun isAvailable(): Boolean = false
            override fun voices(onReady: (List<VoiceOption>) -> Unit) = onReady(emptyList())
            override fun speak(text: String, speechRate: Float, voiceId: String?, onDone: () -> Unit) = onDone()
            override fun stop() = Unit
        })
    }
}

/**
 * [VoiceOutput.Engine] over [TextToSpeech], initialized lazily on first use.
 *
 * Google's TTS is preferred because it ships the Natural voices. It is a
 * user-installed app and may be missing or have no downloaded language, so the
 * engine falls back to the device default automatically — voice output never
 * depends on Google being present, and the user never picks an engine, only a
 * voice.
 *
 * Until initialization finishes (or if it fails) [speak] degrades to an
 * immediate [onDone] so the UI never sticks in a speaking state.
 */
class AndroidVoiceOutput(context: Context) : VoiceOutput.Engine {

    private val appContext = context.applicationContext

    private val utteranceIds = AtomicLong()

    /** Set once initialization finished; false means the engine is unusable. */
    @Volatile
    private var ready: Boolean? = null

    private var playingDone: (() -> Unit)? = null

    /** The engine package currently bound, or null for the device default. */
    private var boundEngine: String? = null

    private var fellBackToDefault = false

    private var tts: TextToSpeech? = null

    override fun isAvailable(): Boolean {
        if (ready != null) return ready == true
        // Synchronous probe: any installed activity answering the TTS
        // data-check intent. Full init refines this on first use.
        val check = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
        return appContext.packageManager.queryIntentActivities(check, 0).isNotEmpty()
    }

    override fun voices(onReady: (List<VoiceOption>) -> Unit) {
        val existing = tts
        if (existing != null && ready == true) {
            onReady(optionsOf(existing))
            return
        }
        bind { initialized -> onReady(if (initialized == null) emptyList() else optionsOf(initialized)) }
    }

    override fun speak(text: String, speechRate: Float, voiceId: String?, onDone: () -> Unit) {
        playingDone = null
        if (ready == false) {
            onDone()
            return
        }
        if (tts == null) bind()
        val engine = tts
        if (engine == null || ready != true) {
            // Not initialized yet: nothing is speaking, so report done rather
            // than leave the UI stuck in a speaking state.
            onDone()
            return
        }
        playingDone = onDone
        applyVoice(engine, voiceId)
        val id = utteranceIds.incrementAndGet().toString()
        engine.setSpeechRate(speechRate)
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            playingDone = null
            onDone()
        }
    }

    override fun stop() {
        playingDone = null
        tts?.stop()
    }

    /**
     * Selects [voiceId] when the engine still has it. A voice whose language
     * pack was removed is ignored instead of failing the utterance.
     */
    private fun applyVoice(engine: TextToSpeech, voiceId: String?) {
        if (voiceId.isNullOrBlank()) return
        val target = engine.voices?.firstOrNull {
            VoiceOption.from(boundEngine.orEmpty(), it).id == voiceId
        } ?: return
        engine.voice = target
    }

    /** Natural voices first, then by label, so the list is stable. */
    private fun optionsOf(engine: TextToSpeech): List<VoiceOption> {
        val engineId = boundEngine.orEmpty()
        val options = engine.voices.orEmpty().map { VoiceOption.from(engineId, it) }
        val perLocale = options.groupingBy { it.locale }.eachCount()
        val seen = mutableSetOf<String>()
        return options.asSequence()
            .filter { seen.add(it.id) }
            .sortedWith(compareByDescending<VoiceOption> { it.isNatural }.thenBy { it.label })
            .map { option ->
                // Engines identify voices by raw name, so two voices of one
                // language are otherwise indistinguishable in the list.
                if ((perLocale[option.locale] ?: 0) > 1) {
                    option.copy(label = "${option.label} · ${VoiceOption.shortName(option.id)}")
                } else {
                    option
                }
            }
            .toList()
    }

    private fun bind(onInit: ((TextToSpeech?) -> Unit)? = null) {
        if (tts != null) {
            onInit?.invoke(tts)
            return
        }
        create(preferredEngine()) { onInitialized(it, onInit) }
    }

    private fun create(enginePackage: String?, onInit: (Int) -> Unit) {
        boundEngine = enginePackage
        val listener = TextToSpeech.OnInitListener { status -> onInit(status) }
        tts = if (enginePackage == null) {
            TextToSpeech(appContext, listener)
        } else {
            TextToSpeech(appContext, listener, enginePackage)
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finishCurrent()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finishCurrent()
        })
    }

    private fun onInitialized(status: Int, onInit: ((TextToSpeech?) -> Unit)?) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            ready = false
            finishCurrent()
            onInit?.invoke(null)
            return
        }
        // Google without a downloaded Natural voice is worse than the device
        // default, so switch over once and stay there.
        if (boundEngine == GOOGLE_TTS_PACKAGE && !fellBackToDefault && hasNoNaturalVoice(engine)) {
            fellBackToDefault = true
            rebindToDefault(onInit)
            return
        }
        ready = true
        onInit?.invoke(engine)
    }

    private fun rebindToDefault(onInit: ((TextToSpeech?) -> Unit)?) {
        tts?.shutdown()
        tts = null
        create(enginePackage = null) { status ->
            val engine = tts
            val usable = status == TextToSpeech.SUCCESS && engine != null
            ready = usable
            if (!usable) finishCurrent()
            onInit?.invoke(if (usable) engine else null)
        }
    }

    private fun hasNoNaturalVoice(engine: TextToSpeech): Boolean =
        engine.voices.orEmpty().none {
            !it.isNetworkConnectionRequired && it.quality >= VoiceOption.NATURAL_QUALITY
        }

    private fun preferredEngine(): String? =
        if (isInstalled(GOOGLE_TTS_PACKAGE)) GOOGLE_TTS_PACKAGE else null

    private fun isInstalled(packageName: String): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    private fun finishCurrent() {
        val done = playingDone
        playingDone = null
        done?.invoke()
    }

    private companion object {
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
    }
}
