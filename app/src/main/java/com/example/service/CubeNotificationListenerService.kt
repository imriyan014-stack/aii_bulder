package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class InterceptedAppNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val senderOrTitle: String,
    val messageBody: String,
    val timestamp: Long = System.currentTimeMillis(),
    val canReplyInline: Boolean = false,
    val isIncomingCall: Boolean = false,
    val lastRepliedText: String = ""
)

data class CachedReplyAction(
    val pendingIntent: PendingIntent,
    val remoteInputs: Array<RemoteInput>
)

data class NotificationHubState(
    val isListenerConnected: Boolean = false,
    val messages: List<InterceptedAppNotification> = NotificationReplyBus.defaultSampleNotifications(),
    val lastReplyStatus: String = "হোয়াটসঅ্যাপ, টেলিগ্রাম, মেসেঞ্জার ও কল মনিটর প্রস্তুত"
)

object NotificationReplyBus {
    private val replyActionsMap = mutableMapOf<String, CachedReplyAction>()
    private val acceptCallIntentMap = mutableMapOf<String, PendingIntent>()

    private val _state = MutableStateFlow(NotificationHubState())
    val state: StateFlow<NotificationHubState> = _state.asStateFlow()

    fun setConnected(connected: Boolean) {
        _state.value = _state.value.copy(isListenerConnected = connected)
    }

    fun syncPermissionState(context: Context): Boolean {
        val enabledPkgs = try {
            NotificationManagerCompat.getEnabledListenerPackages(context)
        } catch (_: Exception) {
            emptySet()
        }
        val isGranted = _state.value.isListenerConnected || enabledPkgs.contains(context.packageName)
        if (_state.value.isListenerConnected != isGranted) {
            _state.value = _state.value.copy(isListenerConnected = isGranted)
        }
        return isGranted
    }

    fun onNotificationCaptured(
        item: InterceptedAppNotification,
        replyAction: CachedReplyAction?,
        acceptCallIntent: PendingIntent?
    ) {
        if (replyAction != null) {
            replyActionsMap[item.key] = replyAction
        }
        if (acceptCallIntent != null) {
            acceptCallIntentMap[item.key] = acceptCallIntent
        }
        val filtered = _state.value.messages.filterNot {
            it.senderOrTitle == item.senderOrTitle && it.messageBody == item.messageBody
        }
        _state.value = _state.value.copy(
            messages = (listOf(item) + filtered).take(40)
        )
    }

    /**
     * Formats recent WhatsApp, Telegram, Messenger, or all notifications for spoken reading.
     */
    fun readNotificationsSummary(appFilter: String = ""): String {
        val filterLower = appFilter.trim().lowercase()
        val matched = _state.value.messages.filter { msg ->
            filterLower.isBlank() ||
                msg.appLabel.lowercase().contains(filterLower) ||
                msg.senderOrTitle.lowercase().contains(filterLower) ||
                msg.packageName.lowercase().contains(filterLower) ||
                (filterLower.contains("হোয়াটসঅ্যাপ") && msg.appLabel.equals("WhatsApp", ignoreCase = true)) ||
                (filterLower.contains("টেলিগ্রাম") && msg.appLabel.equals("Telegram", ignoreCase = true)) ||
                (filterLower.contains("মেসেঞ্জার") && msg.appLabel.equals("Messenger", ignoreCase = true))
        }.take(3)

        if (matched.isEmpty()) {
            return "জি স্যার, বর্তমানে কোনো নতুন মেসেজ বা নোটিফিকেশন নেই।"
        }

        val formatted = matched.joinToString("। ") { msg ->
            "${msg.appLabel}-এ ${msg.senderOrTitle} লিখেছেন: ${msg.messageBody}"
        }
        return "জি স্যার, আপনার সাম্প্রতিক নোটিফিকেশন: $formatted।"
    }

