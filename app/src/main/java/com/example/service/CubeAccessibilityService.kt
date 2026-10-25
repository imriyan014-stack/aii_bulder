package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingUiMacro(
    val id: String = "macro_${System.currentTimeMillis()}",
    val targetPackageHint: String = "",
    val textToType: String = "",
    val clickTargets: List<String> = emptyList(),
    val secondaryClickTargets: List<String> = emptyList(),
    val targetViewIds: List<String> = emptyList(),
    val scrollDirection: String = "", // DOWN, UP, LEFT, RIGHT
    val globalAction: String = "", // BACK, HOME, NOTIFICATIONS, QUICK_SETTINGS, RECENTS
    val quickSettingTileTargets: List<String> = emptyList(),
    val closeShadeAfterClick: Boolean = true,
    val gestureType: String = "", // TAP_XY, TAP_CENTER, DOUBLE_TAP, LONG_PRESS, SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT
    val gestureX: Float = 540f,
    val gestureY: Float = 1100f,
    val maxAutoScrollRetries: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

data class AccessibilityEngineState(
    val isServiceEnabled: Boolean = false,
    val currentForegroundPackage: String = "",
    val visibleScreenText: String = "স্ক্রিন রিডার প্রস্তুত — যেকোনো অ্যাপ বা ওয়েবসাইট বিশ্লেষণ করা যাবে",
    val visibleButtonsAndNodes: List<String> = emptyList(),
    val lastTouchPoint: String = "টাচ রেডি (X: 540, Y: 1100)",
    val lastAutomationResult: String = "কুইক সেটিংস টগল, অটো-ক্লিক, স্ক্রিন টাচ ও কন্টাক্ট স্ক্যানার প্রস্তুত",
    val pendingMacro: PendingUiMacro? = null
)

object AccessibilityAutomationBus {
    private val _state = MutableStateFlow(AccessibilityEngineState())
    val state: StateFlow<AccessibilityEngineState> = _state.asStateFlow()

    var serviceInstance: CubeAccessibilityService? = null

    fun setServiceConnected(connected: Boolean, instance: CubeAccessibilityService?) {
        serviceInstance = instance
        _state.value = _state.value.copy(isServiceEnabled = connected)
    }

    fun requestLiveScreenFrame(onBitmapReady: (Bitmap) -> Unit) {
        val projectedBmp = ScreenCaptureSessionBus.latestCapturedBitmap
        if (ScreenCaptureSessionBus.state.value.hasMediaProjectionGrant && projectedBmp != null && !projectedBmp.isRecycled) {
            onBitmapReady(projectedBmp)
            return
        }
        val svc = serviceInstance
        if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            svc.captureHardwareScreenshotOrFallback(onBitmapReady)
        } else {
            onBitmapReady(renderLiveScreenMatrixBitmap())
        }
    }

    fun renderLiveScreenMatrixBitmap(): Bitmap {
        val snap = _state.value
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(4, 18, 14))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 255, 136)
            textSize = 18f
        }
        canvas.drawText("ARCHER LIVE SCREEN STREAM", 20f, 36f, paint)
        paint.color = Color.rgb(0, 229, 255)
        paint.textSize = 15f
        canvas.drawText("PKG: ${snap.currentForegroundPackage.ifBlank { "com.aistudio.omniagent" }}", 20f, 66f, paint)
        canvas.drawText("TOUCH: ${snap.lastTouchPoint}", 20f, 92f, paint)
        paint.color = Color.WHITE
        paint.textSize = 14f
        val lines = snap.visibleScreenText.chunked(38).take(10)
        var y = 130f
        for (line in lines) {
            canvas.drawText(line, 20f, y, paint)
            y += 24f
        }
        paint.color = Color.rgb(0, 255, 136)
        val btns = "NODES: " + snap.visibleButtonsAndNodes.take(6).joinToString(", ")
        canvas.drawText(btns.take(42), 20f, 420f, paint)
        return bmp
    }

    fun syncPermissionState(context: Context): Boolean {
        val enabledServices = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        } catch (_: Exception) {
            ""
        }
        val isEnabled = serviceInstance != null ||
            enabledServices.contains(context.packageName, ignoreCase = true)
        if (_state.value.isServiceEnabled != isEnabled) {
            _state.value = _state.value.copy(isServiceEnabled = isEnabled)
        }
        return isEnabled
    }

    fun updateScreenSnapshot(pkg: String, screenText: String, buttons: List<String> = emptyList()) {
        _state.value = _state.value.copy(
            currentForegroundPackage = pkg,
            visibleScreenText = screenText.ifBlank { _state.value.visibleScreenText },
            visibleButtonsAndNodes = if (buttons.isNotEmpty()) buttons else _state.value.visibleButtonsAndNodes
        )
    }

    fun updateTouchTelemetry(touchSummary: String) {
        _state.value = _state.value.copy(
            lastTouchPoint = touchSummary,
            lastAutomationResult = touchSummary
        )
    }

    fun queueMacro(macro: PendingUiMacro) {
        val label = when {
            macro.quickSettingTileTargets.isNotEmpty() -> "Quick Settings: ${macro.quickSettingTileTargets.first()}"
            macro.gestureType.isNotBlank() -> "Touch: ${macro.gestureType}"
            else -> (macro.clickTargets + macro.secondaryClickTargets).joinToString("/")
                .ifBlank { macro.textToType.ifBlank { macro.globalAction } }
        }
        _state.value = _state.value.copy(
            pendingMacro = macro,
            lastAutomationResult = "অ্যাক্সেসিবিলিটি ম্যাক্রো সক্রিয়: $label"
        )
        serviceInstance?.tryExecutePendingMacro()
    }

    fun updatePendingMacro(macro: PendingUiMacro?, statusMessage: String) {
        _state.value = _state.value.copy(
            pendingMacro = macro,
            lastAutomationResult = statusMessage
        )
    }

    fun recordMacroSuccess(summary: String) {
        _state.value = _state.value.copy(
            pendingMacro = null,
            lastAutomationResult = summary
        )
    }
}

class CubeAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var quickSettingsShadeOpenedForMacroId: String = ""

    override fun onServiceConnected() {
        TouchEffectBus.load(applicationContext)
        super.onServiceConnected()
        AccessibilityAutomationBus.setServiceConnected(true, this)
    }

    @Volatile private var lastRealShot: Bitmap? = null
    @Volatile private var lastShotAt = 0L
    @Volatile private var lastRefreshAt = 0L

    /** Fresh UI tree WITH coordinates -> bus + persistent per-app memory. Safe to call every ~second. */
    fun refreshSnapshotNow(): List<UiElem> {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return emptyList()
        val pkg = root.packageName?.toString().orEmpty()
        val elems = ArrayList<UiElem>()
        walkNodes(root, elems, 0)
        lastRefreshAt = android.os.SystemClock.uptimeMillis()
        if (pkg.isNotBlank() && pkg != packageName && elems.isNotEmpty()) {
            ScreenMemory.record(applicationContext, pkg, elems)
            AccessibilityAutomationBus.updateScreenSnapshot(
                pkg = pkg,
                screenText = elems.take(60).joinToString(" | ") { it.label },
                buttons = elems.filter { it.clickable || it.editable }.take(30).map { "${it.label}@${it.cx},${it.cy}" }
            )
        }
        return elems
    }

    private fun walkNodes(node: AccessibilityNodeInfo?, out: MutableList<UiElem>, depth: Int) {
        if (node == null || depth > 28 || out.size >= 300) return
        try {
            if (node.isVisibleToUser) {
                val raw = node.text?.toString().orEmpty().ifBlank { node.contentDescription?.toString().orEmpty() }
                    .ifBlank { node.viewIdResourceName?.substringAfter('/').orEmpty() }
                val label = raw.replace('\n', ' ').trim().take(60)
                if (label.isNotBlank()) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0) {
                        out.add(UiElem(label, r.centerX(), r.centerY(),
                            node.isClickable || node.isLongClickable, node.isEditable))
                    }
                }
            }
            for (i in 0 until node.childCount) walkNodes(node.getChild(i), out, depth + 1)
        } catch (_: Exception) {}
    }

    fun captureHardwareScreenshotOrFallback(onBitmapReady: (Bitmap) -> Unit) {
        // Android rate-limits takeScreenshot to ~1/sec: reuse the last REAL shot instead of a fake text image
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (nowMs - lastShotAt < 1100L) { lastRealShot?.let { onBitmapReady(it); return } }
        lastShotAt = nowMs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hwBmp = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace
                                )
                                val softBmp = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                                screenshot.hardwareBuffer.close()
                                if (softBmp != null) {
                                    lastRealShot = softBmp
                                    onBitmapReady(softBmp)
                                } else {
                                    onBitmapReady(lastRealShot ?: AccessibilityAutomationBus.renderLiveScreenMatrixBitmap())
                                }
                            } catch (_: Exception) {
                                onBitmapReady(lastRealShot ?: AccessibilityAutomationBus.renderLiveScreenMatrixBitmap())
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            onBitmapReady(lastRealShot ?: AccessibilityAutomationBus.renderLiveScreenMatrixBitmap())
                        }
                    }
                )
            } catch (_: Exception) {
                onBitmapReady(lastRealShot ?: AccessibilityAutomationBus.renderLiveScreenMatrixBitmap())
            }
        } else {
            onBitmapReady(lastRealShot ?: AccessibilityAutomationBus.renderLiveScreenMatrixBitmap())
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotBlank() && pkg != packageName && event != null) {
            val evType = event.eventType
            if (evType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                evType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
            ) {
                val evText = event.text?.joinToString(" ").orEmpty().lowercase()
                val desc = event.contentDescription?.toString().orEmpty().lowercase()
                val viewId = try {
                    event.source?.viewIdResourceName.orEmpty().lowercase()
                } catch (_: Exception) {
                    ""
                }
                val combined = "$evText $desc $viewId"
                val isExternalMicTrigger = combined.contains("voice") ||
                    combined.contains("mic") ||
                    combined.contains("record") ||
                    combined.contains("audio_btn") ||
                    combined.contains("speech") ||
                    combined.contains("dictat") ||
                    combined.contains("ভয়েস") ||
                    combined.contains("মাইক") ||
                    combined.contains("রেকর্ড")
                if (isExternalMicTrigger) {
                    ExternalMicArbiter.notifyProactiveExternalMicYield(
                        durationMs = 3800L,
                        reasonBn = "অন্য অ্যাপে মাইক ব্যবহৃত হচ্ছে — কাজ শেষ হলেই অটো সক্রিয় হবে"
                    )
                }
            }
        }

        if (pkg != packageName && android.os.SystemClock.uptimeMillis() - lastRefreshAt > 350L) {
            refreshSnapshotNow()
        }

        val root = rootInActiveWindow ?: return

        // Extract visible text and clickable buttons from current screen for Full Screen Vision
        if (pkg.isNotBlank() && pkg != packageName) {
            val collected = mutableListOf<String>()
            val buttons = mutableListOf<String>()
            collectVisibleTextAndButtons(root, collected, buttons, maxItems = 45)
            if (collected.isNotEmpty()) {
                AccessibilityAutomationBus.updateScreenSnapshot(
                    pkg = pkg,
                    screenText = collected.joinToString(" | "),
                    buttons = buttons.take(20)
                )
            }
        }

        tryExecutePendingMacro()
    }

    fun tryExecutePendingMacro() {
        val macro = AccessibilityAutomationBus.state.value.pendingMacro ?: return
        if (System.currentTimeMillis() - macro.createdAt > 30_000L) {
            AccessibilityAutomationBus.recordMacroSuccess("ম্যাক্রো সময়সীমা শেষ হয়েছে")
            return
        }

        // 0A. Handle Notification Shade / Quick Settings Tile Auto-Toggle
        if (macro.quickSettingTileTargets.isNotEmpty()) {
            executeQuickSettingsTileToggle(macro)
            return
        }

        // 0B. Handle Direct Screen Touch / Gesture (dispatchGesture)
        if (macro.gestureType.isNotBlank()) {
            executeScreenTouchGesture(macro)
            return
        }

        // 0C. Handle Global Actions (Back, Home, Notifications, Quick Settings, Recents)
        if (macro.globalAction.isNotBlank()) {
            when (macro.globalAction.uppercase()) {
                "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
            AccessibilityAutomationBus.recordMacroSuccess("সিস্টেম নেভিগেশন সম্পন্ন: ${macro.globalAction}")
            return
        }

        val root = rootInActiveWindow ?: return
        val activePkg = root.packageName?.toString().orEmpty()

        // Never execute external app macros on our own app's UI
        if (activePkg == packageName && macro.targetPackageHint.isNotBlank() && macro.targetPackageHint != packageName) {
            return
        }

        // 1. Handle 4-Directional Scroll / Swipe Action (UP, DOWN, LEFT, RIGHT)
        if (macro.scrollDirection.isNotBlank()) {
            val dir = macro.scrollDirection.uppercase()
            when {
                dir.contains("LEFT") || dir.contains("বাম") -> {
                    executeScreenTouchGesture(macro.copy(gestureType = "SWIPE_LEFT"))
                }
                dir.contains("RIGHT") || dir.contains("ডান") -> {
                    executeScreenTouchGesture(macro.copy(gestureType = "SWIPE_RIGHT"))
                }
                else -> {
                    val forward = dir != "UP" && dir != "SCROLL_UP" && !dir.contains("উপর")
                    val scrolled = performScrollOnNode(root, forward)
                    if (!scrolled) {
                        dispatchSwipeGesture(isScrollDown = forward)
                    }
                    AccessibilityAutomationBus.recordMacroSuccess("স্ক্রিন স্ক্রল সম্পন্ন (${macro.scrollDirection})")
                }
            }
            return
        }

        // 2. Handle Auto-Typing into editable text field
        var typedOk = false
        if (macro.textToType.isNotBlank()) {
            typedOk = findAndTypeText(root, macro.textToType, macro.targetViewIds)
        }

        // 3. Handle Auto-Clicking primary target View IDs or primary button text (across all interactive windows)
        var clickedPrimaryLabel: String? = null
        if (!typedOk && macro.targetViewIds.isNotEmpty()) {
            for (viewId in macro.targetViewIds) {
                if (findAndClickByViewIdAcrossWindows(viewId)) {
                    clickedPrimaryLabel = viewId.substringAfterLast('/')
                    break
                }
            }
        }

        if (clickedPrimaryLabel == null && macro.clickTargets.isNotEmpty()) {
            for (targetText in macro.clickTargets) {
                if (findAndClickByTextAcrossWindows(targetText, exactOrButtonPreferred = true)) {
                    clickedPrimaryLabel = targetText
                    break
                }
            }
        }

        // 4. Secondary target (e.g., app card in Play Store or person's name in Contacts/WhatsApp list)
        if (!typedOk && clickedPrimaryLabel == null && macro.secondaryClickTargets.isNotEmpty()) {
            for (secondaryText in macro.secondaryClickTargets) {
                if (findAndClickByTextAcrossWindows(secondaryText, exactOrButtonPreferred = false)) {
                    val nextStageMacro = macro.copy(secondaryClickTargets = emptyList())
                    AccessibilityAutomationBus.updatePendingMacro(
                        macro = nextStageMacro,
                        statusMessage = "'$secondaryText' সিলেক্ট করা হয়েছে, পরবর্তী বাটনে ক্লিক করা হচ্ছে..."
                    )
                    handler.postDelayed({
                        tryExecutePendingMacro()
                    }, 1100L)
                    return
                }
            }
        }

        // 5. If searching for a contact/name across the whole screen and it's further down the list, auto-scroll and retry!
        if (!typedOk && clickedPrimaryLabel == null && macro.maxAutoScrollRetries > 0) {
            val scrolled = performScrollOnNode(root, true) || dispatchSwipeGesture(isScrollDown = true)
            if (scrolled) {
                val retryMacro = macro.copy(maxAutoScrollRetries = macro.maxAutoScrollRetries - 1)
                AccessibilityAutomationBus.updatePendingMacro(
                    macro = retryMacro,
                    statusMessage = "স্ক্রিনে নাম খোঁজা হচ্ছে (স্ক্রল বাকি: ${retryMacro.maxAutoScrollRetries})..."
                )
                handler.postDelayed({
                    tryExecutePendingMacro()
                }, 850L)
                return
            }
        }

        if (typedOk || clickedPrimaryLabel != null) {
            if (typedOk && (macro.clickTargets.isNotEmpty() || macro.targetViewIds.isNotEmpty())) {
                handler.postDelayed({
                    var followUpClicked = false
                    for (viewId in macro.targetViewIds) {
                        if (viewId.contains("send", ignoreCase = true) || viewId.contains("button1", ignoreCase = true)) {
                            if (findAndClickByViewIdAcrossWindows(viewId)) {
                                followUpClicked = true
                                break
                            }
                        }
                    }
                    if (!followUpClicked) {
                        for (t in macro.clickTargets) {
                            if (findAndClickByTextAcrossWindows(t, exactOrButtonPreferred = true)) break
                        }
                    }
                    AccessibilityAutomationBus.recordMacroSuccess("অটো-টাইপ ও সেন্ড সম্পন্ন: \"${macro.textToType}\"")
                }, 550L)
            } else {
                AccessibilityAutomationBus.recordMacroSuccess(
                    buildString {
                        if (typedOk) append("টাইপ: '${macro.textToType}' ")
                        if (clickedPrimaryLabel != null) append("ক্লিক: '$clickedPrimaryLabel'")
                    }.ifBlank { "অটোমেশন সম্পন্ন" }
                )
            }
        }
    }

    /**
     * Pulls down the Notification Bar / Quick Settings shade (`GLOBAL_ACTION_QUICK_SETTINGS`),
     * locates the requested Quick Settings tile (Wi-Fi, Mobile Data, Bluetooth, Airplane Mode,
     * Hotspot, Location, Torch, Auto-Rotate, DND, etc.), clicks it, and closes the shade.
     */
    private fun executeQuickSettingsTileToggle(macro: PendingUiMacro) {
        if (quickSettingsShadeOpenedForMacroId != macro.id) {
            quickSettingsShadeOpenedForMacroId = macro.id
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            AccessibilityAutomationBus.updatePendingMacro(
                macro = macro,
                statusMessage = "নোটিফিকেশন বার নামিয়ে '${macro.quickSettingTileTargets.firstOrNull()}' টগল খোঁজা হচ্ছে..."
            )
            handler.postDelayed({
                tryClickQuickSettingsTileNow(macro, attempt = 1)
            }, 650L)
        } else {
            tryClickQuickSettingsTileNow(macro, attempt = 2)
        }
    }

    private fun tryClickQuickSettingsTileNow(macro: PendingUiMacro, attempt: Int) {
        var clickedTileLabel: String? = null

        // Pass 1: Search all interactive windows (SystemUI Quick Settings shade + Settings Panels)
        for (tileName in macro.quickSettingTileTargets) {
            if (findAndClickByTextAcrossWindows(tileName, exactOrButtonPreferred = false)) {
                clickedTileLabel = tileName
                break
            }
        }

        // Pass 2: If a Settings Panel popup is open with a Switch/ToggleButton, click the first Switch
        if (clickedTileLabel == null) {
            if (clickFirstSwitchOrToggleAcrossWindows()) {
                clickedTileLabel = macro.quickSettingTileTargets.firstOrNull() ?: "Switch"
            }
        }

        if (clickedTileLabel != null) {
            AccessibilityAutomationBus.recordMacroSuccess("নোটিফিকেশন বার টগল সম্পন্ন: '$clickedTileLabel'")
            if (macro.closeShadeAfterClick) {
                handler.postDelayed({
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    } else {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }, 650L)
            }
        } else if (attempt < 2) {
            // Expand Quick Settings a second time to reveal all tiles in the full grid
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            handler.postDelayed({
                tryClickQuickSettingsTileNow(macro, attempt = 2)
            }, 700L)
        } else {
            AccessibilityAutomationBus.recordMacroSuccess(
                "কুইক সেটিংস প্যানেল খোলা হয়েছে: ${macro.quickSettingTileTargets.firstOrNull().orEmpty()}"
            )
        }
    }

    /**
     * Executes real screen touch & swipe gestures using Android's `dispatchGesture` API.
     */
    private fun executeScreenTouchGesture(macro: PendingUiMacro) {
        val type = macro.gestureType.uppercase()
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(720).toFloat()
        val screenH = dm.heightPixels.coerceAtLeast(1280).toFloat()

        val x = if (macro.gestureX > 1f) macro.gestureX.coerceIn(20f, screenW - 20f) else screenW * 0.5f
        val y = if (macro.gestureY > 1f) macro.gestureY.coerceIn(40f, screenH - 40f) else screenH * 0.5f

        when (type) {
            "TAP_XY", "TAP_CENTER", "TAP", "DOUBLE_TAP", "LONG_PRESS" -> TouchEffectOverlay.tap(x, y)
            "SWIPE_LEFT", "SCROLL_RIGHT" -> TouchEffectOverlay.swipe(screenW * 0.84f, screenH * 0.5f, screenW * 0.16f, screenH * 0.5f)
            "SWIPE_RIGHT", "SCROLL_LEFT" -> TouchEffectOverlay.swipe(screenW * 0.16f, screenH * 0.5f, screenW * 0.84f, screenH * 0.5f)
            else -> {}
        }
        when (type) {
            "TAP_XY", "TAP_CENTER", "TAP" -> {
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 90L))
                    .build()
                dispatchGesture(gesture, null, null)
                val summary = "স্ক্রিন টাচ সম্পন্ন (X: ${x.toInt()}, Y: ${y.toInt()})"
                AccessibilityAutomationBus.updateTouchTelemetry(summary)
                AccessibilityAutomationBus.recordMacroSuccess(summary)
            }
            "DOUBLE_TAP" -> {
                val path1 = Path().apply { moveTo(x, y) }
                val path2 = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path1, 0L, 65L))
                    .addStroke(GestureDescription.StrokeDescription(path2, 130L, 65L))
                    .build()
                dispatchGesture(gesture, null, null)
                val summary = "ডাবল ট্যাপ সম্পন্ন (X: ${x.toInt()}, Y: ${y.toInt()})"
                AccessibilityAutomationBus.updateTouchTelemetry(summary)
                AccessibilityAutomationBus.recordMacroSuccess(summary)
            }
            "LONG_PRESS" -> {
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 750L))
                    .build()
                dispatchGesture(gesture, null, null)
                val summary = "লং প্রেস সম্পন্ন (X: ${x.toInt()}, Y: ${y.toInt()})"
                AccessibilityAutomationBus.updateTouchTelemetry(summary)
                AccessibilityAutomationBus.recordMacroSuccess(summary)
            }
            "SWIPE_UP", "SCROLL_DOWN" -> {
                dispatchSwipeGesture(isScrollDown = true)
                AccessibilityAutomationBus.recordMacroSuccess("স্ক্রিন সোয়াইপ ডাউন সম্পন্ন")
            }
            "SWIPE_DOWN", "SCROLL_UP" -> {
                dispatchSwipeGesture(isScrollDown = false)
                AccessibilityAutomationBus.recordMacroSuccess("স্ক্রিন সোয়াইপ আপ সম্পন্ন")
            }
            "SWIPE_LEFT", "SCROLL_RIGHT" -> {
                val path = Path().apply {
                    moveTo(screenW * 0.84f, screenH * 0.5f)
                    lineTo(screenW * 0.16f, screenH * 0.5f)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 260L))
                    .build()
                dispatchGesture(gesture, null, null)
                val summary = "স্ক্রিন বামে সোয়াইপ সম্পন্ন"
                AccessibilityAutomationBus.updateTouchTelemetry(summary)
                AccessibilityAutomationBus.recordMacroSuccess(summary)
            }
            "SWIPE_RIGHT", "SCROLL_LEFT" -> {
                val path = Path().apply {
                    moveTo(screenW * 0.16f, screenH * 0.5f)
                    lineTo(screenW * 0.84f, screenH * 0.5f)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 260L))
                    .build()
                dispatchGesture(gesture, null, null)
                val summary = "স্ক্রিন ডানে সোয়াইপ সম্পন্ন"
                AccessibilityAutomationBus.updateTouchTelemetry(summary)
                AccessibilityAutomationBus.recordMacroSuccess(summary)
            }
            else -> {
                AccessibilityAutomationBus.recordMacroSuccess("টাচ কমান্ড সম্পন্ন")
            }
        }
    }

    private fun dispatchSwipeGesture(isScrollDown: Boolean): Boolean {
        return try {
            val dm = resources.displayMetrics
            val cx = (dm.widthPixels.coerceAtLeast(720)) * 0.5f
            val h = (dm.heightPixels.coerceAtLeast(1280)).toFloat()
            val startY = if (isScrollDown) h * 0.75f else h * 0.28f
            val endY = if (isScrollDown) h * 0.25f else h * 0.78f
            TouchEffectOverlay.swipe(cx, startY, cx, endY)
            val path = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 280L))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    private fun getAllWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (win in windows) {
                win.root?.let { roots.add(it) }
            }
        } catch (_: Exception) {
        }
        rootInActiveWindow?.let { activeRoot ->
            if (roots.none { it == activeRoot }) {
                roots.add(0, activeRoot)
            }
        }
        return roots
    }

    private fun findAndClickByViewIdAcrossWindows(viewId: String): Boolean {
        for (root in getAllWindowRoots()) {
            if (findAndClickByViewId(root, viewId)) return true
        }
        return false
    }

    private fun findAndClickByTextAcrossWindows(
        targetText: String,
        exactOrButtonPreferred: Boolean = false
    ): Boolean {
        for (root in getAllWindowRoots()) {
            if (findAndClickByText(root, targetText, exactOrButtonPreferred)) return true
        }
        return false
    }

    private fun clickFirstSwitchOrToggleAcrossWindows(): Boolean {
        for (root in getAllWindowRoots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                val cls = cur.className?.toString()?.lowercase().orEmpty()
                if (cls.contains("switch") || cls.contains("togglebutton") || cur.isCheckable) {
                    if (clickNodeOrParent(cur)) return true
                }
                for (i in 0 until cur.childCount) {
                    cur.getChild(i)?.let { queue.add(it) }
                }
            }
        }
        return false
    }

    private fun collectVisibleTextAndButtons(
        node: AccessibilityNodeInfo?,
        outList: MutableList<String>,
        outButtons: MutableList<String>,
        maxItems: Int
    ) {
        if (node == null || outList.size >= maxItems) return
        val txt = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val label = txt.ifBlank { desc }
        if (label.length in 2..140 && !outList.contains(label)) {
            outList.add(label)
            if ((node.isClickable || node.parent?.isClickable == true) && outButtons.size < 20) {
                outButtons.add(label)
            }
        }
        for (i in 0 until node.childCount) {
            if (outList.size >= maxItems) break
            collectVisibleTextAndButtons(node.getChild(i), outList, outButtons, maxItems)
        }
    }

    private fun findAndTypeText(
        root: AccessibilityNodeInfo,
        text: String,
        preferredViewIds: List<String> = emptyList()
    ): Boolean {
        for (viewId in preferredViewIds) {
            val idNodes = try {
                root.findAccessibilityNodeInfosByViewId(viewId)
            } catch (_: Exception) {
                null
            }
            if (!idNodes.isNullOrEmpty()) {
                for (node in idNodes) {
                    if (node.isEditable) {
                        val args = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                text
                            )
                        }
                        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                            return true
                        }
                    }
                }
            }
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.isEditable) {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }
                current.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val ok = current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (ok) return true
            }
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun findAndClickByViewId(root: AccessibilityNodeInfo, viewId: String): Boolean {
        if (viewId.isBlank()) return false
        val nodes = try {
            root.findAccessibilityNodeInfosByViewId(viewId)
        } catch (_: Exception) {
            null
        }
        if (!nodes.isNullOrEmpty()) {
            for (node in nodes) {
                if (!node.isEditable && clickNodeOrParent(node)) {
                    return true
                }
            }
        }
        return false
    }

    private fun isSearchBoxOrEditableNode(node: AccessibilityNodeInfo?): Boolean {
        var cur = node
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isEditable) return true
            val cls = cur.className?.toString()?.lowercase().orEmpty()
            val viewId = cur.viewIdResourceName?.lowercase().orEmpty()
            if (cls.contains("edittext") ||
                viewId.contains("search_box") ||
                viewId.contains("search_src_text") ||
                viewId.contains("search_edit")
            ) {
                return true
            }
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun findAndClickByText(
        root: AccessibilityNodeInfo,
        targetText: String,
        exactOrButtonPreferred: Boolean = false
    ): Boolean {
        if (targetText.isBlank()) return false
        val lowerTarget = targetText.trim().lowercase()

        // Pass 1: exact text/contentDescription match
        val queueExact = ArrayDeque<AccessibilityNodeInfo>()
        queueExact.add(root)
        while (queueExact.isNotEmpty()) {
            val cur = queueExact.removeFirst()
            if (!isSearchBoxOrEditableNode(cur)) {
                val txt = cur.text?.toString()?.trim()?.lowercase().orEmpty()
                val desc = cur.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                if (txt == lowerTarget || desc == lowerTarget) {
                    if (clickNodeOrParent(cur)) return true
                }
            }
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queueExact.add(it) }
            }
        }

        // Pass 2: Android framework findAccessibilityNodeInfosByText
        val matchedNodes = root.findAccessibilityNodeInfosByText(targetText)
        if (!matchedNodes.isNullOrEmpty()) {
            for (node in matchedNodes) {
                if (!isSearchBoxOrEditableNode(node)) {
                    val txt = node.text?.toString()?.trim()?.lowercase().orEmpty()
                    val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                    if (!exactOrButtonPreferred || txt.length <= lowerTarget.length + 22 || desc.length <= lowerTarget.length + 22) {
                        if (clickNodeOrParent(node)) return true
                    }
                }
            }
        }

        // Pass 3: Breadth-first substring match on text & contentDescription
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!isSearchBoxOrEditableNode(cur)) {
                val desc = cur.contentDescription?.toString()?.lowercase().orEmpty()
                val txt = cur.text?.toString()?.lowercase().orEmpty()
                if (desc.contains(lowerTarget) || txt.contains(lowerTarget)) {
                    if (!exactOrButtonPreferred || txt.length <= lowerTarget.length + 28 || desc.length <= lowerTarget.length + 28) {
                        if (clickNodeOrParent(cur)) return true
                    }
                }
            }
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable && !current.isEditable) {
                val rect = Rect()
                current.getBoundsInScreen(rect)
                AccessibilityAutomationBus.updateTouchTelemetry(
                    "ট্যাপ: (${rect.centerX()}, ${rect.centerY()})"
                )
                TouchEffectOverlay.tap(rect.centerX().toFloat(), rect.centerY().toFloat())
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        // Fallback: if node has valid screen bounds, tap its center via dispatchGesture
        if (node != null) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 4 && rect.height() > 4) {
                val cx = rect.centerX().toFloat()
                val cy = rect.centerY().toFloat()
                TouchEffectOverlay.tap(cx, cy)
                val path = Path().apply { moveTo(cx, cy) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 85L))
                    .build()
                AccessibilityAutomationBus.updateTouchTelemetry("জেসচার ট্যাপ: (${cx.toInt()}, ${cy.toInt()})")
                return dispatchGesture(gesture, null, null)
            }
        }
        return false
    }

    private fun performScrollOnNode(root: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur.isScrollable) {
                if (cur.performAction(action)) return true
            }
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        AccessibilityAutomationBus.setServiceConnected(false, null)
        super.onDestroy()
    }
}
