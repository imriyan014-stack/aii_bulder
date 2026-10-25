package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.example.model.WakeWordPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceBridgeState(
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isMuted: Boolean = false,
    val ttsEnabled: Boolean = true,
    val isTtsReady: Boolean = false,
    val isSttAvailable: Boolean = true,
    val handsFreeContinuousMode: Boolean = true,
    val isHandsFreeContinuous: Boolean = true,
    val ttsLanguageLabel: String = "প্রস্তুত হচ্ছে...",
    val languageLabel: String = "বাংলা (bn-BD)",
    val sttLanguageCode: String = "bn-BD",
    val languageCode: String = "bn-BD",
    val rmsLevel: Float = 0f,
    val statusHint: String = "মাইক সর্বদা সক্রিয় — সরাসরি কথা বলুন",
    val speechRate: Float = 0.98f,
    val pitch: Float = 0.72f,
    val voiceProfile: String = "DEEP_JARVIS",
    val partialTranscript: String = "",
    val lastSpokenText: String = "",
    val errorHint: String? = null
)

/**
 * Coordinates `AgentTtsModule` (Voice Output) and `AgentSttModule` (Voice Input)
 * with instant Barge-In support and natural human conversational prosody.
 */
class VoiceAgentBridge(
    context: Context,
    private val onWakeWordGreetingTriggered: (rawWakePhrase: String, greetingText: String) -> Unit,
    private val onPartialTranscriptChanged: (partialText: String) -> Unit = {},
    private val onFinalTranscript: (recognizedText: String, wasWokenByCube: Boolean) -> Unit,
    private val onSpeechPlayingStateChanged: (isSpeaking: Boolean) -> Unit = {}
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(VoiceBridgeState())
    val state: StateFlow<VoiceBridgeState> = _state.asStateFlow()

    private var listenForCommandAfterGreeting = false
    private var activeSessionWokenByCube = false

    private val ttsModule: AgentTtsModule
    private val sttModule: AgentSttModule

    init {
        ttsModule = AgentTtsModule(
            context = appContext,
            onReadyChanged = { ready, langLabel ->
                _state.value = _state.value.copy(
                    isTtsReady = ready,
                    ttsLanguageLabel = langLabel
                )
            },
            onSpeechStarted = { spokenText ->
                _state.value = _state.value.copy(
                    isSpeaking = true,
                    lastSpokenText = spokenText,
                    errorHint = null
                )
                onSpeechPlayingStateChanged(true)
                sttModule.pauseForTtsOutput()
            },
            onSpeechCompleted = {
                _state.value = _state.value.copy(isSpeaking = false)
                onSpeechPlayingStateChanged(false)
                handlePostSpeechLifecycle()
            },
            onSpeechError = { errMsg ->
                _state.value = _state.value.copy(
                    isSpeaking = false,
                    errorHint = errMsg
                )
                onSpeechPlayingStateChanged(false)
                handlePostSpeechLifecycle()
            }
        )

        sttModule = AgentSttModule(
            context = appContext,
            isTtsCurrentlySpeaking = { _state.value.isSpeaking },
            onPartialSpeechUpdate = { partial ->
                // If user starts speaking while TTS is speaking, trigger instant Barge-In!
                if (_state.value.isSpeaking && partial.trim().length >= 2) {
                    ttsModule.stopSpeaking()
                    _state.value = _state.value.copy(isSpeaking = false)
                    onSpeechPlayingStateChanged(false)
                }
                _state.value = _state.value.copy(
                    partialTranscript = partial,
                    errorHint = null
                )
                onPartialTranscriptChanged(partial)
            },
            onFinalSpeechRecognized = { finalCmd, _, isFollowUp ->
                if (_state.value.isSpeaking) {
                    ttsModule.stopSpeaking()
                    _state.value = _state.value.copy(isSpeaking = false)
                    onSpeechPlayingStateChanged(false)
                }
                _state.value = _state.value.copy(
                    partialTranscript = finalCmd,
                    isListening = false,
                    errorHint = null
                )
                val wasWoken = isFollowUp || activeSessionWokenByCube
                activeSessionWokenByCube = false
                onFinalTranscript(finalCmd, wasWoken)
            },
            onStateChanged = { sttState ->
                _state.value = _state.value.copy(
                    isListening = sttState.isListening,
                    handsFreeContinuousMode = sttState.isHandsFreeContinuous,
                    isHandsFreeContinuous = sttState.isHandsFreeContinuous,
                    isSttAvailable = sttState.isRecognitionServiceAvailable,
                    sttLanguageCode = sttState.languageCode,
                    languageCode = sttState.languageCode,
                    languageLabel = sttState.languageLabel,
                    rmsLevel = sttState.rmsLevel,
                    statusHint = sttState.statusMessage
                )
            }
        )

        val initialStt = sttModule.state.value
        _state.value = _state.value.copy(
            isSttAvailable = initialStt.isRecognitionServiceAvailable,
            sttLanguageCode = initialStt.languageCode,
            languageCode = initialStt.languageCode,
            languageLabel = initialStt.languageLabel,
            handsFreeContinuousMode = initialStt.isHandsFreeContinuous,
            isHandsFreeContinuous = initialStt.isHandsFreeContinuous,
            speechRate = ttsModule.speechRate,
            pitch = ttsModule.pitch,
            voiceProfile = ttsModule.voiceProfile
        )
    }

    private fun handlePostSpeechLifecycle() {
        val shouldListenForWakeFollowUp = listenForCommandAfterGreeting
        listenForCommandAfterGreeting = false
        val sentinelOn = CubeSentinelBus.state.value.isSentinelActive
        val handsFreeOn = _state.value.handsFreeContinuousMode
        val extState = ExternalMicArbiter.state.value

        if (extState.isGeminiLiveOwningMic || extState.isExternalMicBusy) {
            return
        }

        if (shouldListenForWakeFollowUp || handsFreeOn || sentinelOn) {
            if (sentinelOn) {
                CubeSentinelBus.updatePhase(WakeWordPhase.AWAITING_BOSS_COMMAND)
            }
            mainHandler.postDelayed({
                if (!ExternalMicArbiter.state.value.isGeminiLiveOwningMic &&
                    !ExternalMicArbiter.state.value.isExternalMicBusy &&
                    !_state.value.isSpeaking
                ) {
                    sttModule.startListening(isCommandFollowUp = true)
                }
            }, 220L)
        }
    }

    fun speak(text: String, forceSpeakWhenMuted: Boolean = false) {
        if (text.isBlank()) {
            handlePostSpeechLifecycle()
            return
        }
        _state.value = _state.value.copy(lastSpokenText = text)
        if (ttsModule.isMuted && !forceSpeakWhenMuted) {
            handlePostSpeechLifecycle()
            return
        }
        ttsModule.speak(text, forceSpeakWhenMuted = forceSpeakWhenMuted)
    }

    /**
     * Speaks [text] aloud in the deep, clear male voice and automatically starts listening
     * for the user's reply as soon as the utterance finishes (Real Gemini Live turn-taking).
     */
    fun speakAndThenListen(text: String) {
        if (text.isBlank()) {
            startListening(isManualCommandTap = true)
            return
        }
        ttsModule.isMuted = false
        listenForCommandAfterGreeting = true
        _state.value = _state.value.copy(
            isMuted = false,
            ttsEnabled = true,
            lastSpokenText = text
        )
        ttsModule.speak(text, forceSpeakWhenMuted = true)
    }

    fun replayLastSpoken() {
        val last = _state.value.lastSpokenText
        if (last.isNotBlank()) {
            ttsModule.speak(last, forceSpeakWhenMuted = true)
        } else {
            testTtsVoice()
        }
    }

    fun testTtsVoice() {
        val sample = "জি স্যার! আমার ডিপ এবং ক্লিয়ার মেল ভয়েস সম্পূর্ণ প্রস্তুত। আপনি যেকোনো আদেশ বলুন।"
        _state.value = _state.value.copy(lastSpokenText = sample)
        ttsModule.speak(sample, forceSpeakWhenMuted = true)
    }

    fun stopSpeaking() {
        listenForCommandAfterGreeting = false
        ttsModule.stopSpeaking()
        _state.value = _state.value.copy(isSpeaking = false)
        onSpeechPlayingStateChanged(false)
    }

    fun toggleTts(): Boolean = toggleMute()

    fun toggleMute(): Boolean {
        val next = !_state.value.isMuted
        ttsModule.isMuted = next
        _state.value = _state.value.copy(
            isMuted = next,
            ttsEnabled = !next
        )
        if (next) {
            ttsModule.stopSpeaking()
        }
        return next
    }

    fun setSpeechRate(rate: Float) {
        ttsModule.setSpeechRate(rate)
        _state.value = _state.value.copy(speechRate = ttsModule.speechRate)
    }

    fun setPitch(newPitch: Float) {
        ttsModule.setPitch(newPitch)
        _state.value = _state.value.copy(pitch = ttsModule.pitch)
    }

    fun applyVoiceProfile(profileName: String) {
        ttsModule.applyVoiceProfile(profileName)
        _state.value = _state.value.copy(
            voiceProfile = ttsModule.voiceProfile,
            pitch = ttsModule.pitch,
            speechRate = ttsModule.speechRate
        )
    }

    fun toggleHandsFreeContinuousMode(): Boolean {
        val next = sttModule.toggleHandsFreeContinuousMode()
        _state.value = _state.value.copy(
            handsFreeContinuousMode = next,
            isHandsFreeContinuous = next
        )
        return next
    }

    fun setHandsFreeContinuousMode(enabled: Boolean) {
        sttModule.setHandsFreeContinuousMode(enabled)
        _state.value = _state.value.copy(
            handsFreeContinuousMode = enabled,
            isHandsFreeContinuous = enabled
        )
    }

    fun startListening(isManualCommandTap: Boolean = true) {
        if (_state.value.isSpeaking) {
            ttsModule.stopSpeaking()
            _state.value = _state.value.copy(isSpeaking = false)
            onSpeechPlayingStateChanged(false)
        }
        _state.value = _state.value.copy(errorHint = null)
        sttModule.startListening(isCommandFollowUp = isManualCommandTap)
    }

    fun stopListening(keepSentinelPhase: Boolean = false) {
        sttModule.stopListening()
        if (!keepSentinelPhase && CubeSentinelBus.state.value.isSentinelActive) {
            CubeSentinelBus.updatePhase(WakeWordPhase.LISTENING_FOR_WAKE_WORD)
        }
    }

    fun createSystemVoicePromptIntent(): Intent = sttModule.buildSystemRecognizerFallbackIntent()

    fun onExternalSpeechResult(spokenText: String) {
        sttModule.onExternalActivityResult(spokenText)
    }

    fun toggleLanguage(): String {
        val next = sttModule.cycleLanguage()
        val s = sttModule.state.value
        _state.value = _state.value.copy(
            sttLanguageCode = next,
            languageCode = next,
            languageLabel = s.languageLabel
        )
        return next
    }

    fun setSttLanguage(languageCode: String) {
        sttModule.setLanguage(languageCode)
        val s = sttModule.state.value
        _state.value = _state.value.copy(
            sttLanguageCode = s.languageCode,
            languageCode = s.languageCode,
            languageLabel = s.languageLabel
        )
    }

    fun triggerHeyCubeWakeSequence(inlineCommand: String = "", rawWakePhrase: String = "Hey Cube") {
        activeSessionWokenByCube = true
        if (inlineCommand.isNotBlank()) {
            CubeSentinelBus.updatePhase(WakeWordPhase.PROCESSING_COMMAND)
            onFinalTranscript(inlineCommand, true)
            return
        }

        val greeting = "জি স্যার, বলুন আমি কী করব?"
        listenForCommandAfterGreeting = true
        CubeSentinelBus.updatePhase(WakeWordPhase.GREETING_BOSS)
        onWakeWordGreetingTriggered(rawWakePhrase, greeting)

        if (_state.value.isMuted) {
            handlePostSpeechLifecycle()
        } else {
            ttsModule.speak(greeting, forceSpeakWhenMuted = true)
        }
    }

    fun release() {
        sttModule.release()
        ttsModule.shutdown()
    }
}
