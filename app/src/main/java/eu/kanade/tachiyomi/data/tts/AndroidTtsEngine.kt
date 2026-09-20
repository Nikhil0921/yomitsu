package eu.kanade.tachiyomi.data.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.tts.engine.TtsEngine
import mihon.domain.tts.engine.TtsEngineInfo
import mihon.domain.tts.engine.TtsFocusEvent
import mihon.domain.tts.engine.TtsVoiceInfo
import mihon.domain.tts.service.TtsVoicePreferences
import mihon.domain.tts.service.TtsVoiceSelection
import mihon.domain.tts.service.resolveVoiceSelection
import tachiyomi.core.common.util.system.logcat
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class AndroidTtsEngine(
    private val context: Context,
    private val voicePreferences: TtsVoicePreferences,
) : TtsEngine {

    private val mutex = Mutex()

    private val tts = AtomicReference<TextToSpeech?>(null)

    private val pendingUtterances = HashMap<String, CompletableDeferred<Boolean>>()

    private var audioFocusRequest: AudioFocusRequest? = null

    private var speechRate: Float = 1f

    private var pitch: Float = 1f

    private var enginePackage: String = voicePreferences.ttsEnginePackage().get()

    private var voiceName: String = voicePreferences.ttsVoiceName().get()

    private var languageTag: String = voicePreferences.ttsLanguageTag().get()

    private var activeEnginePackage: String = ""

    override var onFocusEvent: ((TtsFocusEvent) -> Unit)? = null

    override suspend fun initialize(): Boolean = mutex.withLock {
        val existing = tts.get()
        if (existing != null) {
            applyVoiceConfig(existing)
            return@withLock true
        }

        val readiness = CompletableDeferred<Boolean>()
        val createPackage = enginePackage
        val engine = withContext(Dispatchers.Main) {
            val initListener = TextToSpeech.OnInitListener { status ->
                readiness.complete(status == TextToSpeech.SUCCESS)
            }
            if (createPackage.isNotEmpty()) {
                TextToSpeech(context.applicationContext, initListener, createPackage)
            } else {
                TextToSpeech(context.applicationContext, initListener)
            }.apply {
                setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            if (eu.kanade.tachiyomi.BuildConfig.DEBUG) {
                                logcat(LogPriority.DEBUG) {
                                    "TTS onStart id=$utteranceId startNs=${System.nanoTime()}"
                                }
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            completeUtterance(utteranceId, success = true)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            completeUtterance(utteranceId, success = false)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            completeUtterance(utteranceId, success = false)
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            completeUtterance(utteranceId, success = false)
                        }
                    },
                )
                setSpeechRate(speechRate)
                setPitch(pitch)
            }
        }

        val awaitStart = if (eu.kanade.tachiyomi.BuildConfig.DEBUG) System.nanoTime() else 0L
        val success = try {
            readiness.await()
        } catch (e: CancellationException) {
            withContext(Dispatchers.Main) { engine.shutdown() }
            throw e
        }
        if (eu.kanade.tachiyomi.BuildConfig.DEBUG) {
            logcat(LogPriority.DEBUG) {
                "TTS init readiness awaitMs=${(System.nanoTime() - awaitStart) / 1_000_000}"
            }
        }
        if (!success) {
            withContext(Dispatchers.Main) { engine.shutdown() }
            return@withLock false
        }

        tts.set(engine)
        activeEnginePackage = createPackage
        applyVoiceConfig(engine)
        true
    }

    override suspend fun speak(utteranceId: String, text: String): Boolean {
        val engine = tts.get() ?: return false

        val completion = CompletableDeferred<Boolean>()
        synchronized(pendingUtterances) { pendingUtterances[utteranceId] = completion }

        try {
            val accepted = withContext(Dispatchers.Main) {
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
            if (accepted != TextToSpeech.SUCCESS) return false

            return try {
                completion.await()
            } finally {
                if (!completion.isCompleted) stop()
            }
        } finally {
            // Remove only OUR entry: a relaunch may have registered a newer
            // completion under the same id; removing it unconditionally would
            // misroute the new dispatch's callbacks.
            synchronized(pendingUtterances) {
                if (pendingUtterances[utteranceId] === completion) pendingUtterances.remove(utteranceId)
            }
        }
    }

    override fun setSpeechRate(rate: Float) {
        speechRate = rate
        tts.get()?.setSpeechRate(rate)
    }

    override fun setPitch(value: Float) {
        pitch = value
        tts.get()?.setPitch(value)
    }

    override fun setEnginePackage(pkg: String) {
        if (pkg == enginePackage) return
        enginePackage = pkg
        if (activeEnginePackage != pkg) shutdown()
    }

    override suspend fun getEngines(): List<TtsEngineInfo> = withContext(Dispatchers.Main) {
        val engine = tts.get() ?: return@withContext emptyList()
        val defaultPkg = engine.defaultEngine
        engine.engines.orEmpty().map {
            TtsEngineInfo(
                packageName = it.name,
                label = it.label,
                isSystemDefault = it.name == defaultPkg,
            )
        }
    }

    override suspend fun getVoices(): List<TtsVoiceInfo> = withContext(Dispatchers.Main) {
        val engine = tts.get() ?: return@withContext emptyList()
        engine.voices.orEmpty().map {
            TtsVoiceInfo(
                name = it.name,
                languageTag = it.locale.toLanguageTag(),
                displayName = it.name,
                quality = it.quality,
                latency = it.latency,
                features = it.features.orEmpty().toList(),
                networkRequired = it.isNetworkConnectionRequired,
            )
        }
    }

    override fun acquireFocus() {
        val manager = audioManager() ?: return
        val request = audioFocusRequest ?: buildFocusRequest().also { audioFocusRequest = it }
        manager.requestAudioFocus(request)
    }

    override fun abandonFocus() {
        val request = audioFocusRequest ?: return
        audioManager()?.abandonAudioFocusRequest(request)
    }

    override fun stop() {
        val engine = tts.get() ?: return
        failPendingUtterances()
        engine.stop()
    }

    override fun shutdown() {
        val engine = tts.getAndSet(null) ?: return
        failPendingUtterances()
        abandonFocus()
        audioFocusRequest = null
        engine.shutdown()
    }

    private fun applyVoiceConfig(engine: TextToSpeech) {
        val debug = eu.kanade.tachiyomi.BuildConfig.DEBUG
        val cfgStart = if (debug) System.nanoTime() else 0L
        voiceName = voicePreferences.ttsVoiceName().get()
        languageTag = voicePreferences.ttsLanguageTag().get()
        val voicesStart = if (debug) System.nanoTime() else 0L
        val voices = engine.voices.orEmpty()
        val voicesMs = if (debug) (System.nanoTime() - voicesStart) / 1_000_000 else 0L
        val availableVoiceNames = voices.map { it.name }.toSet()
        val availableLanguageTags = voices.map { it.locale.toLanguageTag() }.toSet()
        val enginesStart = if (debug) System.nanoTime() else 0L
        val availableEnginePackages = engine.engines.orEmpty().map { it.name }.toSet()
        val enginesMs = if (debug) (System.nanoTime() - enginesStart) / 1_000_000 else 0L
        val applyStart = if (debug) System.nanoTime() else 0L

        when (
            resolveVoiceSelection(
                selectedEnginePackage = enginePackage,
                selectedVoiceName = voiceName,
                selectedLanguageTag = languageTag,
                availableEnginePackages = availableEnginePackages,
                availableVoiceNames = availableVoiceNames,
                availableLanguageTags = availableLanguageTags,
            )
        ) {
            TtsVoiceSelection.SystemDefault -> {
                val defaultVoice = engine.defaultVoice
                if (defaultVoice == null) {
                    logcat(LogPriority.DEBUG) { "TTS system default fallback: no default voice" }
                } else {
                    val result = engine.setVoice(defaultVoice)
                    if (result == TextToSpeech.SUCCESS) {
                        logcat(LogPriority.DEBUG) { "TTS default voice restored name=${defaultVoice.name}" }
                    } else {
                        logcat(LogPriority.DEBUG) {
                            "TTS default voice apply failed name=${defaultVoice.name} result=$result"
                        }
                    }
                }
            }
            is TtsVoiceSelection.Voice -> {
                val voice = voices.firstOrNull { it.name == voiceName }
                if (voice == null) {
                    logcat(LogPriority.DEBUG) { "TTS voice fallback: voice unavailable name=$voiceName" }
                } else {
                    val result = engine.setVoice(voice)
                    if (result == TextToSpeech.SUCCESS) {
                        logcat(LogPriority.DEBUG) { "TTS voice applied name=${voice.name}" }
                    } else {
                        logcat(LogPriority.DEBUG) { "TTS voice apply failed name=${voice.name} result=$result" }
                    }
                }
            }
            is TtsVoiceSelection.Language -> {
                val result = engine.setLanguage(Locale.forLanguageTag(languageTag))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    logcat(LogPriority.DEBUG) { "TTS language fallback: unsupported tag=$languageTag result=$result" }
                } else {
                    logcat(LogPriority.DEBUG) { "TTS language applied tag=$languageTag" }
                }
            }
        }
        if (debug) {
            val now = System.nanoTime()
            logcat(LogPriority.DEBUG) {
                "TTS voicecfg voicesMs=$voicesMs enginesMs=$enginesMs " +
                    "resolveApplyMs=${(now - applyStart) / 1_000_000} totalMs=${(now - cfgStart) / 1_000_000}"
            }
        }
    }

    private fun completeUtterance(utteranceId: String?, success: Boolean) {
        if (utteranceId == null) return
        val completion = synchronized(pendingUtterances) { pendingUtterances[utteranceId] } ?: return
        completion.complete(success)
    }

    private fun failPendingUtterances() {
        synchronized(pendingUtterances) {
            pendingUtterances.values.forEach { it.complete(false) }
            pendingUtterances.clear()
        }
    }

    private fun audioManager(): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun buildFocusRequest(): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                val event = when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> TtsFocusEvent.Regained
                    AudioManager.AUDIOFOCUS_LOSS -> TtsFocusEvent.PermanentLoss
                    else -> TtsFocusEvent.TransientLoss
                }
                onFocusEvent?.invoke(event)
            }
            .build()
}
