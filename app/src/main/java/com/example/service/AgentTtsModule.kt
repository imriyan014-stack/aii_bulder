package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

private const val TAG = "AgentTtsModule"

class AgentTtsModule(
    context: Context,
    private val onReadyChanged: (isReady: Boolean, languageLabel: String) -> Unit,
    private val onSpeechStarted: (text: String) -> Unit,
    private val onSpeechCompleted: () -> Unit,
    private val onSpeechError: (errorMessage: String) -> Unit
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var tts: TextToSpeech? = null
    private var isEngineReady = false
    private var pendingUtterance: String? = null
    private var speechWatchdogRunnable: Runnable? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Default to DEEP_JARVIS (Deep & Clear Male Voice) so the agent always sounds like a clear, resonant man
    var speechRate: Float = 0.98f
        private set
    var pitch: Float = 0.72f
        private set
    var voiceProfile: String = "DEEP_JARVIS"
        private set
    var isMuted: Boolean = false
    private var hasNativeMaleVoiceForCurrentLocale: Boolean = false

    init {
        initializeEngine()
    }

    private fun initializeEngine() {
        try {
            tts = TextToSpeech(
                appContext,
                { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        configureLanguageAndListener()
                    } else {
                        initializeFallbackDefaultEngine()
                    }
                },
                "com.google.android.tts"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Google TTS init failed (${e.message}), trying default engine")
            initializeFallbackDefaultEngine()
        }
    }

    private fun initializeFallbackDefaultEngine() {
        try {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    configureLanguageAndListener()
                } else {
                    isEngineReady = false
                    mainHandler.post {
                        onReadyChanged(false, "TTS অনুপলব্ধ")
                    }
                }
            }
        } catch (e: Exception) {
            isEngineReady = false
            mainHandler.post {
                onReadyChanged(false, "TTS ত্রুটি")
            }
        }
    }

    private fun configureLanguageAndListener() {
        val engine = tts ?: return

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        engine.setAudioAttributes(audioAttrs)

        val bnBd = Locale.forLanguageTag("bn-BD")
        val bnIn = Locale.forLanguageTag("bn-IN")
        val hiIn = Locale.forLanguageTag("hi-IN")

        val bdStatus = engine.setLanguage(bnBd)
        val activeLabel = if (bdStatus != TextToSpeech.LANG_MISSING_DATA &&
            bdStatus != TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            "বাংলা (bn-BD)"
        } else {
            val inStatus = engine.setLanguage(bnIn)
            if (inStatus != TextToSpeech.LANG_MISSING_DATA &&
                inStatus != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                "বাংলা (bn-IN)"
            } else {
                val hiStatus = engine.setLanguage(hiIn)
                if (hiStatus != TextToSpeech.LANG_MISSING_DATA &&
                    hiStatus != TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    "हिन्दी / English"
                } else {
                    engine.setLanguage(Locale.US)
                    "English (US)"
                }
            }
        }

        hasNativeMaleVoiceForCurrentLocale = selectBestMaleVoiceForLocale(engine, engine.voice?.locale ?: bnBd)

        engine.setSpeechRate(speechRate)
        engine.setPitch(computeEffectiveMasculinePitch())

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // Handled immediately when speak() is invoked
            }

            override fun onDone(utteranceId: String?) {
                cancelSpeechWatchdog()
                abandonAudioFocus()
                mainHandler.post {
                    onSpeechCompleted()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                cancelSpeechWatchdog()
                abandonAudioFocus()
                mainHandler.post {
                    onSpeechError("ভয়েস আউটপুট ত্রুটি")
                    onSpeechCompleted()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                cancelSpeechWatchdog()
                abandonAudioFocus()
                mainHandler.post {
                    onSpeechError("ভয়েস আউটপুট ত্রুটি ($errorCode)")
                    onSpeechCompleted()
                }
            }
        })

        isEngineReady = true
        mainHandler.post {
            onReadyChanged(true, activeLabel)
            pendingUtterance?.let { queued ->
                pendingUtterance = null
                speak(queued, forceSpeakWhenMuted = true)
            }
        }
    }

    /**
     * Prioritizes deep, clear MALE voices across `bn-BD`, `bn-IN`, `hi-IN`, and `en-US` / `en-IN` / `en-GB`.
     * Returns `true` if an explicit male voice pack was found on the device, or `false` if only a
     * default/gender-neutral voice pack is installed (in which case formant/pitch lowering is applied).
     */
    private fun selectBestMaleVoiceForLocale(engine: TextToSpeech, targetLocale: Locale): Boolean {
        return try {
            val availableVoices = engine.voices ?: return false
            val langVoices = availableVoices.filter {
                it.locale.language.equals(targetLocale.language, ignoreCase = true)
            }
            if (langVoices.isEmpty()) return false

            fun getMaleScore(voice: Voice): Int {
                val n = voice.name.lowercase()
                val feat = (voice.features ?: emptySet()).joinToString(" ").lowercase()
                val combined = "$n $feat"

                val isExplicitFemale = combined.contains("female") ||
                    n.contains("-sfb") ||
                    n.contains("-sfg") ||
                    n.contains("-bnf") ||
                    n.contains("-bng") ||
                    n.contains("-hif") ||
                    n.contains("-hig") ||
                    n.contains("-enc") ||
                    n.contains("-enf")

                if (isExplicitFemale) return 0

                val isExplicitMale = combined.contains("male") ||
                    n.contains("-ban") ||
                    n.contains("-bin") ||
                    n.contains("-bnm") ||
                    n.contains("-bnn") ||
                    n.contains("-hid") ||
                    n.contains("-hie") ||
                    n.contains("-iom") ||
                    n.contains("-tpd") ||
                    n.contains("-end") ||
                    n.contains("-ene") ||
                    n.contains("-rjs") ||
                    n.contains("-gba") ||
                    n.contains("-gbd")

                return if (isExplicitMale) 100 else 50
            }

            val bestVoice: Voice? = langVoices.maxWithOrNull(
                compareBy<Voice> { getMaleScore(it) }
                    .thenBy { !it.isNetworkConnectionRequired }
                    .thenBy { it.quality }
                    .thenBy {
                        val n = it.name.lowercase()
                        when {
                            n.contains("natural") || n.contains("wavenet") || n.contains("neural") -> 3
                            n.contains("local") || n.contains("x-") -> 2
                            else -> 1
                        }
                    }
            )
            if (bestVoice != null) {
                engine.voice = bestVoice
                getMaleScore(bestVoice) >= 100
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun computeEffectiveMasculinePitch(): Float {
        return if (hasNativeMaleVoiceForCurrentLocale) {
            pitch.coerceIn(0.55f, 0.88f)
        } else {
            // If the device only has a single default speaker installed for Bengali/Hindi,
            // shift the pitch into the deep male baritone range (max 0.74f) so it never sounds female.
            pitch.coerceIn(0.55f, 0.74f)
        }
    }

    private fun ensureAudibleMediaVolume() {
        try {
            val maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val minTargetVol = (maxMusicVol * 0.45f).toInt().coerceAtLeast(2)
            if (currentMusicVol < minTargetVol) {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    minTargetVol,
                    0
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not adjust media stream volume: ${e.message}")
        }
    }

    private fun requestTransientAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioFocus request failed: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Transforms formal or structured text into natural, warm, human-like spoken conversation
     * so the agent never sounds like it is reading out written text, symbols, or bullet lists.
     */
    private fun humanizeSpokenText(rawText: String): String {
        var text = rawText
            // Strip code blocks so TTS never reads raw code syntax aloud
            .replace(Regex("```[\\s\\S]*?```"), " কোডটি আমি তৈরি করে সেভ করে রেখেছি স্যার। ")
            // Strip markdown symbols, bullets, numbered list prefixes, and technical brackets
            .replace(Regex("(?m)^\\s*[0-9]+\\.\\s*"), "")
            .replace(Regex("(?m)^\\s*[-•*]\\s*"), "")
            .replace("*", "")
            .replace("#", "")
            .replace("_", " ")
            .replace("`", "")
            // Remove parenthetical technical codes like (bn-BD), (X: 540, Y: 1100), (WIFI)
            .replace(Regex("\\([A-Za-z0-9_:\\-, .%/]+\\)"), "")
            // Replace robotic separators with natural spoken pauses
            .replace(" — ", ", ")
            .replace(" - ", ", ")
            .replace(" • ", ", ")
            .replace(" | ", ", ")
            .replace(":", ", ")
            // Convert formal bookish Bengali words into warm natural spoken Bengali
            .replace("সম্পন্ন হয়েছে", "করে দিয়েছি")
            .replace("সক্রিয় করা হয়েছে", "চালু করে দিয়েছি")
            .replace("নিষ্ক্রিয় করা হয়েছে", "বন্ধ করে দিয়েছি")
            .replace("অনুগ্রহ করে", "একটু")
            .replace("সংরক্ষণ করা হয়েছে", "সেভ করে রেখেছি")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (text.length > 360) {
            text = text.take(360).substringBeforeLast(' ') + "।"
        }
        return text
    }

    fun speak(rawText: String, forceSpeakWhenMuted: Boolean = false) {
        val cleanText = humanizeSpokenText(rawText)

        if (cleanText.isBlank()) return
        if (isMuted && !forceSpeakWhenMuted) return

        if (!isEngineReady || tts == null) {
            pendingUtterance = cleanText
            if (tts == null) {
                initializeEngine()
            }
            return
        }

        val engine = tts ?: return
        cancelSpeechWatchdog()

        ensureAudibleMediaVolume()
        requestTransientAudioFocus()

        // Dynamically match TTS locale to the language of the reply so Bengali, Hindi, and English sound authentic
        val hasBengaliScript = cleanText.any { it in '\u0980'..'\u09FF' }
        val hasDevanagariScript = cleanText.any { it in '\u0900'..'\u097F' }
        try {
            val targetLoc = when {
                hasBengaliScript -> Locale.forLanguageTag("bn-BD")
                hasDevanagariScript -> Locale.forLanguageTag("hi-IN")
                else -> Locale.US
            }
            val langRes = engine.setLanguage(targetLoc)
            if (hasBengaliScript && (langRes == TextToSpeech.LANG_MISSING_DATA || langRes == TextToSpeech.LANG_NOT_SUPPORTED)) {
                engine.setLanguage(Locale.forLanguageTag("bn-IN"))
            }
            hasNativeMaleVoiceForCurrentLocale = selectBestMaleVoiceForLocale(engine, targetLoc)
        } catch (_: Exception) {
        }

        engine.setSpeechRate(speechRate)
        engine.setPitch(computeEffectiveMasculinePitch())

        mainHandler.post {
            onSpeechStarted(cleanText)
        }

        val utteranceId = "cube_tts_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        val result = engine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            Log.w(TAG, "TTS speak returned ERROR, re-initializing engine")
            abandonAudioFocus()
            pendingUtterance = cleanText
            initializeEngine()
            mainHandler.post {
                onSpeechCompleted()
            }
            return
        }

        val estimatedDurationMs = (cleanText.length * 95L).coerceIn(3500L, 18000L)
        val watchdog = Runnable {
            abandonAudioFocus()
            onSpeechCompleted()
        }
        speechWatchdogRunnable = watchdog
        mainHandler.postDelayed(watchdog, estimatedDurationMs)
    }

    private fun cancelSpeechWatchdog() {
        speechWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        speechWatchdogRunnable = null
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.65f, 1.6f)
        tts?.setSpeechRate(speechRate)
    }

    fun setPitch(newPitch: Float) {
        pitch = newPitch.coerceIn(0.55f, 1.35f)
        tts?.setPitch(computeEffectiveMasculinePitch())
    }

    /**
     * Applies a preset MALE voice profile:
     * - "DEEP_JARVIS"   -> pitch = 0.72f, rate = 0.98f (Deep & Clear Male Jarvis voice - Default)
     * - "CLEAN_STUDIO"  -> pitch = 0.76f, rate = 1.00f (Crisp, clear male studio voice)
     * - "ULTRA_DEEP"    -> pitch = 0.62f, rate = 0.94f (Ultra-deep authoritative bass male voice)
     * - "NATURAL_HUMAN" -> pitch = 0.74f, rate = 1.00f (Warm, natural conversational male voice)
     */
    fun applyVoiceProfile(profileName: String) {
        voiceProfile = profileName
        when (profileName.uppercase()) {
            "DEEP_JARVIS" -> {
                setPitch(0.72f)
                setSpeechRate(0.98f)
            }
            "CLEAN_STUDIO" -> {
                setPitch(0.76f)
                setSpeechRate(1.00f)
            }
            "ULTRA_DEEP" -> {
                setPitch(0.62f)
                setSpeechRate(0.94f)
            }
            "NATURAL_HUMAN", "NATURAL" -> {
                setPitch(0.74f)
                setSpeechRate(1.00f)
            }
            else -> {
                setPitch(0.72f)
                setSpeechRate(0.98f)
            }
        }
    }

    fun stopSpeaking() {
        cancelSpeechWatchdog()
        abandonAudioFocus()
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        mainHandler.post {
            onSpeechCompleted()
        }
    }

    fun shutdown() {
        cancelSpeechWatchdog()
        abandonAudioFocus()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }
}
