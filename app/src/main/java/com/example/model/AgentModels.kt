package com.example.model

enum class AgentActionType {
    PLAY_MUSIC,
    STOP_MUSIC,
    SEND_EMAIL,
    SEND_MESSAGE,
    DIAL_NUMBER,
    SET_VOLUME,
    SET_RINGER_MODE,
    TOGGLE_FLASHLIGHT,
    OPEN_SETTINGS,
    SET_ALARM,
    SET_TIMER,
    OPEN_APP,
    WEB_SEARCH,
    OPEN_MAP,
    ANSWER_QUESTION,
    SAVE_MEMORY_NOTE,
    READ_DEVICE_STATUS,
    SHARE_CONTENT,
    OPEN_CAMERA,
    // Universal Mobile Jarvis & Cross-App Automation Actions
    INSTALL_APP_PLAYSTORE,
    INSTALL_APK_PACKAGE,
    READ_NOTIFICATIONS,
    CHROME_WEB_ACTION,
    DOWNLOAD_FILE_MEDIA,
    WHATSAPP_AUTOMATION,
    TELEGRAM_AUTOMATION,
    SCREEN_UI_CONTROL,
    CALL_CONTROL,
    SCHEDULE_TASK,
    TOGGLE_SYSTEM_SETTING,
    SCAN_CONTACT_OR_SCREEN,
    GENERATE_CODE_PROJECT,
    UPDATE_AGENT_RULE
}

enum class WakeWordPhase {
    INACTIVE,
    LISTENING_FOR_WAKE_WORD,
    GREETING_BOSS,
    AWAITING_BOSS_COMMAND,
    PROCESSING_COMMAND
}

enum class JarvisNodeModal {
    NONE,
    MEMORY_AND_IDS,
    CHAT_AND_MESSAGES,
    SOUL_AND_WAKE,
    SETTINGS_AND_PERMS,
    LIVE_CAMERA_VISION,
    HEADLINE_DETAIL,
    CODING_AND_ARGON,
    MULTIMODAL_LIVE_API_HUB
}

enum class MusicMood(
    val titleBn: String,
    val titleEn: String,
    val subtitleBn: String,
    val tempoBpm: Int,
    val baseFreqs: List<Double>
) {
    ROMANTIC(
        titleBn = "রোমান্টিক ও সুন্দর সুর",
        titleEn = "Beautiful & Romantic",
        subtitleBn = "মিষ্টি পিয়ানো ও অ্যাকোস্টিক হারমনি",
        tempoBpm = 88,
        baseFreqs = listOf(261.63, 329.63, 392.00, 493.88, 523.25, 392.00, 329.63, 293.66)
    ),
    SAD(
        titleBn = "স্যাড সং ও আবেগী সুর",
        titleEn = "Sad & Emotional",
        subtitleBn = "ধীরগতির মেলানকোলিক ভায়োলিন ও পিয়ানো",
        tempoBpm = 64,
        baseFreqs = listOf(220.00, 261.63, 329.63, 440.00, 392.00, 349.23, 329.63, 246.94)
    ),
    UPBEAT(
        titleBn = "এনার্জেটিক ও পার্টি বিট",
        titleEn = "Upbeat & Energetic",
        subtitleBn = "রঙিন সিন্থ ও রিদমিক পালস",
        tempoBpm = 124,
        baseFreqs = listOf(293.66, 369.99, 440.00, 587.33, 523.25, 440.00, 369.99, 329.63)
    ),
    LOFI(
        titleBn = "লো-ফাই চিল ও রিলাক্স",
        titleEn = "Lo-Fi Chill & Focus",
        subtitleBn = "নরম অ্যাম্বিয়েন্ট কর্ড ও শান্ত বিট",
        tempoBpm = 76,
        baseFreqs = listOf(174.61, 220.00, 261.63, 329.63, 293.66, 261.63, 220.00, 196.00)
    ),
    CLASSICAL(
        titleBn = "ক্লাসিক্যাল ও রবীন্দ্র মেজাজ",
        titleEn = "Eastern Classical & Melodic",
        subtitleBn = "শান্ত রাগ-অনুপ্রাণিত সরোদ ও বাঁশির আবহ",
        tempoBpm = 72,
        baseFreqs = listOf(261.63, 293.66, 329.63, 392.00, 440.00, 523.25, 440.00, 392.00)
    );

    companion object {
        fun fromString(value: String?): MusicMood {
            if (value.isNullOrBlank()) return ROMANTIC
            val upper = value.uppercase()
            return entries.firstOrNull { it.name == upper }
                ?: when {
                    upper.contains("SAD") || upper.contains("মন খারাপ") || upper.contains("কষ্ট") -> SAD
                    upper.contains("PARTY") || upper.contains("FAST") || upper.contains("DANCE") || upper.contains("UPBEAT") -> UPBEAT
                    upper.contains("LOFI") || upper.contains("CHILL") || upper.contains("SLEEP") -> LOFI
                    upper.contains("CLASSIC") || upper.contains("RABINDRA") || upper.contains("FOLK") -> CLASSICAL
                    else -> ROMANTIC
                }
        }
    }
}

data class CuratedTrack(
    val id: String,
    val title: String,
    val artist: String,
    val mood: MusicMood,
    val language: String,
    val youtubeQuery: String
)

data class AgentStep(
    val id: String,
    val actionType: AgentActionType,
    val title: String,
    val description: String,
    val query: String = "",
    val mood: String = "ROMANTIC",
    val artist: String = "",
    val recipient: String = "",
    val subject: String = "",
    val body: String = "",
    val phoneNumber: String = "",
    val settingTarget: String = "",
    val settingEnabled: Boolean = true,
    val volumeStream: String = "MUSIC",
    val volumePercent: Int = 70,
    val ringerMode: String = "NORMAL",
    val flashlightOn: Boolean = false,
    val alarmHour: Int = 7,
    val alarmMinute: Int = 0,
    val timerSeconds: Int = 60,
    val appName: String = "",
    val targetUrlOrFile: String = "",
    val screenControlCommand: String = "", // CLICK, TYPE, SCROLL_DOWN, SCROLL_UP, BACK, HOME, READ_SCREEN, QUICK_SETTINGS, TAP_XY
    val codeFilename: String = "",
    val codeLanguage: String = "",
    val isExecuted: Boolean = false,
    val executionResult: String = ""
)

data class AgentPlanResponse(
    val id: String,
    val userPrompt: String,
    val spokenReply: String,
    val summaryTitle: String,
    val steps: List<AgentStep>,
    val autoLearnedMemory: String = "",
    val modelUsed: String = "gemini-4-argon",
    val triggeredByWakeWord: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class SmartRoutine(
    val id: String,
    val titleBn: String,
    val subtitleBn: String,
    val iconName: String,
    val accentHex: Long,
    val promptCommand: String,
    val presetSteps: List<AgentStep>
)

data class HeadlineItem(
    val id: String,
    val title: String,
    val dateLabel: String,
    val summaryBn: String,
    val sourceUrl: String
)
