package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AgentDatabase
import com.example.data.AgentMemoryNoteEntity
import com.example.data.CommandHistoryEntity
import com.example.data.CommandHistoryRepository
import com.example.data.PlatformAccountEntity
import com.example.data.TodayTaskEntity
import com.example.model.AgentActionType
import com.example.model.AgentPlanResponse
import com.example.model.AgentStep
import com.example.model.CuratedTrack
import com.example.model.HeadlineItem
import com.example.model.JarvisNodeModal
import com.example.model.MusicMood
import com.example.model.SmartRoutine
import com.example.model.WakeWordPhase
import com.example.service.AccessibilityAutomationBus
import com.example.service.AccessibilityEngineState
import com.example.service.BuiltInMusicEngine
import com.example.service.CubeSentinelBus
import com.example.service.CubeSentinelState
import com.example.service.CubeWakeWordService
import com.example.service.DeviceControlExecutor
import com.example.service.DeviceHardwareSnapshot
import com.example.service.MusicPlayerState
import com.example.service.NotificationHubState
import com.example.service.NotificationReplyBus
import com.example.service.VoiceAgentBridge
import com.example.service.VoiceBridgeState
import com.example.service.WakeWordEvent
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "OmniAgentViewModel"
private const val PREFS_NAME = "archer_cube_prefs"
private const val KEY_CUSTOM_GEMINI_API_KEY = "custom_gemini_api_key"
private const val KEY_LOCKED_REPLY_LANGUAGE = "locked_reply_language"
private const val KEY_PERSISTENT_USER_RULE = "persistent_user_rule"

sealed interface AgentUiState {
    data object Idle : AgentUiState
    data class Thinking(val commandText: String) : AgentUiState
    data class Success(val plan: AgentPlanResponse) : AgentUiState
    data class Error(val message: String) : AgentUiState
}

class OmniAgentViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs by lazy {
        application.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val historyRepo: CommandHistoryRepository by lazy {
        val db = AgentDatabase.getInstance(application.applicationContext)
        CommandHistoryRepository(
            historyDao = db.commandHistoryDao(),
            memoryDao = db.agentMemoryNoteDao(),
            taskDao = db.todayTaskDao(),
            platformDao = db.platformAccountDao()
        )
    }

    val musicEngine = BuiltInMusicEngine()
    val codingAndContactsManager = com.example.service.ArcherCodingAndContactsManager(application.applicationContext)
    val deviceExecutor = DeviceControlExecutor(
        context = application.applicationContext,
        musicEngine = musicEngine,
        codingAndContactsManager = codingAndContactsManager,
        onSaveMemoryNoteCallback = { title, content, category ->
            viewModelScope.launch {
                historyRepo.saveMemoryNote(title, content, category)
            }
        },
        onScheduleTaskCallback = { title, prompt, tag ->
            viewModelScope.launch {
                historyRepo.addTask(title, prompt, tag)
            }
        },
        onUpdateAgentRuleCallback = { ruleType, ruleValue ->
            applyPersistentRuleUpdate(ruleType, ruleValue)
        }
    )

    lateinit var multimodalLiveService: com.example.service.MultimodalLiveService
        private set

    val voiceBridge: VoiceAgentBridge = VoiceAgentBridge(
        context = application.applicationContext,
        onWakeWordGreetingTriggered = { rawWakePhrase, greetingText ->
            onWakeGreetingSpoken(rawWakePhrase, greetingText)
        },
        onPartialTranscriptChanged = { partial ->
            if (partial.isNotBlank()) {
                _liveUserTranscript.value = partial
            }
        },
        onFinalTranscript = { recognizedText, wasWokenByCube ->
            processUserCommand(recognizedText, triggeredByWakeWord = wasWokenByCube)
        },
        onSpeechPlayingStateChanged = { isSpeaking ->
            if (::multimodalLiveService.isInitialized) {
                multimodalLiveService.notifyAgentSpeechState(isSpeaking)
            }
        }
    )

    init {
        multimodalLiveService = com.example.service.MultimodalLiveService(
            context = application.applicationContext,
            onLiveSpokenChunkOrReply = { textChunk, speakImmediately ->
                _liveArcherReply.value = textChunk
                if (speakImmediately && textChunk.isNotBlank()) {
                    voiceBridge.speak(textChunk)
                }
            },
            onExecuteLiveActionTag = { userUtterance, rawAiResponse ->
                handleLiveMultimodalTurnResult(userUtterance, rawAiResponse)
            },
            onBargeInInterruptRequested = {
                voiceBridge.stopSpeaking()
            },
            onUserTranscript = { text -> _liveUserTranscript.value = text }
        )
        multimodalLiveService.setToolCallHandler { tag -> executeLiveToolTag(tag) }
        multimodalLiveService.setNamedToolHandler { name, args -> executeNamedLiveTool(name, args) }
    }