    /**
     * Sends an inline background reply to the most recent matching notification (WhatsApp, Telegram, Messenger, SMS)
     */
    fun sendInlineReply(context: Context, targetContactOrApp: String, replyText: String): Pair<Boolean, String> {
        val lowerTarget = targetContactOrApp.trim().lowercase()
        val candidate = _state.value.messages.firstOrNull { msg ->
            replyActionsMap.containsKey(msg.key) && (
                lowerTarget.isBlank() ||
                    msg.senderOrTitle.lowercase().contains(lowerTarget) ||
                    msg.appLabel.lowercase().contains(lowerTarget) ||
                    msg.packageName.lowercase().contains(lowerTarget)
                )
        } ?: _state.value.messages.firstOrNull { replyActionsMap.containsKey(it.key) }

        if (candidate != null) {
            val cached = replyActionsMap[candidate.key]
            if (cached != null) {
                return try {
                    val fillIntent = Intent()
                    val bundle = Bundle()
                    for (ri in cached.remoteInputs) {
                        bundle.putCharSequence(ri.resultKey, replyText)
                    }
                    RemoteInput.addResultsToIntent(cached.remoteInputs, fillIntent, bundle)
                    cached.pendingIntent.send(context, 0, fillIntent)

                    val updatedList = _state.value.messages.map {
                        if (it.key == candidate.key) it.copy(lastRepliedText = replyText) else it
                    }
                    _state.value = _state.value.copy(
                        messages = updatedList,
                        lastReplyStatus = "${candidate.appLabel}-এ ${candidate.senderOrTitle}-কে ইনলাইন রিপ্লাই পাঠানো হয়েছে: \"$replyText\""
                    )
                    true to "জি স্যার, ${candidate.appLabel}-এ '${candidate.senderOrTitle}'-কে সরাসরি রিপ্লাই পাঠানো হয়েছে: \"$replyText\""
                } catch (e: Exception) {
                    false to "ইনলাইন রিপ্লাই পাঠানো যায়নি (${e.localizedMessage ?: ""})"
                }
            }
        }

        // Update UI status if simulated/demo notification was matched
        val matchedSample = _state.value.messages.firstOrNull {
            lowerTarget.isBlank() ||
                it.senderOrTitle.lowercase().contains(lowerTarget) ||
                it.appLabel.lowercase().contains(lowerTarget)
        }
        if (matchedSample != null) {
            val updated = _state.value.messages.map {
                if (it.key == matchedSample.key) it.copy(lastRepliedText = replyText) else it
            }
            _state.value = _state.value.copy(
                messages = updated,
                lastReplyStatus = "${matchedSample.appLabel} (${matchedSample.senderOrTitle}) এ রিপ্লাই প্রস্তুত: \"$replyText\""
            )
            return false to "${matchedSample.appLabel}-এ ${matchedSample.senderOrTitle}-এর জন্য মেসেজ প্রস্তুত: \"$replyText\""
        }
        return false to "কোনো অ্যাক্টিভ মেসেজ নোটিফিকেশন পাওয়া যায়নি — সরাসরি অ্যাপ ওপেন করে পাঠানো হচ্ছে"
    }

    fun answerIncomingCallIfPresent(): Boolean {
        val callEntry = acceptCallIntentMap.entries.firstOrNull() ?: return false
        return try {
            callEntry.value.send()
            acceptCallIntentMap.remove(callEntry.key)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun defaultSampleNotifications(): List<InterceptedAppNotification> {
        return listOf(
            InterceptedAppNotification(
                key = "sample_wa_1",
                packageName = "com.whatsapp",
                appLabel = "WhatsApp",
                senderOrTitle = "রাহিম ভাই (প্রজেক্ট গ্রুপ)",
                messageBody = "আজকের মিটিংয়ের প্রেজেন্টেশন ফাইলটা কি রেডি হয়েছে? জানাও।",
                canReplyInline = true
            ),
            InterceptedAppNotification(
                key = "sample_tg_1",
                packageName = "org.telegram.messenger",
                appLabel = "Telegram",
                senderOrTitle = "Tech & AI Resources Channel",
                messageBody = "New Android Jarvis Toolkit PDF & APK v2.4 uploaded for members.",
                canReplyInline = true
            ),
            InterceptedAppNotification(
                key = "sample_fb_1",
                packageName = "com.facebook.orca",
                appLabel = "Messenger",
                senderOrTitle = "তানভীর আহমেদ",
                messageBody = "দোস্ত সিলেটের ট্যুর প্ল্যানটা কখন ফাইনাল করবি?",
                canReplyInline = true
            )
        )
    }
}

class CubeNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationReplyBus.setConnected(true)
        // Immediately load any active notifications already present in the status bar
        try {
            activeNotifications?.forEach { sbn ->
                processStatusBarNotification(sbn)
            }
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        NotificationReplyBus.setConnected(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        processStatusBarNotification(sbn)
    }

    private fun processStatusBarNotification(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
                .orEmpty()

        if (title.isBlank() && text.isBlank()) return

        val appLabel = when {
            pkg.contains("whatsapp") -> "WhatsApp"
            pkg.contains("telegram") -> "Telegram"
            pkg.contains("facebook.orca") -> "Messenger"
            pkg.contains("facebook.katana") -> "Facebook"
            pkg.contains("instagram") -> "Instagram"
            pkg.contains("android.gm") -> "Gmail"
            pkg.contains("mms") || pkg.contains("messaging") -> "SMS"
            else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }

        var cachedReply: CachedReplyAction? = null
        var acceptCallPending: PendingIntent? = null
        val isCall = notification.category == Notification.CATEGORY_CALL

        notification.actions?.forEach { action ->
            val remoteInputs = action.remoteInputs
            if (!remoteInputs.isNullOrEmpty() && action.actionIntent != null) {
                cachedReply = CachedReplyAction(
                    pendingIntent = action.actionIntent,
                    remoteInputs = remoteInputs
                )
            }
            val actTitle = action.title?.toString()?.lowercase().orEmpty()
            if (isCall && (actTitle.contains("answer") || actTitle.contains("accept") || actTitle.contains("রিসিভ"))) {
                acceptCallPending = action.actionIntent
            }
        }

        NotificationReplyBus.onNotificationCaptured(
            item = InterceptedAppNotification(
                key = sbn.key,
                packageName = pkg,
                appLabel = appLabel,
                senderOrTitle = title.ifBlank { appLabel },
                messageBody = text,
                canReplyInline = cachedReply != null,
                isIncomingCall = isCall
            ),
            replyAction = cachedReply,
            acceptCallIntent = acceptCallPending
        )
    }
}
