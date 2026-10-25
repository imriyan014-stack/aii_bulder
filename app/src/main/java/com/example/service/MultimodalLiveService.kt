package com.example.service

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

private const val TAG = "MultimodalLiveService"

enum class LiveConnectionEngine(
    val id: String,
    val badgeText: String,
    val labelBn: String
) {
    AUTO_HYBRID(
        id = "auto_hybrid",
        badgeText = "GEMINI LIVE WEBSOCKET",
        labelBn = "ডাইরেক্ট Gemini Live WebSocket (BidiGenerateContent)"
    ),
    BUILTIN_FIREBASE_STREAM(
        id = "builtin_stream",
        badgeText = "GEMINI LIVE WEBSOCKET",
        labelBn = "ডাইরেক্ট Gemini Live WebSocket (BidiGenerateContent)"
    ),
    DIRECT_WEBSOCKET_BIDI(
        id = "direct_ws_bidi",
        badgeText = "GEMINI LIVE WEBSOCKET",
        labelBn = "ডাইরেক্ট Gemini Live WebSocket (BidiGenerateContent)"
    )
}

data class MultimodalLiveExportConfig(
    val builtInFirebaseApiKey: String = "",
    val customUserApiKey: String = "",
    val effectiveApiKey: String = "",
    val projectId: String = "",
    val applicationId: String = "",
    val packageName: String = "",
    val certSha1: String = "",
    val websocketBidiEndpoint: String =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
    val fullWebsocketUrlWithKey: String = "",
    val restStreamEndpoint: String =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:streamGenerateContent?alt=sse",
    val activeLiveModel: String = "models/gemini-3.1-flash-live-preview",
    val fallbackStreamModel: String = "models/gemini-2.5-flash-native-audio-preview-09-2025",
    val setupHandshakeJson: String = "",
    val setupPayloadJson: String = "",
    val toolsDeclarationJson: String = "",
    val realtimeFramePayloadSample: String = "",
    val pythonLiveClientCode: String = ""
)

data class MultimodalLiveSessionState(
    val isLiveActive: Boolean = false,
    val connectionEngine: LiveConnectionEngine = LiveConnectionEngine.DIRECT_WEBSOCKET_BIDI,
    val engineMode: LiveConnectionEngine = LiveConnectionEngine.DIRECT_WEBSOCKET_BIDI,
    val connectionStatusLabel: String = "CONNECTING • GEMINI LIVE",
    val isWebSocketConnected: Boolean = false,
    val isMicMuted: Boolean = false,
    val isSpeakerMuted: Boolean = false,
    val isCameraStreamEnabled: Boolean = false,
    val isScreenStreamEnabled: Boolean = false,
    val isRealTimeToolsArmed: Boolean = true,
    val isToolsArmed: Boolean = true,
    val isAgentSpeakingLive: Boolean = false,
    val isUserSpeakingBargeIn: Boolean = false,
    val liveRmsLevel: Float = 0f,
    val streamedCameraFrames: Int = 0,
    val streamedScreenFrames: Int = 0,
    val framesSentCount: Int = 0,
    val fpsTelemetry: String = "LIVE 24kHz PCM • VISION",
    val streamedAudioPackets: Int = 0,
    val executedLiveActionsCount: Int = 0,
    val bargeInInterruptCount: Int = 0,
    val lastVisualInsight: String = "ক্যামেরা ও পার্মানেন্ট স্ক্রিন ভিশন সক্রিয়",
    val audioWaveformLevels: List<Float> = listOf(
        0.22f, 0.38f, 0.54f, 0.30f, 0.68f, 0.82f, 0.45f, 0.60f,
        0.75f, 0.42f, 0.58f, 0.85f, 0.50f, 0.36f, 0.62f, 0.28f
    ),
    val lastLiveToolSummary: String = "লাইভ ভয়েস, ক্যামেরা ও পার্মানেন্ট স্ক্রিন কন্ট্রোল প্রস্তুত",
    val lastToolExecuted: String = "লাইভ ভয়েস, ক্যামেরা ও ৪-দিকের স্ক্রিন কন্ট্রোল প্রস্তুত",
    val lastCopiedLabel: String = "",
    val liveStreamingPartialReply: String = "",
    val activeModelName: String = "models/gemini-3.1-flash-live-preview",
    val statusMessageBn: String = "Gemini Live প্রস্তুত — সরাসরি কথা বলুন",
    val exportConfig: MultimodalLiveExportConfig = MultimodalLiveExportConfig()
)