    /** Runs a phone action requested by Gemini Live (function call) and returns a short result string. */
    private suspend fun executeLiveToolTag(tag: String): String {
        val plan = buildPlanFromAiResponse(
            userPrompt = "",
            effectiveCommand = "",
            rawResponse = tag,
            triggeredByWakeWord = true,
            modelUsed = multimodalLiveState.value.activeModelName
        )
        if (plan.steps.isEmpty()) return "action not recognized"
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val sb = StringBuilder()
            plan.steps.forEachIndexed { index, step ->
                val r = deviceExecutor.executeStep(step, launchExternalIntents = index == 0)
                sb.append(r).append("; ")
            }
            sb.toString().trim()
        }.let { base ->
            kotlinx.coroutines.delay(900L)   // let the screen settle, then report what is on it now
            val st = com.example.service.AccessibilityAutomationBus
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { st.serviceInstance?.refreshSnapshotNow() }
            val pkg = com.example.service.ScreenMemory.currentPkg
            base + " | NOW ON SCREEN (" + pkg + "): " + st.state.value.visibleScreenText.take(500) +
                " | ELEMENTS(label@x,y): " + com.example.service.ScreenMemory.describeCurrent(28)
        }
    }

    /** app_control + write_code tools coming from Gemini Live. */
    private suspend fun executeNamedLiveTool(name: String, args: org.json.JSONObject): String {
        val ctx = getApplication<Application>().applicationContext
        return when (name) {
            "app_control" -> {
                val on = args.optString("state").trim().uppercase() in setOf("ON", "TRUE", "1", "ENABLE")
                when (args.optString("feature").trim().uppercase()) {
                    "TOUCH_EFFECT" -> { com.example.service.TouchEffectBus.set(ctx, on); "touch effect ${if (on) "ON" else "OFF"}" }
                    "SCREEN_SHARE" -> {
                        if (multimodalLiveState.value.isScreenStreamEnabled != on) toggleLiveScreenStream()
                        "screen share ${if (on) "ON" else "OFF"}"
                    }
                    "CAMERA" -> {
                        if (multimodalLiveState.value.isCameraStreamEnabled != on) toggleLiveCameraStream()
                        "camera ${if (on) "ON" else "OFF"}"
                    }
                    "MIC_MUTE" -> {
                        if (multimodalLiveState.value.isMicMuted != on) multimodalLiveService.toggleMicMute()
                        "mic ${if (on) "muted" else "unmuted"}"
                    }
                    "SPEAKER_MUTE" -> { multimodalLiveService.setSpeakerMuted(on); "speaker ${if (on) "muted" else "on"}" }
                    else -> "unknown feature"
                }
            }
            "ui_memory" -> {
                val hits = com.example.service.ScreenMemory.lookup(ctx, args.optString("query"), args.optString("app"))
                if (hits.isEmpty()) "not remembered yet — look at the screen / scroll"
                else hits.joinToString("; ") { (pkg, label, e) -> "$label@${e.cx},${e.cy} in $pkg" }
            }
            "find_app" -> {
                val hits = com.example.service.InstalledAppsIndex.search(ctx, args.optString("query"))
                if (hits.isEmpty()) "not installed" else hits.joinToString("; ") { "${it.label} (${it.pkg})" }
            }
            "deep_think" -> {
                val key = multimodalLiveService.currentApiKey()
                val goal = args.optString("goal")
                val screenInfo = "Foreground app: ${com.example.service.ScreenMemory.currentPkg}. Elements: ${com.example.service.ScreenMemory.describeCurrent(40)}"
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.service.GeminiBrainClient.think(key, "GOAL: $goal\n$screenInfo", multimodalLiveService.lastFrameForBrain)
                }
            }
            "write_code" -> {
                val title = args.optString("title").ifBlank { "archer_project" }
                val file = args.optString("file_name").ifBlank { "main.txt" }
                val lang = args.optString("language")
                val code = args.optString("code")
                if (code.isBlank()) return "no code received"
                val id = "code_" + System.currentTimeMillis()
                com.example.service.CodingSessionBus.start(
                    com.example.service.CodingSession(id = id, title = title, fileName = file, language = lang, code = code)
                )
                val artifact = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    codingAndContactsManager.saveGeneratedCodeToArcherFolder(
                        projectTitle = title, preferredFileName = file, language = lang, rawCodeOrResponse = code
                    )
                }
                com.example.service.CodingSessionBus.markSaved(artifact.singleFilePath)
                "saved in archer folder: ${artifact.singleFilePath} (+ folder and zip)"
            }
            else -> "unknown tool"
        }
    }

    private val _uiState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    // Live real-time transcript states so YOU and ARCHER update instantaneously
    private val _liveUserTranscript = MutableStateFlow("")
    val liveUserTranscript: StateFlow<String> = _liveUserTranscript.asStateFlow()

    private val _liveArcherReply = MutableStateFlow("জি স্যার, আমি প্রস্তুত। যেকোনো প্রশ্ন বা আদেশ বলুন।")
    val liveArcherReply: StateFlow<String> = _liveArcherReply.asStateFlow()

    private val _autoExecute = MutableStateFlow(true)
    val autoExecute: StateFlow<Boolean> = _autoExecute.asStateFlow()

    private val _activeModal = MutableStateFlow(JarvisNodeModal.NONE)
    val activeModal: StateFlow<JarvisNodeModal> = _activeModal.asStateFlow()

    private val _selectedHeadline = MutableStateFlow<HeadlineItem?>(null)
    val selectedHeadline: StateFlow<HeadlineItem?> = _selectedHeadline.asStateFlow()

    private val _isFrontCameraSelected = MutableStateFlow(true)
    val isFrontCameraSelected: StateFlow<Boolean> = _isFrontCameraSelected.asStateFlow()

    private val _geminiApiKeyState = MutableStateFlow(
        prefs.getString(KEY_CUSTOM_GEMINI_API_KEY, "")?.trim().orEmpty()
    )
    val geminiApiKeyState: StateFlow<String> = _geminiApiKeyState.asStateFlow()

    private val _lockedReplyLanguage = MutableStateFlow(
        prefs.getString(KEY_LOCKED_REPLY_LANGUAGE, "AUTO")?.trim().orEmpty().ifBlank { "AUTO" }
    )
    val lockedReplyLanguage: StateFlow<String> = _lockedReplyLanguage.asStateFlow()

    private val _persistentUserRule = MutableStateFlow(
        prefs.getString(KEY_PERSISTENT_USER_RULE, "স্যারের যেকোনো আদেশ ও নিয়ম মাথায় রেখে কাজ করবে")?.trim().orEmpty()
    )
    val persistentUserRule: StateFlow<String> = _persistentUserRule.asStateFlow()

    private val _agentPlans = MutableStateFlow<List<AgentPlanResponse>>(defaultWelcomePlan())
    val agentPlans: StateFlow<List<AgentPlanResponse>> = _agentPlans.asStateFlow()

    val musicState: StateFlow<MusicPlayerState> = musicEngine.state
    val hardwareState: StateFlow<DeviceHardwareSnapshot> = deviceExecutor.hardwareState
    val voiceState: StateFlow<VoiceBridgeState> = voiceBridge.state
    val sentinelState: StateFlow<CubeSentinelState> = CubeSentinelBus.state
    val accessibilityState: StateFlow<AccessibilityEngineState> = AccessibilityAutomationBus.state
    val notificationState: StateFlow<NotificationHubState> = NotificationReplyBus.state
    val codingWorkspaceState: StateFlow<com.example.service.CodingWorkspaceState> = codingAndContactsManager.workspaceState
    val multimodalLiveState: StateFlow<com.example.service.MultimodalLiveSessionState> = multimodalLiveService.state
    val screenCaptureSessionState: StateFlow<com.example.service.ScreenCaptureSessionState> =
        com.example.service.ScreenCaptureSessionBus.state

    val commandHistory: StateFlow<List<CommandHistoryEntity>> = historyRepo.historyFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val memoryNotes: StateFlow<List<AgentMemoryNoteEntity>> = historyRepo.memoryNotesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val todayTasks: StateFlow<List<TodayTaskEntity>> = historyRepo.todayTasksFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val platformAccounts: StateFlow<List<PlatformAccountEntity>> = historyRepo.platformAccountsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val headlines: List<HeadlineItem> = DEFAULT_HEADLINES

    private var activeCommandJob: Job? = null
    private val recentConversationTurns = ArrayDeque<Pair<String, String>>(6)

    init {
        val ctx = application.applicationContext
        com.example.service.ExternalMicArbiter.init(ctx)
        com.example.service.ExternalMicArbiter.setGeminiLiveOwningMic(true)

        // Keep old STT/TTS off so Gemini Live exclusively owns the microphone and speaker
        voiceBridge.setHandsFreeContinuousMode(false)
        voiceBridge.stopListening(keepSentinelPhase = false)

        viewModelScope.launch {
            seedInitialDataIfEmpty()
        }

        // Initialize and start Gemini Live immediately as the main engine connected to the Main Globe
        multimodalLiveService.refreshExportConfig(_geminiApiKeyState.value)
        multimodalLiveService.startLiveSession(_geminiApiKeyState.value)
    }

    fun updateGeminiApiKey(newKey: String) {
        val cleaned = newKey.trim()
        prefs.edit().putString(KEY_CUSTOM_GEMINI_API_KEY, cleaned).apply()
        _geminiApiKeyState.value = cleaned
        multimodalLiveService.refreshExportConfig(cleaned)
        multimodalLiveService.startLiveSession(cleaned)
        _liveArcherReply.value = "জি স্যার, Gemini Live কানেক্ট হচ্ছে..."
    }

    /**
     * Direct Main Globe interaction:
     * - If Gemini Live is not active or disconnected -> starts/reconnects Gemini Live.
     * - If Gemini Live is speaking -> instant barge-in so the user can speak immediately.
     * - If mic was muted -> unmutes the mic immediately.
     */
    fun onMainGlobeTap() {
        val live = multimodalLiveState.value
        when {
            !live.isLiveActive -> {
                com.example.service.ExternalMicArbiter.setGeminiLiveOwningMic(true)
                voiceBridge.stopSpeaking()
                voiceBridge.stopListening(keepSentinelPhase = false)
                multimodalLiveService.startLiveSession(_geminiApiKeyState.value)
                _liveArcherReply.value = "শুনছি… সরাসরি কথা বলুন।"
            }
            live.isAgentSpeakingLive -> {
                multimodalLiveService.setMicMuted(false)
                multimodalLiveService.triggerBargeInInterrupt()
                _liveArcherReply.value = "বলুন স্যার—আমি শুনছি..."
            }
            live.isMicMuted -> {
                multimodalLiveService.setMicMuted(false)
                _liveArcherReply.value = "মাইক সক্রিয় — কথা বলুন..."
            }
            !live.isWebSocketConnected -> {
                multimodalLiveService.startLiveSession(_geminiApiKeyState.value)
                _liveArcherReply.value = "পুনরায় সংযোগ হচ্ছে..."
            }
            else -> {
                multimodalLiveService.triggerBargeInInterrupt()
                _liveArcherReply.value = "শুনছি… সরাসরি কথা বলুন।"
            }
        }
    }

    fun toggleMultimodalLiveSession() {
        onMainGlobeTap()
    }

    fun selectLiveEngineMode(mode: com.example.service.LiveConnectionEngine) {
        multimodalLiveService.setEngineMode(mode)
        _liveArcherReply.value = multimodalLiveState.value.statusMessageBn
    }

    fun toggleLiveMicMute() {
        val muted = multimodalLiveService.toggleMicMute()
        _liveArcherReply.value = if (muted) {
            "মাইক্রোফোন পজ করা হয়েছে — কথা বলতে গ্লোবে ট্যাপ করুন।"
        } else {
            "শুনছি… সরাসরি কথা বলুন।"
        }
    }

    fun toggleLiveSpeakerMute() {
        multimodalLiveService.toggleSpeakerMute()
    }

    fun toggleLiveCameraStream() {
        val enabled = multimodalLiveService.toggleCameraStream()
        if (enabled && !multimodalLiveState.value.isLiveActive) {
            multimodalLiveService.startLiveSession(_geminiApiKeyState.value)
        }
        _liveArcherReply.value = multimodalLiveState.value.statusMessageBn
    }

    fun toggleLiveScreenStream() {
        val hasProjectionGrant = com.example.service.ScreenCaptureSessionBus.state.value.hasMediaProjectionGrant
        if (!hasProjectionGrant) {
            com.example.service.ScreenCaptureSessionBus.requestOneTimeScreenCapturePermission()
            _liveArcherReply.value = "জি স্যার, প্রথমবার স্ক্রিন শেয়ারের পারমিশন বক্সটি ওপেন করেছি—একবার Allow করে দিন, এরপর থেকে সরাসরি স্ক্রিন দেখব।"
            return
        }
        val next = multimodalLiveService.toggleScreenStream()
        if (next && !multimodalLiveState.value.isLiveActive) {
            multimodalLiveService.startLiveSession(_geminiApiKeyState.value)
        }
        _liveArcherReply.value = if (next) {
            "লাইভ স্ক্রিন শেয়ারিং চালু হয়েছে — এখন স্ক্রিন দেখে যেকোনো প্রশ্ন বা কাজ বলুন।"
        } else {
            "স্ক্রিন শেয়ারিং আপাতত পজ করা হয়েছে।"
        }
    }

    fun onOneTimeScreenCapturePermissionResult(resultCode: Int, data: android.content.Intent?) {
        val ctx = getApplication<Application>().applicationContext
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            com.example.service.PermanentScreenCaptureService.startWithProjectionGrant(ctx, resultCode, data)
            multimodalLiveService.enableScreenStreamNow()
            if (!multimodalLiveState.value.isLiveActive) {
                multimodalLiveService.startLiveSession(_geminiApiKeyState.value)
            }
            _liveArcherReply.value = "পার্মানেন্ট স্ক্রিন শেয়ারিং চালু হয়েছে! এখন সরাসরি কথা বলুন।"
        } else {
            if (accessibilityState.value.isServiceEnabled) {
                multimodalLiveService.enableScreenStreamNow()
                _liveArcherReply.value = "অ্যাক্সেসিবিলিটি ভিশনের মাধ্যমেই স্ক্রিন শেয়ারিং সক্রিয় হয়েছে।"
            } else {
                _liveArcherReply.value = "স্ক্রিন শেয়ারিং চালু করতে প্রথমবার Allow বাটনে ট্যাপ করুন।"
            }
        }
    }

    fun toggleLiveToolsArmed() {
        multimodalLiveService.toggleToolsArmed()
        _liveArcherReply.value = multimodalLiveState.value.statusMessageBn
    }

    fun triggerLiveBargeIn() {
        multimodalLiveService.triggerBargeInInterrupt()
        _liveArcherReply.value = "বলুন স্যার—আমি শুনছি..."
    }

    fun onLiveCameraBitmapCaptured(bitmap: android.graphics.Bitmap) {
        multimodalLiveService.updateLatestCameraFrame(bitmap)
    }

    fun copyLiveApiConfigItem(label: String, content: String) {
        val msg = multimodalLiveService.copyToClipboard(label, content)
        _liveArcherReply.value = msg
    }

    fun exportLiveApiBundleToArcherFolder() {
        viewModelScope.launch {
            val cfg = multimodalLiveState.value.exportConfig
            val bundleContent = buildString {
                appendLine("# ARCHER AI — Gemini Live Export Bundle")
                appendLine("# Project ID: ${cfg.projectId}")
                appendLine("# App ID: ${cfg.applicationId}")
                appendLine("# Package: ${cfg.packageName}")
                appendLine("# Effective Live API Key: ${cfg.effectiveApiKey}")
                appendLine("# WebSocket Endpoint: ${cfg.fullWebsocketUrlWithKey}")
            }
            val artifact = codingAndContactsManager.saveGeneratedCodeToArcherFolder(
                projectTitle = "archer_multimodal_live_api",
                preferredFileName = "archer_multimodal_live_client.py",
                language = "Python (WebSocket Bidi Live API)",
                rawCodeOrResponse = bundleContent
            )
            _liveArcherReply.value = "জি স্যার, কনফিগ ফাইল '${artifact.projectTitle}.zip' নামে সেভ করা হয়েছে!"
        }
    }

    private fun handleLiveMultimodalTurnResult(userUtterance: String, rawAiResponse: String) {
        viewModelScope.launch {
            val effectiveRawResponse = rawAiResponse.trim()
            if (effectiveRawResponse.isBlank()) return@launch

            val parsedPlan = buildPlanFromAiResponse(
                userPrompt = userUtterance,
                effectiveCommand = userUtterance,
                rawResponse = effectiveRawResponse,
                triggeredByWakeWord = true,
                modelUsed = multimodalLiveState.value.activeModelName
            )
            if (recentConversationTurns.size >= 6) {
                recentConversationTurns.removeFirst()
            }
            recentConversationTurns.addLast(userUtterance to parsedPlan.spokenReply)

            if (parsedPlan.spokenReply.isNotBlank()) {
                _liveArcherReply.value = parsedPlan.spokenReply
            }
            _agentPlans.value = listOf(parsedPlan) + _agentPlans.value
            _uiState.value = AgentUiState.Success(parsedPlan)
            if (_autoExecute.value && parsedPlan.steps.isNotEmpty()) {
                executePlanStepsIfEnabled(parsedPlan)
            }
            logPlanToHistory(userUtterance, parsedPlan)
        }
    }

    private suspend fun seedInitialDataIfEmpty() {
        val currentTasks = historyRepo.todayTasksFlow.first()
        if (currentTasks.isEmpty()) {
            historyRepo.addTask(
                title = "সিলেট जाने का प्लान बनाना",
                autoCommandPrompt = "সিলেট যাওয়ার ২ দিনের পূর্ণাঙ্গ প্ল্যান বানিয়ে দাও",
                platformTag = "PLANNER"
            )
            historyRepo.addTask(
                title = "Release desktop version 75",
                autoCommandPrompt = "ডেস্কটপ ভার্সন ৭৫ রিলিজের সংক্ষিপ্ত চেকলিস্ট বলো",
                platformTag = "SYSTEM"
            )
            historyRepo.addTask(
                title = "Upload video to TikTok",
                autoCommandPrompt = "TikTok অ্যাপ খোলো এবং নতুন ভিডিও আপলোডের জন্য প্রস্তুত করো",
                platformTag = "TIKTOK"
            )
        }

        val currentAccounts = historyRepo.platformAccountsFlow.first()
        if (currentAccounts.isEmpty()) {
            historyRepo.addPlatformAccount(
                platformName = "WhatsApp",
                contactOrGroupName = "রাহিম ভাই (অফিস)",
                handleOrNumberOrUrl = "+8801700000000",
                notes = "প্রজেক্ট টিম লিড"
            )
            historyRepo.addPlatformAccount(
                platformName = "Telegram",
                contactOrGroupName = "Tech & Movie Hub",
                handleOrNumberOrUrl = "@telegram",
                notes = "সফটওয়্যার, পিডিএফ ও মুভি ডাউনলোড চ্যানেল"
            )
            historyRepo.addPlatformAccount(
                platformName = "Chrome Site",
                contactOrGroupName = "GitHub & AI Resources",
                handleOrNumberOrUrl = "https://github.com",
                notes = "কোড ও অ্যাপ ডাউনলোড সাইট"
            )
        }
    }

    private val autonomousSystemInstruction = """
        You are "ARCHER AI" (Cube AI / পার্সোনাল মোবাইল এজেন্ট), an ultra-intelligent, warm, human-voiced autonomous Android OS controller and coding engineer powered by Argon 4 (`models/gemini-4-argon`) and Gemini 2.0 Flash Live (`models/gemini-2.0-flash-exp`).
        CRITICAL RULES:
        1. REAL HUMAN CONVERSATIONAL VOICE (NEVER SOUND LIKE YOU ARE READING WRITTEN TEXT):
           - Speak naturally, warmly, and conversationally like a real human talking face-to-face with "স্যার" (or "Sir" in English).
           - NEVER output bullet points, numbered lists, colons, or robotic announcements in your spoken reply. Keep spoken replies natural, warm, and concise (1–2 conversational sentences).
           - Understand ALL languages seamlessly: Bengali (বাংলা), Banglish, English, Hindi (हिन्दी), Urdu, Arabic, and ASR phonetic variations.
           - STRICTLY OBEY the user's `Active Language Lock` and `Active Persistent Rule` provided in the context!
        2. Always address the user respectfully as "স্যার" (or "Sir" when speaking English). NEVER call the user "বস" or "Boss".
        3. Answer any topic—general knowledge, coding, science, math, or device control—naturally and conversationally. If the user asks to write code, include the complete runnable code inside triple backticks ```language ... ``` AND append the GENERATE_CODE_PROJECT action tag.
        4. When the user's request requires a phone/system/app/screen/coding action, append ONE action tag on a new line at the very end of your response in this exact format:
           [[ACTION:ACTION_TYPE|target_or_query|extra_body]]
           Valid ACTION_TYPE values:
           - TOGGLE_SYSTEM_SETTING:
             * Use to turn ON or OFF any Notification Bar / Quick Settings tile or phone setting: `WIFI`, `BLUETOOTH`, `MOBILE_DATA` (সিমের নেট/ডাটা), `AIRPLANE` (এরোপ্লেন মোড), `HOTSPOT`, `LOCATION`, `AUTO_ROTATE`, `DND`, `BATTERY_SAVER`, `DARK_MODE`, `FLASHLIGHT`.
             * Set `target_or_query` to the setting name (e.g., `WIFI`, `MOBILE_DATA`, `AIRPLANE`, `BLUETOOTH`, `HOTSPOT`) and `extra_body` to `ON` or `OFF`.
             * Example: "ওয়াইফাই অফ করো" -> [[ACTION:TOGGLE_SYSTEM_SETTING|WIFI|OFF]]
             * Example: "সিমের নেট চালু করো" -> [[ACTION:TOGGLE_SYSTEM_SETTING|MOBILE_DATA|ON]]
             * Example: "এরোপ্লেন মোড অন করো" -> [[ACTION:TOGGLE_SYSTEM_SETTING|AIRPLANE|ON]]
           - SET_VOLUME:
             * Controls all 6 audio streams: `ALL` (সব সাউন্ড), `MUSIC` (মিডিয়া/গান), `RING` (রিংটোন), `ALARM` (অ্যালার্ম), `NOTIFICATION` (নোটিফিকেশন), `VOICE_CALL` (কল সাউন্ড), `SYSTEM`.
             * Format: `[[ACTION:SET_VOLUME|STREAM_NAME|PERCENT_OR_DIRECTION]]` where `PERCENT_OR_DIRECTION` is `0`..`100`, `UP`, `DOWN`, `MAX`, `MUTE`, or `UNMUTE`.
             * Example: "সব সাউন্ড ফুল করো" -> [[ACTION:SET_VOLUME|ALL|100]]
             * Example: "কল ভলিউম কমাও" -> [[ACTION:SET_VOLUME|VOICE_CALL|DOWN]]
           - SCAN_CONTACT_OR_SCREEN:
             * Use when the user mentions a person's name to Call, WhatsApp, Message, or Tap on screen (searches both Device Contacts and the entire visible screen!).
             * Format: `[[ACTION:SCAN_CONTACT_OR_SCREEN|PersonName|CALL or WHATSAPP or SCREEN_TAP]]`
           - GENERATE_CODE_PROJECT:
             * Use whenever the user asks to write code, build a website/script/app, or use Argon 4 for coding.
             * Format: `[[ACTION:GENERATE_CODE_PROJECT|project_title|filename.ext]]` (and include the full code block in your response so it is saved in the `archer` folder as a File, Folder, and ZIP!).
           - UPDATE_AGENT_RULE:
             * Use when the user sets a standing rule like "শুধু ইংরেজিতে কথা বলবে" (`ENGLISH_ONLY`), "শুধু বাংলায় বলবে" (`BENGALI_ONLY`), "হিন্দিতে বলবে" (`HINDI_ONLY`), or a custom behavior rule.
             * Format: `[[ACTION:UPDATE_AGENT_RULE|LANGUAGE_LOCK_OR_CUSTOM|ENGLISH_ONLY]]`
           - SCREEN_UI_CONTROL:
             * Format: `[[ACTION:SCREEN_UI_CONTROL|target_text_or_coords|CLICK or TYPE or SCROLL_DOWN or SCROLL_UP or TAP_CENTER or DOUBLE_TAP or LONG_PRESS or SWIPE_UP or SWIPE_DOWN or QUICK_SETTINGS or READ_SCREEN]]`
           - INSTALL_APP_PLAYSTORE (target = clean canonical English app name ONLY, extra_body = UPDATE or INSTALL)
           - OPEN_APP (target = app name like YouTube, WhatsApp, Telegram, Free Fire; extra_body = search query if any)
           - INSTALL_APK_PACKAGE, READ_NOTIFICATIONS, WHATSAPP_AUTOMATION, TELEGRAM_AUTOMATION, CHROME_WEB_ACTION, DOWNLOAD_FILE_MEDIA, PLAY_MUSIC, STOP_MUSIC, TOGGLE_FLASHLIGHT, DIAL_NUMBER, OPEN_CAMERA, OPEN_SETTINGS, WEB_SEARCH
    """.trimIndent()

    fun openModal(modal: JarvisNodeModal) {
        _activeModal.value = modal
    }

    fun closeModal() {
        _activeModal.value = JarvisNodeModal.NONE
    }

    fun openHeadlineDetail(headline: HeadlineItem) {
        _selectedHeadline.value = headline
        _activeModal.value = JarvisNodeModal.HEADLINE_DETAIL
        _liveArcherReply.value = "${headline.title} — ${headline.summaryBn}"
    }

    fun toggleCameraLens() {
        _isFrontCameraSelected.value = !_isFrontCameraSelected.value
    }

    fun stopAllAgentActivity() {
        activeCommandJob?.cancel()
        val live = multimodalLiveState.value
        if (live.isAgentSpeakingLive || musicState.value.isPlaying) {
            multimodalLiveService.triggerBargeInInterrupt()
            musicEngine.stop()
            _liveArcherReply.value = "শুনছি… সরাসরি কথা বলুন।"
        } else {
            toggleLiveMicMute()
        }
        _uiState.value = AgentUiState.Idle
    }

    fun speakTextAloud(text: String) {
        if (text.isBlank()) return
        _liveArcherReply.value = text
        voiceBridge.speak(text, forceSpeakWhenMuted = true)
    }

    fun replayLastAgentResponse() {
        val currentReply = _liveArcherReply.value.ifBlank {
            voiceBridge.state.value.lastSpokenText
        }
        if (currentReply.isNotBlank()) {
            voiceBridge.speak(currentReply, forceSpeakWhenMuted = true)
        } else {
            voiceBridge.replayLastSpoken()
        }
    }

    fun testTtsVoice() {
        val testMsg = "জি স্যার! আর্চার এআই ভয়েস ইঞ্জিন সম্পূর্ণ সচল আছে। আপনি বাংলা, ইংরেজি বা হিন্দিতে যেকোনো আদেশ করতে পারেন।"
        _liveArcherReply.value = testMsg
        voiceBridge.testTtsVoice()
    }

    fun setTtsSpeechRate(rate: Float) {
        voiceBridge.setSpeechRate(rate)
    }

    fun setTtsPitch(pitch: Float) {
        voiceBridge.setPitch(pitch)
    }

    fun setTtsVoiceProfile(profile: String) {
        voiceBridge.applyVoiceProfile(profile)
        val msg = when (profile.uppercase()) {
            "NATURAL_HUMAN", "NATURAL" -> if (_lockedReplyLanguage.value == "ENGLISH_ONLY") {
                "Yes Sir, I am now speaking in my natural human conversational voice."
            } else {
                "জি স্যার, এখন থেকে আমি একদম বাস্তব মানুষের মতো স্বাভাবিকভাবে কথা বলব।"
            }
            "DEEP_JARVIS" -> if (_lockedReplyLanguage.value == "ENGLISH_ONLY") {
                "Yes Sir, Deep and Clean Jarvis voice profile is now active."
            } else {
                "জি স্যার, ডিপ এবং ক্লিন জার্ভিস ভয়েস প্রোফাইল চালু করেছি।"
            }
            "ULTRA_DEEP" -> "জি স্যার, আলট্রা ডিপ কমান্ড ভয়েস চালু করেছি।"
            "CLEAN_STUDIO" -> "জি স্যার, ক্লিন স্টুডিও ভয়েস চালু করেছি।"
            else -> "জি স্যার, ন্যাচারাল হিউম্যান ভয়েস প্রোফাইল চালু করেছি।"
        }
        _liveArcherReply.value = msg
        voiceBridge.speak(msg, forceSpeakWhenMuted = true)
    }

    fun applyPersistentRuleUpdate(ruleType: String, ruleValue: String) {
        val upperType = ruleType.uppercase()
        val upperVal = ruleValue.uppercase()
        when {
            upperVal.contains("ENGLISH_ONLY") || upperType.contains("ENGLISH") -> {
                _lockedReplyLanguage.value = "ENGLISH_ONLY"
                prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, "ENGLISH_ONLY").apply()
            }
            upperVal.contains("BENGALI_ONLY") || upperType.contains("BENGALI") || upperVal.contains("BANGLA") -> {
                _lockedReplyLanguage.value = "BENGALI_ONLY"
                prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, "BENGALI_ONLY").apply()
            }
            upperVal.contains("HINDI_ONLY") || upperType.contains("HINDI") -> {
                _lockedReplyLanguage.value = "HINDI_ONLY"
                prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, "HINDI_ONLY").apply()
            }
            upperVal.contains("AUTO") || upperVal.contains("RESET") -> {
                _lockedReplyLanguage.value = "AUTO"
                prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, "AUTO").apply()
            }
            else -> {
                _persistentUserRule.value = ruleValue
                prefs.edit().putString(KEY_PERSISTENT_USER_RULE, ruleValue).apply()
            }
        }
    }

    fun setLockedReplyLanguage(langMode: String) {
        val clean = langMode.uppercase().trim()
        _lockedReplyLanguage.value = clean
        prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, clean).apply()
        val msg = when (clean) {
            "ENGLISH_ONLY" -> "Understood Sir. From now on, even if you speak in Bengali or Hindi, I will reply strictly in English."
            "BENGALI_ONLY" -> "জি স্যার, এখন থেকে আপনি যে ভাষাতেই বলুন না কেন, আমি শুধুমাত্র বাংলায় উত্তর দেব।"
            "HINDI_ONLY" -> "जी सर, अब से आप किसी भी भाषा में बोलें, मैं केवल हिंदी में उत्तर दूँगा।"
            else -> "জি স্যার, স্বয়ংক্রিয় সব ভাষা সাপোর্ট (বাংলা, বাংলিশ, ইংরেজি, হিন্দি) চালু করা হয়েছে।"
        }
        _liveArcherReply.value = msg
        voiceBridge.speak(msg)
    }

    fun setCustomPersistentRule(ruleText: String) {
        val clean = ruleText.trim()
        if (clean.isBlank()) return
        _persistentUserRule.value = clean
        prefs.edit().putString(KEY_PERSISTENT_USER_RULE, clean).apply()
        viewModelScope.launch {
            historyRepo.saveMemoryNote("স্যারের স্থায়ী নিয়ম", clean, "PERSISTENT_RULE")
        }
        val msg = if (_lockedReplyLanguage.value == "ENGLISH_ONLY") {
            "Yes Sir, I have locked your instruction in memory and will follow it strictly."
        } else {
            "জি স্যার, আপনার এই নির্দেশনাটি আমি মাথায় রেখেছি এবং এখন থেকে সবসময় এটি মেনে কাজ করব।"
        }
        _liveArcherReply.value = msg
        voiceBridge.speak(msg)
    }

    fun selectAiModel(modelId: String, autoRoute: Boolean = false) {
        codingAndContactsManager.setActiveModel(modelId, autoRoute)
        val msg = "জি স্যার, এআই মডেল পরিবর্তন করে $modelId সক্রিয় করা হয়েছে।"
        _liveArcherReply.value = msg
        voiceBridge.speak(msg)
    }

    fun toggleAutoModelRouting() {
        val autoOn = codingAndContactsManager.toggleAutoModelRouting()
        val msg = if (autoOn) {
            "জি স্যার, কাজের ধরন অনুযায়ী স্বয়ংক্রিয়ভাবে Argon 4 ও Gemini মডেল সুইচিং চালু হয়েছে।"
        } else {
            "জি স্যার, ম্যানুয়াল এআই মডেল সিলেকশন চালু হয়েছে।"
        }
        _liveArcherReply.value = msg
    }

    fun toggleHandsFreeVoiceMode() {
        if (_stateIsGeminiLivePrimary()) {
            val muted = multimodalLiveService.toggleMicMute()
            _liveArcherReply.value = if (muted) {
                "মাইক্রোফোন পজ করা হয়েছে — কথা বলতে গ্লোবে ট্যাপ করুন।"
            } else {
                "শুনছি… সরাসরি কথা বলুন।"
            }
            return
        }
        val next = voiceBridge.toggleHandsFreeContinuousMode()
        val msg = if (next) {
            "জি স্যার, হ্যান্ডস-ফ্রি ভয়েস মোড চালু হয়েছে। এখন থেকে টাইপ না করেই সরাসরি মুখে আদেশ করতে পারবেন।"
        } else {
            "জি স্যার, হ্যান্ডস-ফ্রি অটো-লিসেনিং বন্ধ করা হয়েছে।"
        }
        _liveArcherReply.value = msg
    }

    fun setHandsFreeVoiceMode(enabled: Boolean) {
        if (_stateIsGeminiLivePrimary()) {
            // Gemini Live owns the microphone continuously; keep legacy SpeechRecognizer disabled to avoid mic conflict
            voiceBridge.setHandsFreeContinuousMode(false)
            voiceBridge.stopListening(keepSentinelPhase = false)
            if (enabled) {
                multimodalLiveService.setMicMuted(false)
                if (!multimodalLiveState.value.isLiveActive) {
                    multimodalLiveService.startLiveSession(_geminiApiKeyState.value)
                }
            }
            return
        }
        voiceBridge.setHandsFreeContinuousMode(enabled)
    }

    private fun _stateIsGeminiLivePrimary(): Boolean {
        return com.example.service.ExternalMicArbiter.state.value.isGeminiLiveOwningMic ||
            multimodalLiveState.value.isLiveActive
    }

    fun cycleSttLanguage() {
        val nextCode = voiceBridge.toggleLanguage()
        val langName = when {
            nextCode.startsWith("bn") -> "বাংলা (bn-BD)"
            nextCode.startsWith("hi") -> "हिन्दी (hi-IN)"
            else -> "English (en-US)"
        }
        _liveArcherReply.value = "জি স্যার, ভয়েস রিকগনিশন ভাষা পরিবর্তন করে $langName করা হয়েছে।"
    }

    fun setSttLanguage(languageCode: String) {
        voiceBridge.setSttLanguage(languageCode)
    }

    fun onExternalSpeechRecognized(spokenText: String) {
        voiceBridge.onExternalSpeechResult(spokenText)
    }

    fun analyzeCurrentScreenAndSpeak() {
        val screenText = AccessibilityAutomationBus.state.value.visibleScreenText
        val recentMsgs = NotificationReplyBus.state.value.messages.take(2)
            .joinToString(" | ") { "${it.appLabel} (${it.senderOrTitle}): ${it.messageBody}" }
        val combinedPrompt = "বর্তমান স্ক্রিন ও মেসেজ দেখে তোমার নিজের বুদ্ধিতে বলো এখানে কী আছে এবং কী করা উচিত: স্ক্রিন=[$screenText], মেসেজ=[$recentMsgs]"
        processUserCommand(combinedPrompt)
    }

    fun toggleAutoExecute() {
        _autoExecute.value = !_autoExecute.value
    }

    fun toggleAlwaysOnCubeSentinel(enable: Boolean) {
        val ctx = getApplication<Application>().applicationContext
        if (enable) {
            CubeWakeWordService.startService(ctx)
            voiceBridge.setHandsFreeContinuousMode(true)
            voiceBridge.startListening(isManualCommandTap = false)
        } else {
            CubeWakeWordService.stopService(ctx)
            voiceBridge.setHandsFreeContinuousMode(false)
            voiceBridge.stopListening()
        }
    }

    fun simulateHeyCubeWakeCall() {
        CubeSentinelBus.emitWakeWordDetected()
    }

    private fun onWakeGreetingSpoken(rawWakePhrase: String, greetingText: String) {
        _liveUserTranscript.value = rawWakePhrase.ifBlank { "Hey Cube" }
        _liveArcherReply.value = greetingText
        val wakeCard = AgentPlanResponse(
            id = "wake_${System.currentTimeMillis()}",
            userPrompt = rawWakePhrase.ifBlank { "Hey Cube" },
            spokenReply = greetingText,
            summaryTitle = "ARCHER AI — ভয়েস কমান্ডের জন্য প্রস্তুত",
            steps = emptyList(),
            triggeredByWakeWord = true
        )
        _agentPlans.value = listOf(wakeCard) + _agentPlans.value
        _uiState.value = AgentUiState.Success(wakeCard)
    }

    fun toggleQuickSettingFromUi(settingTarget: String, enable: Boolean? = null) {
        viewModelScope.launch {
            val hw = deviceExecutor.hardwareState.value
            val resolvedEnable = enable ?: when (settingTarget.uppercase()) {
                "WIFI" -> !hw.isWifiEnabled
                "BLUETOOTH" -> !hw.isBluetoothEnabled
                "AIRPLANE_MODE" -> !hw.isAirplaneModeOn
                "AUTO_ROTATE" -> !hw.isAutoRotateOn
                "FLASHLIGHT" -> !hw.isFlashlightOn
                else -> true
            }
            val res = deviceExecutor.toggleSystemSettingSmart(
                settingTarget = settingTarget,
                enable = resolvedEnable,
                launchExternal = true
            )
            _liveArcherReply.value = res
            voiceBridge.speak(res)
        }
    }

    fun saveWorkspaceCodeDirectly(
        projectTitle: String,
        fileName: String,
        language: String,
        codeContent: String
    ) {
        if (codeContent.isBlank()) return
        viewModelScope.launch {
            codingAndContactsManager.saveGeneratedCodeToArcherFolder(
                projectTitle = projectTitle.ifBlank { "archer_project" },
                preferredFileName = fileName.ifBlank { "main.py" },
                language = language.ifBlank { "Python" },
                rawCodeOrResponse = codeContent
            )
            val summary = codingWorkspaceState.value.lastSaveSummary
            _liveArcherReply.value = summary
            voiceBridge.speak(summary)
        }
    }

    private fun detectAndApplyInlineRuleLock(command: String): String? {
        val lower = command.lowercase().trim()
        val asksEnglishOnly = (lower.contains("ইংরেজিতে বল") ||
            lower.contains("ইরেজিতে বল") ||
            lower.contains("ইংলিশে বল") ||
            lower.contains("শুধু ইংরেজি") ||
            lower.contains("only in english") ||
            lower.contains("speak in english") ||
            lower.contains("reply in english") ||
            lower.contains("talk in english")) &&
            !lower.contains("অনুবাদ")

        val asksBengaliOnly = (lower.contains("শুধু বাংলায় বল") ||
            lower.contains("শুধু বাংলাতে বল") ||
            lower.contains("বাংলায় কথা বল") ||
            lower.contains("speak only in bengali") ||
            lower.contains("reply in bengali"))

        val asksHindiOnly = (lower.contains("হিন্দিতে বল") ||
            lower.contains("শুধু হিন্দি") ||
            lower.contains("speak in hindi") ||
            lower.contains("हिंदी में बोलो"))

        val asksAutoAll = (lower.contains("সব ভাষায় কথা বল") ||
            lower.contains("যেকোনো ভাষায়") ||
            lower.contains("আগের মতো বাংলা") ||
            lower.contains("reset language"))

        return when {
            asksEnglishOnly -> {
                _lockedReplyLanguage.value = "ENGLISH_ONLY"
                _persistentUserRule.value = "Always reply strictly in English even when the user speaks in Bengali, Banglish, or Hindi."
                prefs.edit()
                    .putString(KEY_LOCKED_REPLY_LANGUAGE, "ENGLISH_ONLY")
                    .putString(KEY_PERSISTENT_USER_RULE, _persistentUserRule.value)
                    .apply()
                viewModelScope.launch {
                    historyRepo.saveMemoryNote(
                        title = "ভাষা নির্দেশনা লক (ENGLISH_ONLY)",
                        content = "User instructed: $command -> Reply only in English",
                        category = "AGENT_RULE"
                    )
                }
                "ENGLISH_ONLY"
            }
            asksBengaliOnly -> {
                _lockedReplyLanguage.value = "BENGALI_ONLY"
                _persistentUserRule.value = "সবসময় পরিষ্কার বাংলায় উত্তর দেবে।"
                prefs.edit()
                    .putString(KEY_LOCKED_REPLY_LANGUAGE, "BENGALI_ONLY")
                    .putString(KEY_PERSISTENT_USER_RULE, _persistentUserRule.value)
                    .apply()
                "BENGALI_ONLY"
            }
            asksHindiOnly -> {
                _lockedReplyLanguage.value = "HINDI_ONLY"
                _persistentUserRule.value = "हमेशा हिंदी में उत्तर दें।"
                prefs.edit()
                    .putString(KEY_LOCKED_REPLY_LANGUAGE, "HINDI_ONLY")
                    .putString(KEY_PERSISTENT_USER_RULE, _persistentUserRule.value)
                    .apply()
                "HINDI_ONLY"
            }
            asksAutoAll -> {
                _lockedReplyLanguage.value = "AUTO"
                _persistentUserRule.value = ""
                prefs.edit()
                    .putString(KEY_LOCKED_REPLY_LANGUAGE, "AUTO")
                    .putString(KEY_PERSISTENT_USER_RULE, "")
                    .apply()
                "AUTO"
            }
            lower.contains("মাথায় রাখ") || lower.contains("মনে রাখ") || lower.contains("এখন থেকে") || lower.contains("always remember") -> {
                _persistentUserRule.value = command
                prefs.edit().putString(KEY_PERSISTENT_USER_RULE, command).apply()
                viewModelScope.launch {
                    historyRepo.saveMemoryNote(
                        title = "স্যারের স্থায়ী নির্দেশনা",
                        content = command,
                        category = "AGENT_RULE"
                    )
                }
                "CUSTOM_RULE"
            }
            else -> null
        }
    }

    fun processUserCommand(promptText: String, triggeredByWakeWord: Boolean = false) {
        val rawTrimmed = promptText.trim()
        if (rawTrimmed.isBlank()) return

        _liveUserTranscript.value = rawTrimmed
        detectAndApplyInlineRuleLock(rawTrimmed)

        val lowerEffective = rawTrimmed.lowercase()
        val asksToLookAtScreen = lowerEffective.contains("স্ক্রিনে দেখ") ||
            lowerEffective.contains("সক্রিনে দেখ") ||
            lowerEffective.contains("স্ক্রিন দেখ") ||
            lowerEffective.contains("সক্রিন দেখ") ||
            lowerEffective.contains("স্ক্রিন শেয়ার") ||
            lowerEffective.contains("সক্রিন সেয়ার") ||
            lowerEffective.contains("সক্রিন শেয়ার") ||
            lowerEffective.contains("screen share") ||
            lowerEffective.contains("look at screen") ||
            lowerEffective.contains("look at my screen")

        if (asksToLookAtScreen) {
            multimodalLiveService.enableScreenStreamNow()
            val hasPermGrant = com.example.service.ScreenCaptureSessionBus.state.value.hasMediaProjectionGrant
            if (!hasPermGrant && !accessibilityState.value.isServiceEnabled) {
                com.example.service.ScreenCaptureSessionBus.requestOneTimeScreenCapturePermission()
                _liveArcherReply.value = "জি স্যার, প্রথমবার স্ক্রিন শেয়ারের পারমিশন বক্সটি ওপেন করেছি—একবার Allow করে দিন।"
                return
            }
        }

        val nowStr = SimpleDateFormat("hh:mm a, dd MMM yyyy", Locale.getDefault()).format(Date())
        val hw = deviceExecutor.hardwareState.value
        val acc = accessibilityState.value
        val contextualInput = buildString {
            appendLine("Time: $nowStr | Battery: ${hw.batteryPercent}%")
            val activePkg = acc.currentForegroundPackage.ifBlank { "Archer HUD" }
            appendLine("Screen Accessibility: ${acc.isServiceEnabled} | Active App: $activePkg")
            if (acc.visibleScreenText.isNotBlank()) {
                appendLine("Live Screen Text: ${acc.visibleScreenText.take(450)}")
            }
            if (acc.visibleButtonsAndNodes.isNotEmpty()) {
                appendLine("Clickable Screen Nodes: ${acc.visibleButtonsAndNodes.take(15).joinToString(", ")}")
            }
        }

        multimodalLiveService.sendLiveMultimodalTurn(
            userUtterance = rawTrimmed,
            contextualDeviceState = contextualInput,
            isFrontCamera = _isFrontCameraSelected.value
        )
    }

    private fun stripActionTag(raw: String): String {
        return raw
            .replace(Regex("\\[\\[ACTION:.*?]]", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\\[\\[ACTION:.*"), "")
            .replace(Regex("```[a-zA-Z0-9_-]*\\n[\\s\\S]*?```"), "")
            .replace("জি বস", "জি স্যার")
            .replace("হ্যালো বস", "হ্যালো স্যার")
            .replace("বসের", "স্যারের")
            .replace(Regex("(?<![অ-হ])বস(?![অ-হ])"), "স্যার")
            .replace(Regex("(?i)\\bboss\\b"), "Sir")
            .trim()
    }

    private fun buildPlanFromAiResponse(
        userPrompt: String,
        effectiveCommand: String,
        rawResponse: String,
        triggeredByWakeWord: Boolean,
        modelUsed: String = "gemini-3.8-flash"
    ): AgentPlanResponse {
        val trimmed = rawResponse.trim()
        val actionMatch = Regex(
            "\\[\\[ACTION:([A-Z_]+)\\|([^|\\]]*)(?:\\|([^|\\]]*))?(?:\\|([^\\]]*))?]]",
            RegexOption.DOT_MATCHES_ALL
        ).find(trimmed)
        val markdownCodeMatch = Regex("```([a-zA-Z0-9_-]*)\\s*\\n([\\s\\S]*?)```").find(trimmed)

        val cleanSpoken = stripActionTag(trimmed).ifBlank {
            if (_lockedReplyLanguage.value == "ENGLISH_ONLY") {
                "Yes Sir, I have completed the task and saved the files in the archer folder."
            } else {
                "জি স্যার, আপনার আদেশটি সম্পন্ন করছি।"
            }
        }
        val lowerCmd = effectiveCommand.lowercase().trim()

        val steps = mutableListOf<AgentStep>()
        if (actionMatch != null) {
            val actionName = actionMatch.groupValues[1].trim()
            val rawTarget = actionMatch.groupValues[2].trim()
            val extra = actionMatch.groupValues.getOrNull(3)?.trim().orEmpty()
            val fourthPart = actionMatch.groupValues.getOrNull(4)?.trim().orEmpty()

            var actionType = try {
                AgentActionType.valueOf(actionName)
            } catch (_: Exception) {
                AgentActionType.ANSWER_QUESTION
            }

            // Guarantee YouTube requests ALWAYS open the native YouTube app rather than Chrome/Web
            val mentionsYouTube = rawTarget.lowercase().contains("youtube") ||
                rawTarget.contains("ইউটিউব") ||
                rawTarget.contains("ইউটুব") ||
                lowerCmd.contains("youtube") ||
                lowerCmd.contains("ইউটিউব") ||
                lowerCmd.contains("ইউটুব") ||
                lowerCmd.contains("यूट्यूब")

            if (mentionsYouTube && (actionType == AgentActionType.CHROME_WEB_ACTION || actionType == AgentActionType.WEB_SEARCH)) {
                actionType = AgentActionType.OPEN_APP
            }

            if (actionType != AgentActionType.ANSWER_QUESTION) {
                val turnOnFlag = when {
                    extra.equals("OFF", ignoreCase = true) || rawTarget.equals("OFF", ignoreCase = true) -> false
                    extra.equals("ON", ignoreCase = true) || rawTarget.equals("ON", ignoreCase = true) -> true
                    lowerCmd.contains("অফ") || lowerCmd.contains("বন্ধ") || lowerCmd.contains("off") || lowerCmd.contains("बंद") -> false
                    lowerCmd.contains("অন") || lowerCmd.contains("চালু") || lowerCmd.contains("on") || lowerCmd.contains("चालू") -> true
                    else -> null
                }
                val volPct = extra.filter { it.isDigit() }.toIntOrNull()
                    ?: rawTarget.filter { it.isDigit() }.toIntOrNull()
                    ?: -1

                when (actionType) {
                    AgentActionType.TOGGLE_SYSTEM_SETTING -> {
                        val settingName = rawTarget.ifBlank { effectiveCommand }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.TOGGLE_SYSTEM_SETTING,
                                title = "কুইক সেটিংস টগল: $settingName (${extra.ifBlank { "TOGGLE" }})",
                                description = cleanSpoken,
                                settingTarget = settingName,
                                settingEnabled = turnOnFlag ?: true,
                                query = settingName,
                                body = extra.ifBlank { "TOGGLE" }
                            )
                        )
                    }

                    AgentActionType.SET_VOLUME -> {
                        val stream = rawTarget.ifBlank { "MUSIC" }
                        val modeOrVal = extra.ifBlank { if (volPct >= 0) "$volPct" else "UP" }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.SET_VOLUME,
                                title = "সাউন্ড কন্ট্রোল: $stream ($modeOrVal)",
                                description = cleanSpoken,
                                volumeStream = stream,
                                volumePercent = if (volPct in 0..100) volPct else -1,
                                screenControlCommand = modeOrVal,
                                body = modeOrVal
                            )
                        )
                    }

                    AgentActionType.SCAN_CONTACT_OR_SCREEN -> {
                        val personName = rawTarget.ifBlank { effectiveCommand }
                        val subAction = extra.ifBlank { "CALL_OR_CLICK" }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.SCAN_CONTACT_OR_SCREEN,
                                title = "কন্টাক্ট ও স্ক্রিন স্ক্যান: $personName",
                                description = cleanSpoken,
                                recipient = personName,
                                query = personName,
                                screenControlCommand = subAction,
                                body = fourthPart.ifBlank { subAction }
                            )
                        )
                    }

                    AgentActionType.GENERATE_CODE_PROJECT -> {
                        val fileName = rawTarget.ifBlank { "archer_app.py" }
                        val lang = extra.ifBlank {
                            markdownCodeMatch?.groupValues?.getOrNull(1)?.ifBlank { "python" } ?: "python"
                        }
                        val codeBody = when {
                            markdownCodeMatch != null -> markdownCodeMatch.groupValues[2].trim()
                            fourthPart.isNotBlank() -> fourthPart
                            else -> "# Generated by ARCHER AI ($modelUsed)\nprint('Hello from Archer AI')"
                        }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.GENERATE_CODE_PROJECT,
                                title = "Argon 4 কোডিং ও archer ফোল্ডারে সেভ: $fileName",
                                description = cleanSpoken,
                                query = effectiveCommand,
                                codeFilename = fileName,
                                codeLanguage = lang,
                                body = codeBody
                            )
                        )
                    }

                    AgentActionType.UPDATE_AGENT_RULE -> {
                        val langMode = rawTarget.uppercase()
                        if (langMode in listOf("ENGLISH_ONLY", "BENGALI_ONLY", "HINDI_ONLY", "AUTO")) {
                            _lockedReplyLanguage.value = langMode
                            prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, langMode).apply()
                        }
                        if (extra.isNotBlank()) {
                            _persistentUserRule.value = extra
                            prefs.edit().putString(KEY_PERSISTENT_USER_RULE, extra).apply()
                        }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.UPDATE_AGENT_RULE,
                                title = "স্থায়ী নির্দেশনা ও ভাষা লক: $langMode",
                                description = cleanSpoken,
                                query = langMode,
                                body = extra.ifBlank { effectiveCommand }
                            )
                        )
                    }

                    AgentActionType.INSTALL_APP_PLAYSTORE -> {
                        val forceUpdate = extra.equals("UPDATE", ignoreCase = true) ||
                            lowerCmd.contains("আপডেট") ||
                            lowerCmd.contains("আপটেড") ||
                            lowerCmd.contains("আপডেড") ||
                            lowerCmd.contains("update") ||
                            lowerCmd.contains("updet") ||
                            lowerCmd.contains("अपडेट")
                        val resolved = deviceExecutor.cleanPlayStoreAppQuery(
                            rawTarget.ifBlank { effectiveCommand },
                            forceUpdateMode = forceUpdate
                        )
                        val cleanApp = resolved.cleanAppName.ifBlank { rawTarget }
                        val modeLabel = if (resolved.isUpdateRequest) "UPDATE" else "INSTALL"
                        val titlePrefix = if (resolved.isUpdateRequest) "প্লে স্টোর অটো-আপডেট" else "প্লে স্টোর অটো-ইনস্টল"
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.INSTALL_APP_PLAYSTORE,
                                title = "$titlePrefix: $cleanApp",
                                description = cleanSpoken,
                                query = cleanApp,
                                appName = cleanApp,
                                screenControlCommand = modeLabel,
                                body = modeLabel
                            )
                        )
                    }

                    AgentActionType.OPEN_APP -> {
                        val finalApp = if (mentionsYouTube) "YouTube" else rawTarget.ifBlank { effectiveCommand }
                        val ytSubQuery = if (mentionsYouTube) {
                            extra.ifBlank {
                                if (!rawTarget.equals("YouTube", ignoreCase = true) && !rawTarget.contains("ইউটিউব")) {
                                    rawTarget
                                } else {
                                    effectiveCommand
                                }
                            }
                        } else {
                            extra
                        }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.OPEN_APP,
                                title = "অ্যাপ ওপেন: $finalApp",
                                description = cleanSpoken,
                                query = finalApp,
                                appName = finalApp,
                                body = ytSubQuery
                            )
                        )
                    }

                    else -> {
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = actionType,
                                title = "অটোনোমাস এআই অ্যাকশন: ${actionType.name}",
                                description = cleanSpoken,
                                query = rawTarget.ifBlank { effectiveCommand },
                                appName = rawTarget,
                                targetUrlOrFile = rawTarget,
                                recipient = rawTarget,
                                phoneNumber = rawTarget,
                                settingTarget = rawTarget.ifBlank { "MAIN_SETTINGS" },
                                settingEnabled = turnOnFlag ?: true,
                                mood = extra.ifBlank { "ROMANTIC" },
                                flashlightOn = turnOnFlag ?: true,
                                volumePercent = if (volPct in 0..100) volPct else 75,
                                screenControlCommand = extra.ifBlank { "CLICK" },
                                body = fourthPart.ifBlank { extra.ifBlank { cleanSpoken } }
                            )
                        )
                    }
                }
            }
        }

        // If AI returned a markdown code block without an explicit [[ACTION:GENERATE_CODE_PROJECT]] tag, automatically archive it in archer/
        if (markdownCodeMatch != null && steps.none { it.actionType == AgentActionType.GENERATE_CODE_PROJECT }) {
            val lang = markdownCodeMatch.groupValues[1].trim().ifBlank { "python" }
            val codeText = markdownCodeMatch.groupValues[2].trim()
            val ext = when (lang.lowercase()) {
                "kotlin", "kt" -> "kt"
                "java" -> "java"
                "html" -> "html"
                "javascript", "js" -> "js"
                "typescript", "ts" -> "ts"
                "cpp", "c++" -> "cpp"
                "json" -> "json"
                else -> "py"
            }
            val fileName = "archer_code.$ext"
            steps.add(
                AgentStep(
                    id = UUID.randomUUID().toString(),
                    actionType = AgentActionType.GENERATE_CODE_PROJECT,
                    title = "Argon 4 কোডিং ও archer ফোল্ডারে সেভ: $fileName",
                    description = cleanSpoken,
                    query = effectiveCommand,
                    codeFilename = fileName,
                    codeLanguage = lang,
                    body = codeText
                )
            )
        }

        // Smart safety net if the AI gave a spoken reply without emitting the [[ACTION:...]] tag
        if (steps.isEmpty()) {
            val isQuickSettingToggle = (lowerCmd.contains("ওয়াইফাই") ||
                lowerCmd.contains("wifi") ||
                lowerCmd.contains("ব্লুটুথ") ||
                lowerCmd.contains("bluetooth") ||
                lowerCmd.contains("সিমের নেট") ||
                lowerCmd.contains("ডাটা") ||
                lowerCmd.contains("mobile data") ||
                lowerCmd.contains("এরোপ্লেন") ||
                lowerCmd.contains("airplane") ||
                lowerCmd.contains("হটস্পট") ||
                lowerCmd.contains("hotspot") ||
                lowerCmd.contains("লোকেশন") ||
                lowerCmd.contains("location") ||
                lowerCmd.contains("রোটেশন") ||
                lowerCmd.contains("rotate") ||
                lowerCmd.contains("ডার্ক মোড") ||
                lowerCmd.contains("dark mode") ||
                lowerCmd.contains("নোটিফিকেশন বার") ||
                lowerCmd.contains("কুইক সেটিং")) &&
                (lowerCmd.contains("অন") ||
                    lowerCmd.contains("অফ") ||
                    lowerCmd.contains("চালু") ||
                    lowerCmd.contains("বন্ধ") ||
                    lowerCmd.contains("নামাও") ||
                    lowerCmd.contains("on") ||
                    lowerCmd.contains("off") ||
                    lowerCmd.contains("toggle"))

            val isVolumeCmd = lowerCmd.contains("সাউন্ড") ||
                lowerCmd.contains("ভলিউম") ||
                lowerCmd.contains("আওয়াজ") ||
                lowerCmd.contains("sound") ||
                lowerCmd.contains("volume") ||
                lowerCmd.contains("সাইলেন্ট") ||
                lowerCmd.contains("মিউট") ||
                lowerCmd.contains("mute") ||
                lowerCmd.contains("ভাইব্রেট")

            val isPlayStoreCommand = lowerCmd.contains("প্লে স্টোর") ||
                lowerCmd.contains("প্লে স্টুর") ||
                lowerCmd.contains("প্লেস্টোর") ||
                lowerCmd.contains("play store") ||
                lowerCmd.contains("playstore") ||
                lowerCmd.contains("আপডেট") ||
                lowerCmd.contains("আপটেড") ||
                lowerCmd.contains("ইনস্টল") ||
                lowerCmd.contains("ইনিস্টল") ||
                lowerCmd.contains("update") ||
                lowerCmd.contains("install") ||
                lowerCmd.contains("अपडेट") ||
                lowerCmd.contains("इंस्टॉल")

            val isYouTubeCommand = (lowerCmd.contains("ইউটিউব") ||
                lowerCmd.contains("ইউটিউবে") ||
                lowerCmd.contains("ইউটুব") ||
                lowerCmd.contains("youtube") ||
                lowerCmd.contains("यूट्यूब")) &&
                (lowerCmd.contains("খোল") ||
                    lowerCmd.contains("খুল") ||
                    lowerCmd.contains("ওপেন") ||
                    lowerCmd.contains("চালু") ||
                    lowerCmd.contains("চালাও") ||
                    lowerCmd.contains("বাজাও") ||
                    lowerCmd.contains("সার্চ") ||
                    lowerCmd.contains("open") ||
                    lowerCmd.contains("play") ||
                    lowerCmd.contains("search"))

            if (isQuickSettingToggle) {
                val enable = !(lowerCmd.contains("অফ") || lowerCmd.contains("বন্ধ") || lowerCmd.contains("off"))
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.TOGGLE_SYSTEM_SETTING,
                        title = "কুইক সেটিংস টগল",
                        description = cleanSpoken,
                        settingTarget = effectiveCommand,
                        settingEnabled = enable,
                        query = effectiveCommand
                    )
                )
            } else if (isVolumeCmd) {
                val pct = effectiveCommand.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100) ?: -1
                val stream = when {
                    lowerCmd.contains("রিং") || lowerCmd.contains("ring") -> "RING"
                    lowerCmd.contains("অ্যালার্ম") || lowerCmd.contains("alarm") -> "ALARM"
                    lowerCmd.contains("নোটিফিকেশন") || lowerCmd.contains("notification") -> "NOTIFICATION"
                    lowerCmd.contains("কল") || lowerCmd.contains("call") -> "VOICE_CALL"
                    lowerCmd.contains("সিস্টেম") || lowerCmd.contains("system") -> "SYSTEM"
                    lowerCmd.contains("সব") || lowerCmd.contains("ফুল") || lowerCmd.contains("all") -> "ALL"
                    else -> "MUSIC"
                }
                val dir = when {
                    lowerCmd.contains("মিউট") || lowerCmd.contains("সাইলেন্ট") || lowerCmd.contains("mute") || lowerCmd.contains("silent") -> "MUTE"
                    lowerCmd.contains("আনমিউট") || lowerCmd.contains("unmute") -> "UNMUTE"
                    lowerCmd.contains("ফুল") || lowerCmd.contains("সর্বোচ্চ") || lowerCmd.contains("full") || lowerCmd.contains("max") -> "MAX"
                    lowerCmd.contains("কমা") || lowerCmd.contains("কম") || lowerCmd.contains("down") || lowerCmd.contains("decrease") -> "DOWN"
                    else -> "UP"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.SET_VOLUME,
                        title = "সাউন্ড কন্ট্রোল: $stream",
                        description = cleanSpoken,
                        volumeStream = stream,
                        volumePercent = pct,
                        screenControlCommand = dir,
                        body = dir
                    )
                )
            } else if (isPlayStoreCommand && !lowerCmd.contains("apk")) {
                val resolved = deviceExecutor.cleanPlayStoreAppQuery(effectiveCommand)
                if (resolved.cleanAppName.isNotBlank()) {
                    val modeLabel = if (resolved.isUpdateRequest) "UPDATE" else "INSTALL"
                    val titlePrefix = if (resolved.isUpdateRequest) "প্লে স্টোর অটো-আপডেট" else "প্লে স্টোর অটো-ইনস্টল"
                    steps.add(
                        AgentStep(
                            id = UUID.randomUUID().toString(),
                            actionType = AgentActionType.INSTALL_APP_PLAYSTORE,
                            title = "$titlePrefix: ${resolved.cleanAppName}",
                            description = cleanSpoken,
                            query = resolved.cleanAppName,
                            appName = resolved.cleanAppName,
                            screenControlCommand = modeLabel,
                            body = modeLabel
                        )
                    )
                }
            } else if (isYouTubeCommand) {
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.OPEN_APP,
                        title = "অ্যাপ ওপেন: YouTube",
                        description = cleanSpoken,
                        query = "YouTube",
                        appName = "YouTube",
                        body = effectiveCommand
                    )
                )
            }
        }

        val summaryHeader = if (modelUsed == "gemini-4-argon") {
            "ARCHER AI • ARGON 4 ENGINE"
        } else {
            "ARCHER AI — জেমিনি ইন্টেলিজেন্স"
        }

        return AgentPlanResponse(
            id = UUID.randomUUID().toString(),
            userPrompt = userPrompt,
            spokenReply = cleanSpoken,
            summaryTitle = summaryHeader,
            steps = steps,
            triggeredByWakeWord = triggeredByWakeWord,
            modelUsed = modelUsed
        )
    }

    /**
     * Used ONLY if all cloud Gemini endpoints fail (e.g., offline or invalid API key).
     * Supports Bengali, Banglish, English, Hindi, and ASR phonetic variations,
     * and strictly obeys _lockedReplyLanguage ("ENGLISH_ONLY", "BENGALI_ONLY", "HINDI_ONLY").
     */
    private fun generateSmartContextualAnswerWhenOffline(
        userPrompt: String,
        effectiveCommand: String,
        triggeredByWakeWord: Boolean
    ): AgentPlanResponse {
        val lower = effectiveCommand.lowercase().trim()
        val steps = mutableListOf<AgentStep>()
        val isEnglishLocked = _lockedReplyLanguage.value == "ENGLISH_ONLY"
        val reply: String

        when {
            lower.contains("ইংরেজিতে বল") || lower.contains("ইরেজিতে বল") || lower.contains("only in english") || lower.contains("speak in english") -> {
                _lockedReplyLanguage.value = "ENGLISH_ONLY"
                prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, "ENGLISH_ONLY").apply()
                reply = "Yes Sir, I have locked my response language to English. Even if you speak to me in Bengali, Banglish, or Hindi, I will strictly reply in English."
            }
            lower.contains("শুধু বাংলায় বল") || lower.contains("বাংলায় কথা বল") -> {
                _lockedReplyLanguage.value = "BENGALI_ONLY"
                prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, "BENGALI_ONLY").apply()
                reply = "জি স্যার, আমি আপনার নির্দেশনা মাথায় রেখেছি। এখন থেকে আমি সবসময় বাংলায় উত্তর দেবো।"
            }
            lower.contains("কয়টা বাজে") || lower.contains("সময়") || lower.contains("time") || lower.contains("তারিখ") || lower.contains("समय") -> {
                val now = Date()
                val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now)
                val dateStr = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(now)
                reply = if (isEnglishLocked) {
                    "Yes Sir, the current time is $timeStr and today's date is $dateStr."
                } else {
                    "জি স্যার, এখন সময় $timeStr এবং আজকের তারিখ $dateStr।"
                }
            }
            lower.contains("ব্যাটারি") || lower.contains("চার্জ") || lower.contains("battery") || lower.contains("बैटरी") -> {
                val hw = deviceExecutor.hardwareState.value
                reply = if (isEnglishLocked) {
                    "Yes Sir, your phone battery is currently at ${hw.batteryPercent}%."
                } else {
                    "জি স্যার, আপনার ফোনে এখন ${hw.batteryPercent}% চার্জ রয়েছে।"
                }
            }
            // Quick Settings & Notification Bar Auto-Toggle (Wi-Fi, Bluetooth, SIM Net, Airplane Mode, Hotspot, Location, Rotate, DND, Torch)
            lower.contains("ওয়াইফাই") ||
                lower.contains("wifi") ||
                lower.contains("wi-fi") ||
                lower.contains("ব্লুটুথ") ||
                lower.contains("bluetooth") ||
                lower.contains("সিমের নেট") ||
                lower.contains("মোবাইল ডাটা") ||
                lower.contains("ডাটা") ||
                lower.contains("mobile data") ||
                lower.contains("এরোপ্লেন") ||
                lower.contains("ফ্লাইট মোড") ||
                lower.contains("airplane") ||
                lower.contains("হটস্পট") ||
                lower.contains("hotspot") ||
                lower.contains("লোকেশন") ||
                lowerCmdContainsLocationOrRotate(lower) ||
                lower.contains("টর্চ") ||
                lower.contains("ফ্ল্যাশলাইট") ||
                lower.contains("torch") ||
                lower.contains("flashlight") ||
                lower.contains("নোটিফিকেশন বার") -> {
                val turnOff = lower.contains("বন্ধ") || lower.contains("অফ") || lower.contains("off") || lower.contains("बंद")
                val enableFlag = !turnOff
                reply = if (isEnglishLocked) {
                    "Yes Sir, toggling the requested Quick Setting now."
                } else {
                    "জি স্যার, নোটিফিকেশন বার ও সিস্টেম কন্ট্রোল থেকে সেটিংসটি এখনই টগল করে দিচ্ছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.TOGGLE_SYSTEM_SETTING,
                        title = "কুইক সেটিংস অটো-টগল",
                        description = reply,
                        settingTarget = effectiveCommand,
                        settingEnabled = enableFlag,
                        query = effectiveCommand
                    )
                )
            }
            // Full 6-Stream Sound & Volume Control
            lower.contains("সাউন্ড") ||
                lower.contains("ভলিউম") ||
                lower.contains("আওয়াজ") ||
                lower.contains("sound") ||
                lower.contains("volume") ||
                lower.contains("সাইলেন্ট") ||
                lower.contains("মিউট") ||
                lower.contains("mute") ||
                lower.contains("ভাইব্রেট") -> {
                val pct = effectiveCommand.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100) ?: -1
                val stream = when {
                    lower.contains("রিং") || lower.contains("ring") -> "RING"
                    lower.contains("অ্যালার্ম") || lower.contains("alarm") -> "ALARM"
                    lower.contains("নোটিফিকেশন") || lower.contains("notification") -> "NOTIFICATION"
                    lower.contains("কল") || lower.contains("call") -> "VOICE_CALL"
                    lower.contains("সিস্টেম") || lower.contains("system") -> "SYSTEM"
                    lower.contains("সব") || lower.contains("ফুল") || lower.contains("all") -> "ALL"
                    else -> "MUSIC"
                }
                val dir = when {
                    lower.contains("মিউট") || lower.contains("সাইলেন্ট") || lower.contains("mute") || lower.contains("silent") -> "MUTE"
                    lower.contains("আনমিউট") || lower.contains("unmute") -> "UNMUTE"
                    lower.contains("ভাইব্রেট") || lower.contains("vibrate") -> "VIBRATE"
                    lower.contains("ফুল") || lower.contains("সর্বোচ্চ") || lower.contains("full") || lower.contains("max") -> "MAX"
                    lower.contains("কমা") || lower.contains("কম") || lower.contains("down") || lower.contains("decrease") -> "DOWN"
                    else -> "UP"
                }
                reply = if (isEnglishLocked) {
                    "Yes Sir, adjusting the $stream sound volume now."
                } else {
                    "জি স্যার, আপনার নির্দেশ অনুযায়ী সাউন্ড ভলিউম ($stream) অ্যাডজাস্ট করে দিয়েছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.SET_VOLUME,
                        title = "সাউন্ড কন্ট্রোল: $stream ($dir)",
                        description = reply,
                        volumeStream = stream,
                        volumePercent = pct,
                        screenControlCommand = dir,
                        body = dir
                    )
                )
            }
            // Coding & Argon 4 Workspace + archer Folder Archiver
            lower.contains("কোড") ||
                lower.contains("কোডিং") ||
                lower.contains("argon") ||
                lower.contains("আরগন") ||
                lower.contains("code") ||
                lower.contains("coding") ||
                lower.contains("python") ||
                lower.contains("html") ||
                lower.contains("script") ||
                lower.contains("প্রোগ্রাম") -> {
                val isHtml = lower.contains("html") || lower.contains("ওয়েবসাইট") || lower.contains("website") || lower.contains("web")
                val fileName = if (isHtml) "index.html" else "archer_main.py"
                val lang = if (isHtml) "html" else "python"
                val generatedCode = if (isHtml) {
                    """
                    <!DOCTYPE html>
                    <html lang="bn">
                    <head>
                        <meta charset="UTF-8" />
                        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                        <title>ARCHER AI — Argon 4 Project</title>
                        <style>
                            body { background: #03120B; color: #00FF88; font-family: sans-serif; padding: 2rem; text-align: center; }
                            .card { border: 1px solid #00FF88; border-radius: 16px; padding: 24px; max-width: 540px; margin: 0 auto; background: #072316; }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <h1>ARCHER AI • Argon 4</h1>
                            <p>Prompt: $effectiveCommand</p>
                        </div>
                    </body>
                    </html>
                    """.trimIndent()
                } else {
                    """
                    #!/usr/bin/env python3
                    # Generated by ARCHER AI (Argon 4 Engine)
                    # Task: $effectiveCommand

                    def run_archer_task():
                        print("ARCHER AI Argon-4 Task Initialized")
                        print("Prompt:", ${"\""}${effectiveCommand.replace("\"", "'")}${"\""})

                    if __name__ == "__main__":
                        run_archer_task()
                    """.trimIndent()
                }
                reply = if (isEnglishLocked) {
                    "Yes Sir, Argon 4 has written the code and saved it inside the archer folder as a single file, project folder, and ZIP archive."
                } else {
                    "জি স্যার, Argon 4 দিয়ে কোডিং সম্পন্ন করে মেইন স্টোরেজের 'archer' ফোল্ডারে ফাইল, প্রজেক্ট ফোল্ডার এবং জিপ (.zip) আকারে সেভ করে দিচ্ছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.GENERATE_CODE_PROJECT,
                        title = "Argon 4 কোডিং ও archer ফোল্ডারে সেভ: $fileName",
                        description = reply,
                        query = effectiveCommand,
                        codeFilename = fileName,
                        codeLanguage = lang,
                        body = generatedCode
                    )
                )
            }
            // Contact List + Screen-Wide Name Search & Call/Action
            lower.contains("কল") ||
                lower.contains("ফোন দাও") ||
                lower.contains("ডায়াল") ||
                lower.contains("call ") ||
                lower.contains("কন্টাক্ট") ||
                lower.contains("contact") ||
                lower.contains("নাম খুঁজে") ||
                lower.contains("বিচারাইয়া") -> {
                val cleanedName = effectiveCommand
                    .replace(Regex("(?i)(কল|দাও|করো|কে|ফোন|ডায়াল|কন্টাক্ট|থেকে|স্ক্রিনে|খুঁজে|বিচারাইয়া|call|phone|dial|contact)"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .ifBlank { effectiveCommand }
                reply = if (isEnglishLocked) {
                    "Yes Sir, scanning your Contacts list and the entire screen for '$cleanedName' now."
                } else {
                    "জি স্যার, কন্টাক্ট লিস্ট এবং পুরো স্ক্রিনে '$cleanedName' নামটি খুঁজে এখনই অ্যাকশন নিচ্ছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.SCAN_CONTACT_OR_SCREEN,
                        title = "কন্টাক্ট ও স্ক্রিন স্ক্যান: $cleanedName",
                        description = reply,
                        recipient = cleanedName,
                        query = cleanedName,
                        screenControlCommand = "CALL_OR_CLICK"
                    )
                )
            }
            // Screen Reading, Permanent Screen Share & 4-Directional Touch Gesture Control (UP, DOWN, LEFT, RIGHT)
            lower.contains("স্ক্রিনে কি আছে") ||
                lower.contains("স্ক্রিনে দেখ") ||
                lower.contains("সক্রিনে দেখ") ||
                lower.contains("স্ক্রিন দেখ") ||
                lower.contains("সক্রিন দেখা") ||
                lower.contains("স্ক্রিন শেয়ার") ||
                lower.contains("সক্রিন সেয়ার") ||
                lower.contains("স্ক্রিন পড়ো") ||
                lower.contains("টাচ করো") ||
                lower.contains("ক্লিক করো") ||
                lower.contains("স্ক্রল") ||
                lower.contains("সোয়াইপ") ||
                lower.contains("ডানে") ||
                lower.contains("বামে") ||
                lower.contains("উপরে") ||
                lower.contains("নিচে") ||
                lower.contains("tap") ||
                lower.contains("swipe") ||
                lower.contains("scroll") -> {
                multimodalLiveService.enableScreenStreamNow()
                val cmdType = when {
                    lower.contains("নিচে") || lower.contains("স্ক্রল ডাউন") || lower.contains("scroll down") || lower.contains("swipe up") -> "SWIPE_UP"
                    lower.contains("উপরে") || lower.contains("স্ক্রল আপ") || lower.contains("scroll up") || lower.contains("swipe down") -> "SWIPE_DOWN"
                    lower.contains("বামে") || lower.contains("left") || lower.contains("swipe left") -> "SWIPE_LEFT"
                    lower.contains("ডানে") || lower.contains("right") || lower.contains("swipe right") -> "SWIPE_RIGHT"
                    lower.contains("ডাবল") || lower.contains("double") -> "DOUBLE_TAP"
                    lower.contains("লং") || lower.contains("চাপ দিয়ে") || lower.contains("long press") -> "LONG_PRESS"
                    lower.contains("স্ক্রিনে কি") || lower.contains("পড়ো") || lower.contains("সক্রিন দেখা") || lower.contains("স্ক্রিনে দেখ") || lower.contains("সক্রিনে দেখ") || lower.contains("স্ক্রিন শেয়ার") || lower.contains("সক্রিন সেয়ার") -> "READ_SCREEN"
                    else -> "CLICK"
                }
                val targetTxt = effectiveCommand
                    .replace(Regex("(?i)(স্ক্রিনে|সক্রিনে|উপরে|নিচে|ডানে|বামে|টাচ|ক্লিক|করো|দাও|চাপ|দেখ|দেখো|tap|click|on)"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                reply = if (cmdType == "READ_SCREEN") {
                    val vis = accessibilityState.value.visibleScreenText
                    val hasProj = com.example.service.ScreenCaptureSessionBus.state.value.hasMediaProjectionGrant
                    if ((accessibilityState.value.isServiceEnabled || hasProj) && vis.isNotBlank()) {
                        if (isEnglishLocked) "Yes Sir, I am looking at your screen right now. I can see ${vis.take(180)}"
                        else "জি স্যার, আমি সরাসরি আপনার স্ক্রিন দেখছি। এখানে দেখা যাচ্ছে ${vis.take(180)}"
                    } else if (hasProj) {
                        if (isEnglishLocked) "Yes Sir, live screen sharing is active and I am watching your screen right now."
                        else "জি স্যার, লাইভ স্ক্রিন শেয়ার চালু আছে, আমি এখন সরাসরি আপনার স্ক্রিন দেখছি। বলুন কোথায় ক্লিক বা স্ক্রল করব?"
                    } else {
                        com.example.service.ScreenCaptureSessionBus.requestOneTimeScreenCapturePermission()
                        if (isEnglishLocked) "Sir, I opened the one-time screen share permission prompt. Please tap Allow once, and from next time I will view the screen automatically."
                        else "জি স্যার, প্রথমবার স্ক্রিন শেয়ারের পারমিশন বক্সটি খুলে দিয়েছি—একবার Allow করে দিন, এরপর থেকে বললেই সাথে সাথে নিজে থেকে স্ক্রিন দেখব।"
                    }
                } else {
                    if (isEnglishLocked) "Yes Sir, executing $cmdType on the screen right now."
                    else "জি স্যার, স্ক্রিনে এখনি $cmdType করে দিচ্ছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.SCREEN_UI_CONTROL,
                        title = "স্ক্রিন ভিশন ও ৪-দিকের টাচ: $cmdType",
                        description = reply,
                        query = targetTxt,
                        screenControlCommand = cmdType,
                        body = targetTxt
                    )
                )
            }
            lower.contains("নোটিফিকেশন") || lower.contains("notification") || lower.contains("মেসেজ পড়ো") || lower.contains("কে মেসেজ") -> {
                val filter = when {
                    lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ") -> "WhatsApp"
                    lower.contains("telegram") || lower.contains("টেলিগ্রাম") -> "Telegram"
                    else -> ""
                }
                reply = NotificationReplyBus.readNotificationsSummary(filter)
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.READ_NOTIFICATIONS,
                        title = "নোটিফিকেশন রিডার",
                        description = reply,
                        query = filter,
                        appName = filter
                    )
                )
            }
            lower.contains("apk") -> {
                val resolved = deviceExecutor.cleanPlayStoreAppQuery(effectiveCommand)
                val app = resolved.cleanAppName.ifBlank { "Telegram" }
                reply = if (isEnglishLocked) {
                    "Yes Sir, launching the APK downloader and installer for '$app' now."
                } else {
                    "জি স্যার, '$app' APK ডাউনলোড ও অটো-ইনস্টলার এখনই চালু করছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.INSTALL_APK_PACKAGE,
                        title = "APK অটো-ইনস্টলার: $app",
                        description = reply,
                        query = app,
                        appName = app,
                        targetUrlOrFile = app
                    )
                )
            }
            lower.contains("প্লে স্টোর") ||
                lower.contains("প্লে স্টুর") ||
                lower.contains("প্লেস্টোর") ||
                lower.contains("play store") ||
                lower.contains("playstore") ||
                lower.contains("আপডেট") ||
                lower.contains("আপটেড") ||
                lower.contains("আপডেড") ||
                lower.contains("ইনস্টল") ||
                lower.contains("ইনিস্টল") ||
                lower.contains("install") ||
                lower.contains("update") ||
                lower.contains("updet") ||
                lower.contains("अपडेट") ||
                lower.contains("इंस्टॉल") -> {
                val resolved = deviceExecutor.cleanPlayStoreAppQuery(effectiveCommand)
                val cleanApp = resolved.cleanAppName
                val isUpdate = resolved.isUpdateRequest
                val modeLabel = if (isUpdate) "UPDATE" else "INSTALL"
                reply = when {
                    isEnglishLocked && cleanApp.isBlank() -> "Yes Sir, opening Google Play Store now."
                    isEnglishLocked && isUpdate -> "Yes Sir, searching '$cleanApp' on Play Store and clicking Update automatically."
                    isEnglishLocked -> "Yes Sir, searching '$cleanApp' on Play Store and clicking Install automatically."
                    cleanApp.isBlank() -> "জি স্যার, গুগল প্লে স্টোর অ্যাপটি এখনই খুলে দিচ্ছি।"
                    isUpdate -> "জি স্যার, প্লে স্টোরে '$cleanApp' সার্চ দিয়ে এখনই অটো-আপডেট করে দিচ্ছি।"
                    else -> "জি স্যার, প্লে স্টোরে '$cleanApp' সার্চ দিয়ে এখনই অটো-ইনস্টল করে দিচ্ছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.INSTALL_APP_PLAYSTORE,
                        title = if (isUpdate) "প্লে স্টোর অটো-আপডেট: ${cleanApp.ifBlank { "Play Store" }}" else "প্লে স্টোর অটো-ইনস্টল: ${cleanApp.ifBlank { "Play Store" }}",
                        description = reply,
                        query = cleanApp,
                        appName = cleanApp,
                        screenControlCommand = modeLabel,
                        body = modeLabel
                    )
                )
            }
            lower.contains("ইউটিউব") ||
                lower.contains("ইউটিউবে") ||
                lower.contains("ইউটুব") ||
                lower.contains("youtube") ||
                lower.contains("you tube") ||
                lower.contains("यूट्यूब") -> {
                val subQuery = effectiveCommand
                    .replace(
                        Regex("(?i)(ডাইরেক্ট|সরাসরি|ইউটিউবে|ইউটিউব|ইউটুব|यूट्यूब|youtube|you tube|ওপেন|খোলো|খুলো|চালু|করো|দাও|open|app|অ্যাপ)"),
                        " "
                    )
                    .replace(Regex("\\s+"), " ")
                    .trim()
                reply = if (isEnglishLocked) {
                    if (subQuery.isNotBlank()) "Yes Sir, opening the YouTube app directly and playing '$subQuery'."
                    else "Yes Sir, opening the YouTube app directly now."
                } else {
                    if (subQuery.isNotBlank()) {
                        "জি স্যার, সরাসরি YouTube অ্যাপ খুলে '$subQuery' সার্চ ও প্লে করে দিচ্ছি।"
                    } else {
                        "জি স্যার, সরাসরি YouTube অ্যাপটি এখনই খুলে দিচ্ছি।"
                    }
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.OPEN_APP,
                        title = "সরাসরি YouTube অ্যাপ: ${subQuery.ifBlank { "Home" }}",
                        description = reply,
                        appName = "YouTube",
                        query = "YouTube",
                        body = subQuery
                    )
                )
            }
            lower.contains("টেলিগ্রাম") || lower.contains("telegram") -> {
                val q = effectiveCommand.replace(Regex("(?i)(টেলিগ্রাম|টেলিগ্রামে|telegram|থেকে|ডাউনলোড|সার্চ|করো|দাও)"), "").trim().ifBlank { "AI PDF" }
                reply = if (isEnglishLocked) "Yes Sir, processing '$q' in Telegram." else "জি স্যার, টেলিগ্রামে গিয়ে '$q' প্রসেস করছি।"
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.TELEGRAM_AUTOMATION,
                        title = "টেলিগ্রাম অটোমেশন",
                        description = reply,
                        recipient = "@telegram",
                        query = q,
                        targetUrlOrFile = q
                    )
                )
            }
            lower.contains("হোয়াটসঅ্যাপ") || lower.contains("whatsapp") -> {
                reply = if (isEnglishLocked) "Yes Sir, processing your WhatsApp command now." else "জি স্যার, হোয়াটসঅ্যাপে আপনার মেসেজটি প্রসেস করছি।"
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.WHATSAPP_AUTOMATION,
                        title = "হোয়াটসঅ্যাপ অটোমেশন",
                        description = reply,
                        recipient = "রাহিম ভাই",
                        body = effectiveCommand
                    )
                )
            }
            lower.contains("সেটিং") || lower.contains("setting") || lower.contains("accessibility") -> {
                val targetSetting = when {
                    lower.contains("accessibility") || lower.contains("অ্যাক্সেসিবিলিটি") -> "ACCESSIBILITY"
                    lower.contains("notification") -> "NOTIFICATION_LISTENER"
                    lower.contains("সাউন্ড") || lower.contains("sound") -> "SOUND"
                    lower.contains("ডিসপ্লে") || lower.contains("display") -> "DISPLAY"
                    else -> "MAIN_SETTINGS"
                }
                reply = if (isEnglishLocked) "Yes Sir, opening system settings ($targetSetting) now." else "জি স্যার, সিস্টেম সেটিংস ($targetSetting) এখনই খুলে দিচ্ছি।"
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.OPEN_SETTINGS,
                        title = "সিস্টেম সেটিংস: $targetSetting",
                        description = reply,
                        settingTarget = targetSetting
                    )
                )
            }
            lower.contains("ওপেন") || lower.contains("খোলো") || lower.contains("খুলো") || lower.contains("open") || lower.contains("खोलो") -> {
                val resolved = deviceExecutor.cleanPlayStoreAppQuery(effectiveCommand)
                val targetApp = resolved.cleanAppName.ifBlank {
                    effectiveCommand.replace(Regex("(?i)(ওপেন|খোলো|খুলো|চালু|করো|দাও|open|app|অ্যাপ|खोलो)"), "").trim()
                }.ifBlank { "YouTube" }
                reply = if (isEnglishLocked) "Yes Sir, opening $targetApp directly now." else "জি স্যার, $targetApp অ্যাপটি এখনই সরাসরি খুলে দিচ্ছি।"
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.OPEN_APP,
                        title = "অ্যাপ ওপেন: $targetApp",
                        description = reply,
                        appName = targetApp,
                        query = targetApp
                    )
                )
            }
            lower.contains("গান") || lower.contains("মিউজিক") || lower.contains("music") || lower.contains("song") -> {
                val off = lower.contains("বন্ধ") || lower.contains("stop")
                reply = if (isEnglishLocked) {
                    if (off) "Yes Sir, music has been stopped." else "Yes Sir, playing music now."
                } else {
                    if (off) "জি স্যার, গান বন্ধ করা হয়েছে।" else "জি স্যার, এখনই গান চালু করে দিচ্ছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = if (off) AgentActionType.STOP_MUSIC else AgentActionType.PLAY_MUSIC,
                        title = "মিউজিক কন্ট্রোল",
                        description = reply,
                        query = effectiveCommand,
                        mood = if (lower.contains("মন খারাপ") || lower.contains("sad")) "SAD" else "ROMANTIC"
                    )
                )
            }
            else -> {
                reply = if (isEnglishLocked) {
                    "Yes Sir, I heard your command and I am ready. You can also tap the KEY button at the top-left to set a custom Gemini API key."
                } else if (_geminiApiKeyState.value.isBlank()) {
                    "স্যার, সরাসরি জেমিনি এআই উত্তর পেতে স্ক্রিনের উপরে বাম পাশের KEY বাটনে ট্যাপ করে আপনার Google AI Studio (AIza...) API Key পেস্ট করে সেভ করুন।"
                } else {
                    "স্যার, আপনার দেওয়া Gemini API Key অথবা ইন্টারনেট কানেকশন চেক করুন যেন জেমিনি সরাসরি উত্তর দিতে পারে।"
                }
            }
        }

        return AgentPlanResponse(
            id = UUID.randomUUID().toString(),
            userPrompt = userPrompt,
            spokenReply = reply,
            summaryTitle = "ARCHER AI",
            steps = steps,
            triggeredByWakeWord = triggeredByWakeWord
        )
    }

    private fun lowerCmdContainsLocationOrRotate(lower: String): Boolean {
        return lower.contains("location") ||
            lower.contains("gps") ||
            lower.contains("রোটেশন") ||
            lower.contains("rotate") ||
            lower.contains("ডার্ক মোড") ||
            lower.contains("dark mode") ||
            lower.contains("dnd") ||
            lower.contains("ব্যাটারি সেভার") ||
            lower.contains("battery saver")
    }

    private suspend fun executePlanStepsIfEnabled(plan: AgentPlanResponse) {
        if (!_autoExecute.value || plan.steps.isEmpty()) return
        val updatedSteps = plan.steps.toMutableList()
        for (index in updatedSteps.indices) {
            val step = updatedSteps[index]
            val result = deviceExecutor.executeStep(step, launchExternalIntents = index == 0)
            multimodalLiveService.recordLiveToolExecution("${step.actionType.name}: $result")
            updatedSteps[index] = step.copy(isExecuted = true, executionResult = result)
            val updatedPlan = plan.copy(steps = updatedSteps.toList())
            _agentPlans.value = _agentPlans.value.map {
                if (it.id == plan.id) updatedPlan else it
            }
            _uiState.value = AgentUiState.Success(updatedPlan)
            if (index < updatedSteps.lastIndex) {
                delay(220L)
            }
        }
    }

    private suspend fun logPlanToHistory(userPrompt: String, plan: AgentPlanResponse) {
        historyRepo.logCommand(
            userPrompt = userPrompt,
            agentReply = plan.spokenReply,
            actionsSummary = if (plan.steps.isEmpty()) "AI Conversation" else plan.steps.joinToString(" • ") { it.title },
            primaryActionType = plan.steps.firstOrNull()?.actionType?.name ?: "ANSWER_QUESTION"
        )
    }

    fun executeSingleStep(planId: String, step: AgentStep) {
        val result = deviceExecutor.executeStep(step, launchExternalIntents = true)
        _liveArcherReply.value = result
        voiceBridge.speak(result)
        _agentPlans.value = _agentPlans.value.map { plan ->
            if (plan.id != planId) {
                plan
            } else {
                plan.copy(
                    steps = plan.steps.map { s ->
                        if (s.id == step.id) s.copy(isExecuted = true, executionResult = result) else s
                    }
                )
            }
        }
    }

    fun runSmartRoutine(routine: SmartRoutine) {
        viewModelScope.launch {
            _liveUserTranscript.value = routine.promptCommand
            val executedSteps = routine.presetSteps.mapIndexed { idx, step ->
                val res = deviceExecutor.executeStep(step, launchExternalIntents = idx == routine.presetSteps.lastIndex)
                step.copy(isExecuted = true, executionResult = res)
            }
            val reply = "জি স্যার! ${routine.titleBn} চালু করা হয়েছে।"
            _liveArcherReply.value = reply
            val plan = AgentPlanResponse(
                id = UUID.randomUUID().toString(),
                userPrompt = routine.promptCommand,
                spokenReply = reply,
                summaryTitle = routine.titleBn,
                steps = executedSteps
            )
            _agentPlans.value = listOf(plan) + _agentPlans.value
            _uiState.value = AgentUiState.Success(plan)
            voiceBridge.speak(plan.spokenReply)
        }
    }

    fun executeTodayTask(task: TodayTaskEntity) {
        viewModelScope.launch {
            historyRepo.toggleTaskCompleted(task.id, !task.isCompleted)
            if (!task.isCompleted) {
                processUserCommand(task.autoCommandPrompt)
            }
        }
    }

    fun addTodayTask(title: String, prompt: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            historyRepo.addTask(title, prompt.ifBlank { title })
        }
    }

    fun deleteTodayTask(id: Int) {
        viewModelScope.launch {
            historyRepo.deleteTask(id)
        }
    }

    fun addPlatformAccount(platform: String, name: String, handleOrNumber: String, notes: String) {
        if (name.isBlank() || handleOrNumber.isBlank()) return
        viewModelScope.launch {
            historyRepo.addPlatformAccount(platform, name, handleOrNumber, notes)
            val msg = "জি স্যার, $platform এ $name এর আইডি সংরক্ষণ করা হয়েছে।"
            _liveArcherReply.value = msg
            voiceBridge.speak(msg)
        }
    }

    fun deletePlatformAccount(id: Int) {
        viewModelScope.launch {
            historyRepo.deletePlatformAccount(id)
        }
    }

    fun addManualMemoryNote(title: String, content: String, category: String = "USER_PREFERENCE") {
        if (content.isBlank()) return
        viewModelScope.launch {
            historyRepo.saveMemoryNote(title.ifBlank { "কিউব নোট" }, content, category)
            val msg = "জি স্যার, আপনার তথ্যটি মেমোরিতে সংরক্ষণ করা হয়েছে।"
            _liveArcherReply.value = msg
            voiceBridge.speak(msg)
        }
    }

    fun deleteMemoryNote(id: Int) {
        viewModelScope.launch {
            historyRepo.deleteMemoryNote(id)
        }
    }

    fun sendQuickNotificationReply(targetSender: String, replyText: String) {
        val (_, status) = NotificationReplyBus.sendInlineReply(
            context = getApplication<Application>().applicationContext,
            targetContactOrApp = targetSender,
            replyText = replyText
        )
        _liveArcherReply.value = status
        voiceBridge.speak(status)
    }

    fun readNotificationsAloud(appFilter: String = "") {
        val summary = NotificationReplyBus.readNotificationsSummary(appFilter)
        _liveArcherReply.value = summary
        voiceBridge.speak(summary)
    }

    fun playCuratedTrack(track: CuratedTrack) {
        musicEngine.playTrack(track)
    }

    fun playMoodImmediately(mood: MusicMood) {
        val firstForMood = BuiltInMusicEngine.CURATED_TRACKS.firstOrNull { it.mood == mood }
            ?: BuiltInMusicEngine.CURATED_TRACKS.first()
        musicEngine.playTrack(firstForMood)
    }

    fun openExternalMusicApp(query: String, artist: String) {
        deviceExecutor.openExternalMusicSearch(query, artist)
    }

    fun deleteHistoryItem(id: Int) {
        viewModelScope.launch {
            historyRepo.deleteHistoryItem(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            historyRepo.clearHistory()
        }
    }

    override fun onCleared() {
        super.onCleared()
        musicEngine.stop()
        voiceBridge.release()
        multimodalLiveService.release()
    }

    companion object {
        val DEFAULT_HEADLINES = listOf(
            HeadlineItem(
                id = "hl_1",
                title = "AI News Today, October 19: T...",
                dateLabel = "Oct 19",
                summaryBn = "মোবাইল জার্ভিস এজেন্ট এখন ভয়েস কমান্ডের মাধ্যমে নিজে নিজে সিদ্ধান্ত নিয়ে সরাসরি প্লে স্টোর, ক্রোম, হোয়াটসঅ্যাপ ও টেলিগ্রাম নিয়ন্ত্রণ করতে সক্ষম।",
                sourceUrl = "https://blog.google/technology/ai/"
            ),
            HeadlineItem(
                id = "hl_2",
                title = "AI News Today – Latest AI A...",
                dateLabel = "Oct 18",
                summaryBn = "অ্যান্ড্রয়েড অ্যাক্সেসিবিলিটি এবং নোটিফিকেশন ইঞ্জিনের সমন্বয়ে এক কথায় অ্যাপ ইনস্টল ও ফাইল ডাউনলোড এখন আরও দ্রুত।",
                sourceUrl = "https://developer.android.com/ai"
            ),
            HeadlineItem(
                id = "hl_3",
                title = "AI News Today, October 16: T...",
                dateLabel = "Oct 16",
                summaryBn = "দীর্ঘমেয়াদী নিউরাল মেমোরি প্রযুক্তির কারণে এআই এজেন্ট ইউজারের সব কথা ও ওয়েবসাইটের তথ্য মনে রাখতে পারে।",
                sourceUrl = "https://deepmind.google/"
            ),
            HeadlineItem(
                id = "hl_4",
                title = "AI News Today, October 15: T...",
                dateLabel = "Oct 15",
                summaryBn = "বাংলা ভয়েস কমান্ড ও ওয়েক-ওয়ার্ড প্রযুক্তির মাধ্যমে লক স্ক্রিন থেকেও সম্পূর্ণ মোবাইল পরিচালনা।",
                sourceUrl = "https://ai.google.dev/"
            ),
            HeadlineItem(
                id = "hl_5",
                title = "AI News Today, October 14: Autonomous OS Agents",
                dateLabel = "Oct 14",
                summaryBn = "জেনারেটিভ এআই এখন ইউজারের সংক্ষিপ্ত নির্দেশ থেকেই মাল্টি-স্টেপ অ্যাকশন প্ল্যান তৈরি করে স্বয়ংক্রিয়ভাবে কাজ সম্পন্ন করে।",
                sourceUrl = "https://blog.google/"
            )
        )

        val PRESET_ROUTINES = listOf(
            SmartRoutine(
                id = "routine_playstore_install",
                titleBn = "প্লে স্টোর অটো অ্যাপ ইনস্টলার",
                subtitleBn = "প্লে স্টোর খুলে যেকোনো অ্যাপ সার্চ করে নিজে নিজে Install বাটনে ক্লিক করবে",
                iconName = "PLAYSTORE",
                accentHex = 0xFF00FF66,
                promptCommand = "প্লে স্টোর থেকে Telegram অ্যাপটা ডাউনলোড করো",
                presetSteps = listOf(
                    AgentStep(
                        id = "ps_install_1",
                        actionType = AgentActionType.INSTALL_APP_PLAYSTORE,
                        title = "প্লে স্টোর থেকে অ্যাপ ইনস্টল",
                        description = "Google Play Store ওপেন করে অটোমেটিক Install ক্লিক করা হচ্ছে",
                        query = "Telegram",
                        appName = "Telegram"
                    )
                )
            ),
            SmartRoutine(
                id = "routine_telegram_download",
                titleBn = "টেলিগ্রাম চ্যানেল ফাইল সার্চ ও ডাউনলোড",
                subtitleBn = "টেলিগ্রামে গিয়ে ফাইল বা মুভি সার্চ করে আসল ফাইলটি ডাউনলোড করবে",
                iconName = "TELEGRAM",
                accentHex = 0xFF00E5FF,
                promptCommand = "টেলিগ্রামে গিয়ে নতুন AI PDF ও ফাইলটা সার্চ করে ডাউনলোড দাও",
                presetSteps = listOf(
                    AgentStep(
                        id = "tg_dl_1",
                        actionType = AgentActionType.TELEGRAM_AUTOMATION,
                        title = "টেলিগ্রাম ফাইল সার্চ ও ডাউনলোড",
                        description = "টেলিগ্রাম চ্যানেলে প্রবেশ করে ফাইলটি খুঁজে ডাউনলোড শুরু করা হচ্ছে",
                        recipient = "@telegram",
                        targetUrlOrFile = "AI Toolkit PDF",
                        query = "AI Toolkit PDF"
                    )
                )
            ),
            SmartRoutine(
                id = "routine_whatsapp_reply",
                titleBn = "হোয়াটসঅ্যাপ মেসেজ চেক ও অটো-রিপ্লাই",
                subtitleBn = "হোয়াটসঅ্যাপ মেসেজ চেক করে স্যারের নির্দেশ অনুযায়ী সরাসরি রিপ্লাই পাঠাবে",
                iconName = "WHATSAPP",
                accentHex = 0xFF00E676,
                promptCommand = "রাহিম ভাইকে হোয়াটসঅ্যাপে রিপ্লাই দাও যে আমি মিটিংয়ে আছি",
                presetSteps = listOf(
                    AgentStep(
                        id = "wa_rep_1",
                        actionType = AgentActionType.WHATSAPP_AUTOMATION,
                        title = "হোয়াটসঅ্যাপ অটো-রিপ্লাই",
                        description = "রাহিম ভাইয়ের মেসেজে স্বয়ংক্রিয়ভাবে উত্তর পাঠানো হচ্ছে",
                        recipient = "রাহিম ভাই",
                        body = "ভাই, আমি এখন মিটিংয়ে আছি, কিছুক্ষণের মধ্যে কথা বলছি।"
                    )
                )
            ),
            SmartRoutine(
                id = "routine_sad_song",
                titleBn = "মন খারাপ ও স্যাড সং মুড",
                subtitleBn = "ভলিউম ৬৫% করে আবেগী স্যাড পিয়ানো ও অরিজিৎ সিং এর গান চালু করবে",
                iconName = "MUSIC_SAD",
                accentHex = 0xFF8C62FF,
                promptCommand = "ভলিউম ৬৫% করে একটা সুন্দর স্যাড সং চালাও",
                presetSteps = listOf(
                    AgentStep(
                        id = "s_vol_65",
                        actionType = AgentActionType.SET_VOLUME,
                        title = "মিডিয়া ভলিউম ৬৫% সেট",
                        description = "শান্তভাবে গান শোনার জন্য ভলিউম সামঞ্জস্য করা হচ্ছে",
                        volumeStream = "MUSIC",
                        volumePercent = 65
                    ),
                    AgentStep(
                        id = "s_play_sad",
                        actionType = AgentActionType.PLAY_MUSIC,
                        title = "আবেগী স্যাড সং প্লেয়ার চালু",
                        description = "অরিজিৎ সিং ও অর্ণব ভাইবসের মেলানকোলিক ট্র্যাক চালু করা হচ্ছে",
                        query = "Arijit Singh Arnob best sad song",
                        mood = "SAD",
                        artist = "Arijit Singh & Arnob"
                    )
                )
            )
        )

        private fun defaultWelcomePlan(): List<AgentPlanResponse> {
            return listOf(
                AgentPlanResponse(
                    id = "welcome_plan",
                    userPrompt = "",
                    spokenReply = "জি স্যার, আমি প্রস্তুত। যেকোনো প্রশ্ন বা আদেশ বলুন।",
                    summaryTitle = "ARCHER AI — মোবাইল জার্ভিস অনলাইন",
                    steps = emptyList(),
                    triggeredByWakeWord = false
                )
            )
        }
    }
}