class MultimodalLiveService(
    private val context: Context,
    private val onLiveSpokenChunkOrReply: (textChunk: String, isFinalTurn: Boolean) -> Unit,
    private val onExecuteLiveActionTag: (userUtterance: String, rawAiResponse: String) -> Unit,
    private val onBargeInInterruptRequested: () -> Unit,
    private val onUserTranscript: (String) -> Unit = {}
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(MultimodalLiveSessionState())
    val state: StateFlow<MultimodalLiveSessionState> = _state.asStateFlow()

    @Volatile
    private var latestCameraBitmap: Bitmap? = null

    @Volatile
    private var latestScreenBitmap: Bitmap? = null

    private var framePumpJob: Job? = null
    private var audioRecordPumpJob: Job? = null
    private var audioPlaybackPumpJob: Job? = null
    private var activeWebSocket: WebSocket? = null

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    // Non-blocking 24kHz PCM playback queue + AudioTrack so WebSocket onMessage never blocks
    // and barge-in flushes audio in < 10ms.
    private val audioTrackLock = Any()
    private var liveOutputAudioTrack: AudioTrack? = null
    private val pcmPlaybackQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile
    private var lastAudioChunkPlayedAtMs: Long = 0L

    // Live model candidates: if one is rejected before setup completes, the next one is tried automatically.
    private var liveModelCandidates: List<String> = listOf(
        "models/gemini-3.1-flash-live-preview",
        "models/gemini-2.5-flash-native-audio-preview-09-2025",
        "models/gemini-live-2.5-flash-preview",
        "models/gemini-2.0-flash-exp"
    )
    @Volatile private var modelIndex = 0
    private val wsBidiModel: String get() = liveModelCandidates[modelIndex.coerceIn(0, liveModelCandidates.size - 1)]
    @Volatile private var setupDone = false
    @Volatile var lastFrameForBrain: Bitmap? = null
    private var lastSentSig: IntArray? = null
    private var lastSentAt = 0L
    private var reconnectAttempts = 0
    private var toolCallHandler: (suspend (String) -> String)? = null
    private val userTurnBuf = StringBuilder()
    private val modelTurnBuf = StringBuilder()
    @Volatile private var pendingTextTurn: String? = null

    fun setToolCallHandler(handler: suspend (String) -> String) {
        toolCallHandler = handler
    }

    private var namedToolHandler: (suspend (String, JSONObject) -> String)? = null
    fun setNamedToolHandler(handler: suspend (String, JSONObject) -> String) {
        namedToolHandler = handler
    }

    init {
        ExternalMicArbiter.init(context)
        val permReady = ScreenCaptureSessionBus.syncSavedPreference(context)
        if (permReady) {
            _state.value = _state.value.copy(isScreenStreamEnabled = true)
        }
        refreshExportConfig(customApiKey = "")
    }

    private fun strProp(desc: String) = JSONObject().put("type", "STRING").put("description", desc)

    private fun toolDecl(name: String, desc: String, props: List<Pair<String, String>>, required: List<String>) = JSONObject().apply {
        put("name", name)
        put("description", desc)
        put("parameters", JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply { props.forEach { (k, d) -> put(k, strProp(d)) } })
            put("required", JSONArray().apply { required.forEach { put(it) } })
        })
    }

    private fun uiMemoryDecl() = toolDecl("ui_memory",
        "Look up where a button/field was seen before in any app (remembered coordinates). Use BEFORE searching around: if found, tap it with phone_action SCREEN_UI_CONTROL target='x,y' extra='TAP_XY'.",
        listOf("query" to "Label to find, e.g. 'search', 'play', 'send'", "app" to "Optional app name or package"), listOf("query"))

    private fun findAppDecl() = toolDecl("find_app",
        "Find installed apps by name (returns label + package). Use to check if an app exists before opening it.",
        listOf("query" to "App name fragment"), listOf("query"))

    private fun deepThinkDecl() = toolDecl("deep_think",
        "Ask the most powerful Gemini model (it also sees the current screen) to plan a HARD or multi-step task or to recover when you are stuck/failing. Returns a numbered action plan.",
        listOf("goal" to "What you are trying to achieve and what went wrong, if anything"), listOf("goal"))

    private fun appsContextBlock(): String = try {
        "\n\nINSTALLED APPS ON THIS PHONE (you know all of them; open by exact name): " + InstalledAppsIndex.summary(context, 160)
    } catch (_: Exception) { "" }

    private fun appControlDecl(): JSONObject = JSONObject().apply {
        put("name", "app_control")
        put("description", "Switch OmniAgent's own features on/off. Use when the user says things like 'touch effect on/off', 'screen share off', 'mute mic'.")
        put("parameters", JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("feature", strProp("TOUCH_EFFECT, SCREEN_SHARE, CAMERA, MIC_MUTE or SPEAKER_MUTE"))
                put("state", strProp("ON or OFF"))
            })
            put("required", JSONArray().put("feature").put("state"))
        })
    }

    private fun writeCodeDecl(): JSONObject = JSONObject().apply {
        put("name", "write_code")
        put("description", "Argon 4 coding mode: write a COMPLETE file of code. The app shows a full-screen live typing page, then saves the file (file, folder and zip) into the 'archer' folder. Pass the entire code in 'code'.")
        put("parameters", JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("title", strProp("Short project title"))
                put("file_name", strProp("File name with extension, e.g. index.html"))
                put("language", strProp("Language, e.g. HTML, Python, Kotlin"))
                put("code", strProp("The full source code"))
            })
            put("required", JSONArray().put("title").put("file_name").put("code"))
        })
    }

    fun currentApiKey(): String =
        Regex("key=([^&]+)").find(_state.value.exportConfig.fullWebsocketUrlWithKey)?.groupValues?.get(1).orEmpty()

    private fun buildSetupJson(): String = JSONObject().apply {
        put("setup", JSONObject().apply {
            put("model", wsBidiModel)
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Charon"))))
            })
            put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", LIVE_MULTIMODAL_SYSTEM_PROMPT + appsContextBlock()))))
            put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(
                JSONObject().apply {
                    put("name", "phone_action")
                    put("description", "Control the user's phone: open apps, call, toggle settings, volume, tap/scroll/swipe the screen, start screen sharing, read the screen, play music. Call this whenever the user asks you to DO something on the phone.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("action", JSONObject().put("type", "STRING").put("description",
                                "One of: OPEN_APP, TOGGLE_SYSTEM_SETTING, SET_VOLUME, SCAN_CONTACT_OR_SCREEN, SCREEN_UI_CONTROL, PLAY_MUSIC, STOP_MUSIC, DIAL_NUMBER, OPEN_SETTINGS, INSTALL_APP_PLAYSTORE"))
                            put("target", JSONObject().put("type", "STRING").put("description",
                                "App name / setting name (WIFI, BLUETOOTH, MOBILE_DATA, FLASHLIGHT...) / contact / button text / song"))
                            put("extra", JSONObject().put("type", "STRING").put("description",
                                "ON, OFF, 0-100, UP, DOWN, CALL, WHATSAPP, CLICK, SCROLL_DOWN, SCROLL_UP, SWIPE_LEFT, SWIPE_RIGHT, READ_SCREEN..."))
                        })
                        put("required", JSONArray().put("action"))
                    })
                }
            ).put(appControlDecl()).put(writeCodeDecl()).put(uiMemoryDecl()).put(findAppDecl()).put(deepThinkDecl()))))
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
        })
    }.toString()

    fun refreshExportConfig(customApiKey: String) {
        val cleanCustom = customApiKey.trim()
        var firebaseKey = ""
        var projectId = ""
        var appId = ""
        try {
            val opts = FirebaseApp.getInstance().options
            firebaseKey = opts.apiKey.orEmpty()
            projectId = opts.projectId.orEmpty()
            appId = opts.applicationId.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseApp options read warning: ${e.message}")
        }

        val effectiveKey = cleanCustom.ifBlank { firebaseKey }
        val pkgName = context.packageName
        val wsBase =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        val fullWs = if (effectiveKey.isNotBlank()) {
            "$wsBase?key=$effectiveKey"
        } else {
            "$wsBase?key=YOUR_GEMINI_LIVE_API_KEY"
        }

        val setupJson = buildSetupJson()

        val toolsJson = JSONObject().apply {
            put(
                "functionDeclarations",
                JSONArray().apply {
                    put(JSONObject().put("name", "phone_action").put("description", "Control apps, settings, volume, contacts, and 4-directional screen UI touch"))
                }
            )
        }.toString(2)

        val frameSample = JSONObject().apply {
            put(
                "realtimeInput",
                JSONObject().apply {
                    put(
                        "audio",
                        JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", "<BASE64_16KHZ_MONO_PCM_AUDIO>")
                        }
                    )
                }
            )
        }.toString(2)

        val newExport = MultimodalLiveExportConfig(
            builtInFirebaseApiKey = firebaseKey,
            customUserApiKey = cleanCustom,
            effectiveApiKey = effectiveKey,
            projectId = projectId,
            applicationId = appId,
            packageName = pkgName,
            websocketBidiEndpoint = wsBase,
            fullWebsocketUrlWithKey = fullWs,
            activeLiveModel = wsBidiModel,
            setupHandshakeJson = setupJson,
            setupPayloadJson = setupJson,
            toolsDeclarationJson = toolsJson,
            realtimeFramePayloadSample = frameSample,
            pythonLiveClientCode = ""
        )

        _state.value = _state.value.copy(exportConfig = newExport)
    }

    fun copyToClipboard(label: String, text: String): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            _state.value = _state.value.copy(lastCopiedLabel = label)
            "জি স্যার, '$label' ক্লিপবোর্ডে কপি করা হয়েছে!"
        } catch (e: Exception) {
            "কপি করতে সমস্যা হয়েছে: ${e.message}"
        }
    }

    fun setEngineMode(engine: LiveConnectionEngine) {
        _state.value = _state.value.copy(
            connectionEngine = engine,
            engineMode = engine,
            statusMessageBn = engine.labelBn
        )
    }

    fun updateLatestCameraFrame(bitmap: Bitmap) {
        latestCameraBitmap = bitmap
        if (_state.value.isLiveActive && _state.value.isCameraStreamEnabled) {
            val nextCam = _state.value.streamedCameraFrames + 1
            val totalFrames = nextCam + _state.value.streamedScreenFrames
            _state.value = _state.value.copy(
                streamedCameraFrames = nextCam,
                framesSentCount = totalFrames
            )
        }
    }

    fun updateLatestScreenFrame(bitmap: Bitmap) {
        latestScreenBitmap = bitmap
        if (_state.value.isLiveActive && _state.value.isScreenStreamEnabled) {
            val nextScr = _state.value.streamedScreenFrames + 1
            val totalFrames = _state.value.streamedCameraFrames + nextScr
            _state.value = _state.value.copy(
                streamedScreenFrames = nextScr,
                framesSentCount = totalFrames
            )
        }
    }

    fun startLiveSession(customApiKey: String) {
        LiveKeepAliveService.start(context)
        ExternalMicArbiter.init(context)
        ExternalMicArbiter.setGeminiLiveOwningMic(true)
        modelIndex = 0
        reconnectAttempts = 0
        setupDone = false
        refreshExportConfig(customApiKey)
        ScreenMemory.load(context)
        _state.value = _state.value.copy(
            isScreenStreamEnabled = true,
            isLiveActive = true,
            isMicMuted = false,
            connectionStatusLabel = "CONNECTING • GEMINI LIVE",
            activeModelName = wsBidiModel,
            statusMessageBn = "Gemini Live কানেক্ট হচ্ছে..."
        )

        scope.launch {
            // Ask Google which models this key can use and take the most powerful live model first
            val resolved = withTimeoutOrNull(5000L) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { GeminiModelResolver.resolve(currentApiKey()) }
            }
            if (resolved != null && resolved.live.isNotEmpty()) {
                liveModelCandidates = (resolved.live + liveModelCandidates).distinct()
                modelIndex = 0
                _state.value = _state.value.copy(activeModelName = wsBidiModel)
            }
            connectDirectBidiWebSocketIfPossible()
        }
        startFrameCapturePump()
        startAudioPlaybackPump()
        startRealTimeAudioRecordPump()
    }

    fun stopLiveSession() {
        LiveKeepAliveService.stop(context)
        ExternalMicArbiter.setGeminiLiveOwningMic(false)
        framePumpJob?.cancel()
        framePumpJob = null
        audioRecordPumpJob?.cancel()
        audioRecordPumpJob = null
        audioPlaybackPumpJob?.cancel()
        audioPlaybackPumpJob = null
        stopAndFlushLiveAudioTrack()
        disconnectWebSocket()
        _state.value = _state.value.copy(
            isLiveActive = false,
            isWebSocketConnected = false,
            isAgentSpeakingLive = false,
            isUserSpeakingBargeIn = false,
            liveRmsLevel = 0f,
            connectionStatusLabel = "STANDBY • TAP GLOBE FOR GEMINI LIVE",
            liveStreamingPartialReply = "",
            statusMessageBn = "গ্লোবে ট্যাপ করে কথা বলুন"
        )
    }

    fun toggleMicMute(): Boolean {
        val next = !_state.value.isMicMuted
        _state.value = _state.value.copy(
            isMicMuted = next,
            liveRmsLevel = if (next) 0f else _state.value.liveRmsLevel,
            statusMessageBn = if (next) "মাইক্রোফোন মিউট করা হয়েছে — গ্লোবে ট্যাপ করে আনমিউট করুন" else "শুনছি… কথা বলুন"
        )
        return next
    }

    fun setMicMuted(muted: Boolean) {
        _state.value = _state.value.copy(
            isMicMuted = muted,
            liveRmsLevel = if (muted) 0f else _state.value.liveRmsLevel,
            statusMessageBn = if (muted) "মাইক্রোফোন পজ করা হয়েছে — গ্লোবে ট্যাপ করে কথা বলুন" else "শুনছি… কথা বলুন"
        )
    }

    fun setSpeakerMuted(muted: Boolean) {
        if (_state.value.isSpeakerMuted != muted) toggleSpeakerMute()
    }

    fun toggleSpeakerMute(): Boolean {
        val next = !_state.value.isSpeakerMuted
        if (next) {
            stopAndFlushLiveAudioTrack()
        }
        _state.value = _state.value.copy(
            isSpeakerMuted = next,
            isAgentSpeakingLive = if (next) false else _state.value.isAgentSpeakingLive,
            statusMessageBn = if (next) "স্পিকার মিউট করা হয়েছে" else "স্পিকার চালু করা হয়েছে"
        )
        return next
    }

    fun toggleCameraStream(): Boolean {
        val next = !_state.value.isCameraStreamEnabled
        _state.value = _state.value.copy(
            isCameraStreamEnabled = next,
            statusMessageBn = if (next) "লাইভ ক্যামেরা ভিশন চালু হয়েছে" else "লাইভ ক্যামেরা ভিশন বন্ধ করা হয়েছে"
        )
        return next
    }

    fun enableScreenStreamNow() {
        _state.value = _state.value.copy(
            isScreenStreamEnabled = true,
            statusMessageBn = "পার্মানেন্ট লাইভ স্ক্রিন শেয়ারিং চালু হয়েছে — এজেন্ট আপনার স্ক্রিন দেখছে"
        )
        ScreenCaptureSessionBus.captureBestAvailableScreenFrame { bmp ->
            updateLatestScreenFrame(bmp)
            if (_state.value.isLiveActive && _state.value.isWebSocketConnected) {
                sendRealtimeImageFrameOverWs(bmp)
            }
        }
    }

    fun toggleScreenStream(): Boolean {
        val next = !_state.value.isScreenStreamEnabled
        _state.value = _state.value.copy(
            isScreenStreamEnabled = next,
            statusMessageBn = if (next) "লাইভ স্ক্রিন শেয়ারিং চালু হয়েছে" else "লাইভ স্ক্রিন শেয়ারিং বন্ধ করা হয়েছে"
        )
        if (next) {
            ScreenCaptureSessionBus.captureBestAvailableScreenFrame { bmp ->
                updateLatestScreenFrame(bmp)
                if (_state.value.isLiveActive && _state.value.isWebSocketConnected) {
                    sendRealtimeImageFrameOverWs(bmp)
                }
            }
        }
        return next
    }

    fun toggleToolsArmed(): Boolean {
        val next = !_state.value.isRealTimeToolsArmed
        _state.value = _state.value.copy(
            isRealTimeToolsArmed = next,
            isToolsArmed = next
        )
        return next
    }

    /**
     * Barge-in (Instant Interruption):
     * Immediately halts any ongoing AI audio stream (`AudioTrack` flush + stop), clears the
     * playback queue, and switches to listening for the user's new speech.
     */
    fun triggerBargeInInterrupt() {
        stopAndFlushLiveAudioTrack()
        _state.value = _state.value.copy(
            isAgentSpeakingLive = false,
            isUserSpeakingBargeIn = true,
            liveRmsLevel = 0.15f,
            bargeInInterruptCount = _state.value.bargeInInterruptCount + 1,
            liveStreamingPartialReply = "",
            statusMessageBn = "শুনছি… বলুন স্যার"
        )
        onBargeInInterruptRequested()
    }

    fun notifyAgentSpeechState(isSpeaking: Boolean) {
        if (_state.value.isAgentSpeakingLive != isSpeaking) {
            _state.value = _state.value.copy(
                isAgentSpeakingLive = isSpeaking,
                isUserSpeakingBargeIn = if (isSpeaking) false else _state.value.isUserSpeakingBargeIn
            )
        }
    }

    fun recordLiveToolExecution(summaryBn: String) {
        _state.value = _state.value.copy(
            executedLiveActionsCount = _state.value.executedLiveActionsCount + 1,
            lastLiveToolSummary = summaryBn,
            lastToolExecuted = summaryBn
        )
    }

    /**
     * Dedicated non-blocking 24kHz PCM playback pump so OkHttp's WebSocket reader thread
     * never blocks inside `AudioTrack.write()` and can process `interrupted` events in < 1ms.
     */
    private fun startAudioPlaybackPump() {
        audioPlaybackPumpJob?.cancel()
        audioPlaybackPumpJob = scope.launch {
            while (isActive && _state.value.isLiveActive) {
                val chunk = pcmPlaybackQueue.poll()
                if (chunk == null) {
                    if (_state.value.isAgentSpeakingLive &&
                        System.currentTimeMillis() - lastAudioChunkPlayedAtMs > 280L
                    ) {
                        _state.value = _state.value.copy(
                            isAgentSpeakingLive = false,
                            liveRmsLevel = 0.06f
                        )
                    }
                    delay(12L)
                    continue
                }

                if (_state.value.isSpeakerMuted) {
                    pcmPlaybackQueue.clear()
                    continue
                }

                // Compute output voice RMS for the 3D Main Globe visualizer
                val outRms = computePcm16LeRmsNormalized(chunk)
                _state.value = _state.value.copy(
                    isAgentSpeakingLive = true,
                    isUserSpeakingBargeIn = false,
                    liveRmsLevel = outRms.coerceIn(0.18f, 0.98f)
                )

                try {
                    val track = ensureAudioTrackInitialized()
                    if (track != null) {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            routeAudioToMainLoudspeaker(track)
                            track.play()
                        }
                        track.write(chunk, 0, chunk.size)
                        lastAudioChunkPlayedAtMs = System.currentTimeMillis()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AudioTrack write warning: ${e.message}")
                }
            }
        }
    }

    private fun computePcm16LeRmsNormalized(pcmBytes: ByteArray): Float {
        val sampleCount = pcmBytes.size / 2
        if (sampleCount <= 0) return 0.2f
        var sumSq = 0.0
        var i = 0
        val step = 4 // sample every 4th short for fast calculation
        var counted = 0
        while (i + 1 < pcmBytes.size) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble()
            sumSq += sample * sample
            counted++
            i += 2 * step
        }
        if (counted == 0) return 0.2f
        val rms = sqrt(sumSq / counted)
        return (rms / 6500.0).toFloat().coerceIn(0.12f, 1.0f)
    }

    @SuppressLint("MissingPermission")
    private fun startRealTimeAudioRecordPump() {
        audioRecordPumpJob?.cancel()
        audioRecordPumpJob = scope.launch {
            var tick = 0
            while (isActive && _state.value.isLiveActive) {
                tick++
                val extMicState = ExternalMicArbiter.state.value
                if (extMicState.isExternalMicBusy) {
                    val yieldMsg = extMicState.busyReasonBn
                        .ifBlank { "অন্য জায়গায় মাইক ব্যবহৃত হচ্ছে — কাজ শেষ হলেই অটো সক্রিয় হবে" }
                    if (_state.value.statusMessageBn != yieldMsg) {
                        _state.value = _state.value.copy(
                            statusMessageBn = yieldMsg,
                            liveRmsLevel = 0f
                        )
                    }
                    delay(250L)
                    continue
                }

                val ready = _state.value.isWebSocketConnected && setupDone && !_state.value.isMicMuted
                val hasMicPerm = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!ready || !hasMicPerm) {
                    delay(180L)
                    continue
                }

                val sampleRate = 16000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(3200)
                val recorder = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2
                    )
                } catch (e: Exception) {
                    delay(450L)
                    continue
                }
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    try { recorder.release() } catch (_: Exception) {}
                    delay(450L)
                    continue
                }

                val sessionId = recorder.audioSessionId
                ExternalMicArbiter.registerOwnLiveAudioSession(sessionId)

                var aec: android.media.audiofx.AcousticEchoCanceler? = null
                var ns: android.media.audiofx.NoiseSuppressor? = null
                try {
                    if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                        aec = android.media.audiofx.AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
                    }
                    if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                        ns = android.media.audiofx.NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
                    }
                } catch (_: Exception) {}
                val aecActive = aec?.enabled == true

                val buf = ShortArray(800) // 50ms @16kHz
                var loudFrames = 0
                try {
                    recorder.startRecording()
                    if (_state.value.statusMessageBn.contains("অন্য")) {
                        _state.value = _state.value.copy(statusMessageBn = "শুনছি… কথা বলুন")
                    }
                    while (isActive && _state.value.isLiveActive && _state.value.isWebSocketConnected &&
                        setupDone && !_state.value.isMicMuted && !ExternalMicArbiter.state.value.isExternalMicBusy
                    ) {
                        val n = recorder.read(buf, 0, buf.size)
                        if (n <= 0) {
                            delay(10L)
                            continue
                        }
                        var sum = 0.0
                        for (i in 0 until n) {
                            val v = buf[i].toDouble()
                            sum += v * v
                        }

                        val rms = sqrt(sum / n)
                        if (!_state.value.isAgentSpeakingLive) {
                            val normMicRms = (rms / 3500.0).toFloat().coerceIn(0.05f, 1.0f)
                            _state.value = _state.value.copy(liveRmsLevel = normMicRms)
                        }

                        // Instant local barge-in when user speaks while agent is talking
                        val bargeThreshold = if (aecActive) 2000.0 else 4600.0
                        if (_state.value.isAgentSpeakingLive && rms > bargeThreshold) {
                            loudFrames++
                            if (loudFrames >= 3) {
                                loudFrames = 0
                                localBargeIn()
                            }
                        } else {
                            loudFrames = 0
                        }

                        val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until n) bb.putShort(buf[i])
                        sendRealtimeAudioChunkOverWs(bb.array())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Mic stream paused: ${e.message}")
                } finally {
                    ExternalMicArbiter.unregisterOwnLiveAudioSession(sessionId)
                    try { recorder.stop() } catch (_: Exception) {}
                    try { recorder.release() } catch (_: Exception) {}
                    try { aec?.release() } catch (_: Exception) {}
                    try { ns?.release() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun localBargeIn() {
        stopAndFlushLiveAudioTrack()
        _state.value = _state.value.copy(
            isAgentSpeakingLive = false,
            isUserSpeakingBargeIn = true,
            bargeInInterruptCount = _state.value.bargeInInterruptCount + 1
        )
        onBargeInInterruptRequested()
    }

private fun startFrameCapturePump() {
        framePumpJob?.cancel()
        framePumpJob = scope.launch {
            while (isActive && _state.value.isLiveActive) {
                // 1) fresh UI tree with coordinates -> memory (every tick)
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        AccessibilityAutomationBus.serviceInstance?.refreshSnapshotNow()
                    }
                } catch (_: Exception) {}
                // 2) real screenshot; sent when the screen changed (or every 2.5s as a heartbeat)
                if (_state.value.isScreenStreamEnabled) {
                    ScreenCaptureSessionBus.captureBestAvailableScreenFrame { bmp ->
                        lastFrameForBrain = bmp
                        updateLatestScreenFrame(bmp)
                        if (_state.value.isWebSocketConnected && setupDone) {
                            val sig = frameSignature(bmp)
                            val now = System.currentTimeMillis()
                            val prev = lastSentSig
                            val changed = prev == null || sigDiff(prev, sig) > 6
                            if (changed || now - lastSentAt > 2500L) {
                                lastSentSig = sig
                                lastSentAt = now
                                sendRealtimeImageFrameOverWs(bmp)
                            }
                        }
                    }
                }
                if (_state.value.isCameraStreamEnabled && _state.value.isWebSocketConnected) {
                    latestCameraBitmap?.let { camBmp -> sendRealtimeImageFrameOverWs(camBmp) }
                }
                delay(500L)
            }
        }
    }

    private fun frameSignature(bmp: Bitmap): IntArray {
        val t = Bitmap.createScaledBitmap(bmp, 12, 12, false)
        val out = IntArray(144)
        for (y in 0 until 12) for (x in 0 until 12) {
            val c = t.getPixel(x, y)
            out[y * 12 + x] = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
        }
        if (t !== bmp) t.recycle()
        return out
    }

    private fun sigDiff(a: IntArray, b: IntArray): Int {
        var sum = 0L
        for (i in a.indices) sum += kotlin.math.abs(a[i] - b[i])
        return (sum / a.size).toInt()
    }

    /** After every action: push fresh frames right away so the agent SEES whether it worked. */
    private fun forceFrameBurst() {
        scope.launch {
            repeat(2) {
                ScreenCaptureSessionBus.captureBestAvailableScreenFrame { bmp ->
                    lastFrameForBrain = bmp
                    updateLatestScreenFrame(bmp)
                    lastSentSig = frameSignature(bmp)
                    lastSentAt = System.currentTimeMillis()
                    sendRealtimeImageFrameOverWs(bmp)
                }
                delay(1200L)
            }
        }
    }

    private fun connectDirectBidiWebSocketIfPossible() {
        val url = _state.value.exportConfig.fullWebsocketUrlWithKey
        if (url.isBlank() || url.endsWith("YOUR_GEMINI_LIVE_API_KEY")) {
            _state.value = _state.value.copy(
                statusMessageBn = "উপরে চাবি (Key) আইকনে ট্যাপ করে আপনার Gemini API Key দিন"
            )
            onLiveSpokenChunkOrReply("স্যার, উপরে বাম পাশের চাবি আইকনে ট্যাপ করে আপনার Gemini API Key দিন।", false)
            return
        }
        scope.launch {
            disconnectWebSocket()
            setupDone = false
            val request = Request.Builder().url(url).build()
            activeWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    activeWebSocket = webSocket
                    _state.value = _state.value.copy(
                        isWebSocketConnected = true,
                        connectionStatusLabel = "CONNECTING • $wsBidiModel",
                        activeModelName = wsBidiModel
                    )
                    webSocket.send(buildSetupJson())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.isNotBlank()) handleIncomingWebSocketJson(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val t = try { bytes.utf8() } catch (_: Exception) { "" }
                    if (t.trimStart().startsWith("{")) handleIncomingWebSocketJson(t)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    handleSocketDown(webSocket, "${t.message ?: "network error"}")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    try { webSocket.close(code, reason) } catch (_: Exception) {}
                    handleSocketDown(webSocket, "$code $reason")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    handleSocketDown(webSocket, "$code $reason")
                }
            })
        }
    }

    private fun handleSocketDown(ws: WebSocket, reason: String) {
        if (ws !== activeWebSocket) return
        val wasSetup = setupDone
        setupDone = false
        activeWebSocket = null
        _state.value = _state.value.copy(
            isWebSocketConnected = false,
            isAgentSpeakingLive = false,
            liveRmsLevel = 0f
        )
        stopAndFlushLiveAudioTrack()
        if (!_state.value.isLiveActive) return
        Log.w(TAG, "Live socket down (setup=$wasSetup): $reason")
        scope.launch {
            if (!wasSetup && modelIndex < liveModelCandidates.size - 1) {
                modelIndex++
            } else if (reconnectAttempts >= 6) {
                val errMsg = "লাইভ সংযোগ হয়নি: ${reason.take(65)} (API Key / ইন্টারনেট চেক করুন)"
                _state.value = _state.value.copy(statusMessageBn = errMsg)
                onLiveSpokenChunkOrReply(errMsg, false)
                return@launch
            } else {
                reconnectAttempts++
            }
            _state.value = _state.value.copy(statusMessageBn = "পুনরায় সংযোগ হচ্ছে…")
            delay(700L)
            if (_state.value.isLiveActive && activeWebSocket == null) {
                connectDirectBidiWebSocketIfPossible()
            }
        }
    }

    private fun handleIncomingWebSocketJson(rawJson: String) {
        try {
            val root = JSONObject(rawJson)

            if (root.has("setupComplete")) {
                setupDone = true
                reconnectAttempts = 0
                _state.value = _state.value.copy(
                    connectionStatusLabel = "LIVE • ${wsBidiModel.removePrefix("models/")}",
                    statusMessageBn = "শুনছি… সরাসরি কথা বলুন"
                )
                pendingTextTurn?.let { queuedText ->
                    pendingTextTurn = null
                    sendClientContentTurnOverWs(queuedText)
                }
                return
            }
            root.optJSONObject("toolCall")?.let { handleToolCall(it); return }
            if (root.has("goAway")) {
                activeWebSocket?.let { handleSocketDown(it, "goAway") }
                return
            }

            val sc = root.optJSONObject("serverContent") ?: return

            if (sc.optBoolean("interrupted", false)) {
                stopAndFlushLiveAudioTrack()
                modelTurnBuf.setLength(0)
                _state.value = _state.value.copy(
                    isAgentSpeakingLive = false,
                    isUserSpeakingBargeIn = true,
                    liveRmsLevel = 0.12f,
                    bargeInInterruptCount = _state.value.bargeInInterruptCount + 1,
                    statusMessageBn = "শুনছি… বলুন স্যার"
                )
                onBargeInInterruptRequested()
                return
            }

            sc.optJSONObject("inputTranscription")?.optString("text", "")?.takeIf { it.isNotBlank() }?.let { chunk ->
                userTurnBuf.append(chunk)
                val fullUserText = userTurnBuf.toString().trim()
                onUserTranscript(fullUserText)
                checkIfUserAskedForScreenShare(fullUserText)
            }
            sc.optJSONObject("outputTranscription")?.optString("text", "")?.takeIf { it.isNotBlank() }?.let { chunk ->
                modelTurnBuf.append(chunk)
                val fullModelText = modelTurnBuf.toString().trim()
                _state.value = _state.value.copy(liveStreamingPartialReply = fullModelText)
                onLiveSpokenChunkOrReply(fullModelText, false)
            }

            val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val partObj = parts.optJSONObject(i) ?: continue
                    val partText = partObj.optString("text", "").trim()
                    if (partText.isNotBlank() && !partObj.optBoolean("thought", false)) {
                        if (modelTurnBuf.isEmpty()) {
                            modelTurnBuf.append(partText)
                            _state.value = _state.value.copy(liveStreamingPartialReply = partText)
                            onLiveSpokenChunkOrReply(partText, false)
                        }
                    }
                    val inline = partObj.optJSONObject("inlineData")
                    if (inline != null) {
                        val b64 = inline.optString("data", "")
                        if (inline.optString("mimeType", "").startsWith("audio/pcm") && b64.isNotBlank()) {
                            receivedAudioInTurn = true
                            enqueuePcm24kHzAudioChunk(b64)
                        }
                    }
                }
            }

            if (sc.optBoolean("turnComplete", false)) {
                val finalUser = userTurnBuf.toString().trim()
                val finalModel = modelTurnBuf.toString().trim()
                val hadPcmAudio = receivedAudioInTurn
                receivedAudioInTurn = false
                userTurnBuf.setLength(0)
                modelTurnBuf.setLength(0)
                if (finalModel.isNotBlank()) {
                    if (!hadPcmAudio && !_state.value.isSpeakerMuted) {
                        onLiveSpokenChunkOrReply(finalModel, true)
                    }
                    onExecuteLiveActionTag(finalUser, finalModel)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WS JSON parse warning: ${e.message}")
        }
    }

    @Volatile
    private var receivedAudioInTurn = false

    private fun checkIfUserAskedForScreenShare(transcript: String) {
        val lower = transcript.lowercase()
        val asksScreen = lower.contains("স্ক্রিনে দেখ") ||
            lower.contains("সক্রিনে দেখ") ||
            lower.contains("স্ক্রিন দেখ") ||
            lower.contains("সক্রিন দেখ") ||
            lower.contains("স্ক্রিন শেয়ার") ||
            lower.contains("সক্রিন সেয়ার") ||
            lower.contains("সক্রিন শেয়ার") ||
            lower.contains("screen share") ||
            lower.contains("look at screen") ||
            lower.contains("look at my screen")
        if (asksScreen && !_state.value.isScreenStreamEnabled) {
            enableScreenStreamNow()
            if (!ScreenCaptureSessionBus.state.value.hasMediaProjectionGrant &&
                !AccessibilityAutomationBus.state.value.isServiceEnabled
            ) {
                ScreenCaptureSessionBus.requestOneTimeScreenCapturePermission()
            }
        }
    }

    /** Gemini asked to run a phone action -> execute it on-device and send the result back. */
    private fun handleToolCall(tc: JSONObject) {
        val calls = tc.optJSONArray("functionCalls") ?: return
        scope.launch {
            val responses = JSONArray()
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                val args = c.optJSONObject("args") ?: JSONObject()
                val toolName = c.optString("name")
                val tag = "[[ACTION:${args.optString("action").uppercase()}|${args.optString("target")}|${args.optString("extra")}]]"
                val result = try {
                    withTimeout(25_000L) {
                        if (toolName == "phone_action") toolCallHandler?.invoke(tag) ?: "no handler"
                        else namedToolHandler?.invoke(toolName, args) ?: "no handler"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "error: ${e.message}"
                }
                recordLiveToolExecution("${c.optString("name")} ${args.optString("action")}: ${result.take(60)}")
                responses.put(JSONObject().apply {
                    put("id", c.optString("id"))
                    put("name", c.optString("name"))
                    put("response", JSONObject().put("result", result.take(1500)))
                })
            }
            forceFrameBurst()
            try {
                activeWebSocket?.send(JSONObject().put("toolResponse",
                    JSONObject().put("functionResponses", responses)).toString())
            } catch (_: Exception) {}
        }
    }

    private fun enqueuePcm24kHzAudioChunk(base64Pcm: String) {
        if (_state.value.isSpeakerMuted) return
        try {
            val pcmBytes = Base64.decode(base64Pcm, Base64.DEFAULT)
            if (pcmBytes.isNotEmpty()) {
                lastAudioChunkPlayedAtMs = System.currentTimeMillis()
                _state.value = _state.value.copy(
                    isAgentSpeakingLive = true,
                    isUserSpeakingBargeIn = false,
                    streamedAudioPackets = _state.value.streamedAudioPackets + 1
                )
                pcmPlaybackQueue.offer(pcmBytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PCM decode skip: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun routeAudioToMainLoudspeaker(track: AudioTrack?) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            // Ensure phone is in normal media mode (never trapped in earpiece communication mode)
            if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
                am.mode = AudioManager.MODE_NORMAL
            }
            // Check if user has external headphones/Bluetooth connected
            val outputs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            } else {
                emptyList()
            }
            val hasExternalHeadset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                outputs.any { dev ->
                    dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        dev.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
            } else {
                false
            }
            if (!hasExternalHeadset) {
                am.isSpeakerphoneOn = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && track != null) {
                    val mainSpeaker = outputs.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (mainSpeaker != null) {
                        track.setPreferredDevice(mainSpeaker)
                    }
                }
            }
            // Ensure STREAM_MUSIC volume is audible so Gemini Live is clearly heard
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (maxVol > 0 && curVol == 0) {
                val targetVol = (maxVol * 0.65f).toInt().coerceAtLeast(1)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Loudspeaker routing note: ${e.message}")
        }
    }

    private fun ensureAudioTrackInitialized(): AudioTrack? {
        synchronized(audioTrackLock) {
            var track = liveOutputAudioTrack
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                val sampleRate = 24000
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4800)

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                try {
                    track.setVolume(AudioTrack.getMaxVolume())
                } catch (_: Exception) {
                }
                routeAudioToMainLoudspeaker(track)
                liveOutputAudioTrack = track
            }
            return track
        }
    }

    fun stopAndFlushLiveAudioTrack() {
        pcmPlaybackQueue.clear()
        synchronized(audioTrackLock) {
            val track = liveOutputAudioTrack ?: return
            try {
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                    track.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun sendRealtimeAudioChunkOverWs(pcm16kBytes: ByteArray) {
        val ws = activeWebSocket ?: return
        if (!setupDone || pcm16kBytes.isEmpty()) return
        try {
            val b64 = Base64.encodeToString(pcm16kBytes, Base64.NO_WRAP)
            ws.send(JSONObject().put("realtimeInput", JSONObject().put("audio",
                JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", b64))).toString())
        } catch (_: Exception) {
        }
    }

    private fun sendRealtimeImageFrameOverWs(bitmap: Bitmap) {
        val ws = activeWebSocket ?: return
        if (!setupDone) return
        try {
            val b64 = encodeBitmapToBase64Jpeg(bitmap)
            ws.send(JSONObject().put("realtimeInput", JSONObject().put("video",
                JSONObject().put("mimeType", "image/jpeg").put("data", b64))).toString())
            _state.value = _state.value.copy(framesSentCount = _state.value.framesSentCount + 1)
        } catch (_: Exception) {
        }
    }

    private fun sendClientContentTurnOverWs(textPayload: String) {
        val ws = activeWebSocket ?: return
        if (!setupDone) return
        try {
            if (_state.value.isScreenStreamEnabled) {
                ScreenCaptureSessionBus.captureBestAvailableScreenFrame { bmp ->
                    latestScreenBitmap = bmp
                    sendRealtimeImageFrameOverWs(bmp)
                }
            }
            if (_state.value.isCameraStreamEnabled) {
                latestCameraBitmap?.let { sendRealtimeImageFrameOverWs(it) }
            }
            val clientTurn = JSONObject().apply {
                put(
                    "clientContent",
                    JSONObject().apply {
                        put(
                            "turns",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put(
                                        "parts",
                                        JSONArray().put(
                                            JSONObject().put("text", textPayload)
                                        )
                                    )
                                }
                            )
                        )
                        put("turnComplete", true)
                    }
                )
            }
            ws.send(clientTurn.toString())
        } catch (e: Exception) {
            Log.w(TAG, "WS clientContent send warning: ${e.message}")
        }
    }

    /**
     * Sends a typed or UI-triggered prompt directly through the single Gemini Live WebSocket.
     */
    fun sendLiveMultimodalTurn(
        userUtterance: String,
        contextualDeviceState: String,
        isFrontCamera: Boolean
    ) {
        stopAndFlushLiveAudioTrack()
        val combinedText = if (contextualDeviceState.isNotBlank()) {
            "$contextualDeviceState\nUser: $userUtterance"
        } else {
            userUtterance
        }

        if (!_state.value.isLiveActive) {
            pendingTextTurn = combinedText
            startLiveSession(_state.value.exportConfig.customUserApiKey)
            return
        }

        if (_state.value.isWebSocketConnected && setupDone) {
            sendClientContentTurnOverWs(combinedText)
        } else {
            pendingTextTurn = combinedText
            if (activeWebSocket == null) {
                connectDirectBidiWebSocketIfPossible()
            }
        }
    }

    private fun disconnectWebSocket() {
        try {
            activeWebSocket?.close(1000, "Session closed")
        } catch (_: Exception) {
        }
        activeWebSocket = null
    }

    private fun scaleBitmapForLiveStream(src: Bitmap): Bitmap {
        val maxDim = 420
        val w = src.width.coerceAtLeast(1)
        val h = src.height.coerceAtLeast(1)
        if (w <= maxDim && h <= maxDim) return src
        val ratio = maxDim.toFloat() / maxOf(w, h).toFloat()
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun encodeBitmapToBase64Jpeg(bitmap: Bitmap): String {
        val scaled = scaleBitmapForLiveStream(bitmap)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun release() {
        stopLiveSession()
        synchronized(audioTrackLock) {
            try {
                liveOutputAudioTrack?.release()
            } catch (_: Exception) {
            }
            liveOutputAudioTrack = null
        }
    }

    companion object {
        val LIVE_MULTIMODAL_SYSTEM_PROMPT = """
            You are "ARCHER AI" (পার্সোনাল মোবাইল এজেন্ট), a real-time Gemini Live voice and screen companion with a deep, clear, warm male voice ("Charon").
            CRITICAL LIVE RULES:
            1. REAL HUMAN CONVERSATIONAL VOICE (NEVER SOUND LIKE YOU ARE READING TEXT):
               - Speak naturally, warmly, and conversationally like a real human talking face-to-face with "স্যার" (or "Sir" in English). Never call the user "বস" or "Boss".
               - NEVER read out bullet points, numbered lists, markdown symbols (*, #, -), colons, or robotic announcements.
               - Speak in 1–2 short, natural spoken sentences (e.g., "জি স্যার, আমি স্ক্রিনে দেখছি...", "এখনি করে দিচ্ছি স্যার").
            2. LIVE SCREEN SHARE & 4-DIRECTIONAL TOUCH CONTROL:
               - When screen sharing is active, you receive real-time screen frames and can see everything on the user's screen.
               - When the user asks what is on the screen or how something works, explain it naturally like Gemini Live.
               - When the user asks to click, tap, scroll, or swipe in any direction (UP, DOWN, LEFT, RIGHT / উপরে, নিচে, ডানে, বামে), or open apps/settings/call contacts, call the `phone_action` tool immediately!
            3. REAL-TIME PHONE TOOL EXECUTION:
               - Call `phone_action(action, target, extra)` whenever the user wants an action performed on the phone:
                 * TOGGLE_SYSTEM_SETTING (WIFI, BLUETOOTH, MOBILE_DATA, AIRPLANE, HOTSPOT, LOCATION, AUTO_ROTATE, DND, BATTERY_SAVER, DARK_MODE, FLASHLIGHT | ON or OFF)
                 * SET_VOLUME (ALL, MUSIC, RING, ALARM, NOTIFICATION, VOICE_CALL, SYSTEM | 0..100, UP, DOWN, MAX, MUTE, UNMUTE)
                 * SCAN_CONTACT_OR_SCREEN (PersonName | CALL or WHATSAPP or SCREEN_TAP)
                 * SCREEN_UI_CONTROL (target_text_or_coords | CLICK, SCROLL_DOWN, SCROLL_UP, SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT, DOUBLE_TAP, LONG_PRESS, QUICK_SETTINGS, READ_SCREEN)
                 * OPEN_APP, INSTALL_APP_PLAYSTORE, PLAY_MUSIC, STOP_MUSIC, DIAL_NUMBER, OPEN_SETTINGS
        
            4. AUTONOMOUS MODE (VERY IMPORTANT):
               - You are ALWAYS watching the user's live screen. NEVER ask the user to share the screen or say "look at the screen" — just look and use what you see.
               - After the user gives a task, keep working step by step on your own (look -> act with phone_action -> look at the result -> next step) until it is finished. Do NOT stop to ask for confirmation between steps. Keep going until the user says stop/থামো; when they say stop, stop immediately and wait.
               - Each phone_action result includes the new screen text. Use it to decide the next action.
               - phone_action SCREEN_UI_CONTROL extras also include BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, READ_SCREEN.
               - To install an app: INSTALL_APP_PLAYSTORE, then watch the screen and tap Install/Open yourself.
            5. TOUCH EFFECT & APP SWITCHES: when the user asks to turn touch effect (টাচ ইফেক্ট) on/off, or screen share / camera / mic mute / speaker mute, call `app_control(feature, state)`. Understand loosely phrased requests yourself.
            6. ARGON 4 CODING: when the user asks you to write code, a program, a web page or an app, call `write_code(title, file_name, language, code)` with the COMPLETE code. The app shows a full-screen live typing page and saves the file in the 'archer' folder. Say one short sentence ("কোড লিখছি স্যার"), do NOT read the code aloud, and after saving tell the user in one sentence that it is saved in the archer folder.

            7. WATCH & VERIFY (CRITICAL): You see the screen continuously (fresh frames after every action). After EVERY action, LOOK at the new screen and check that it did what the user asked. If it went wrong (wrong app, wrong button, popup, error), undo/fix it immediately — never continue blindly and never claim success without seeing it.
            8. UI MEMORY: every phone_action result lists the visible elements with coordinates (label@x,y). Remember them. Before searching around, call `ui_memory(query)`; if the element is known, tap it directly with phone_action SCREEN_UI_CONTROL (target='x,y', extra='TAP_XY'). Use find_app to check an app exists. You know all installed apps (listed below) — open them by name.
            9. MUSIC: when asked to play a song/music, ALWAYS use phone_action PLAY_MUSIC (target = song name, extra = artist). It opens the user's real music app (Spotify / YouTube Music / etc.). NEVER Google it, never use WEB_SEARCH for songs, never play ringtones or built-in sounds. Then watch the screen; if a result list is shown, tap the first matching song.
            10. WEB & APPS: for web info use the browser app on screen and read it; for anything inside an app, open that app directly. Use WEB_SEARCH only for real web questions, never for opening apps or music.
            11. HARD TASKS / STUCK: if a task has many steps or you failed twice, call `deep_think(goal)` — the most powerful Gemini model looks at the screen and returns a plan. Then execute it step by step.
""".trimIndent()
    }
}
