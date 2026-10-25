package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LaptopChromebook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.LiveConnectionEngine
import com.example.service.MultimodalLiveSessionState
import com.example.service.ScreenCaptureSessionBus
import com.example.ui.theme.PulseRose
import java.util.concurrent.Executors

@Composable
fun MultimodalLiveStudioBar(
    liveState: MultimodalLiveSessionState,
    onToggleLiveSession: () -> Unit,
    onOpenLiveApiHub: () -> Unit,
    modifier: Modifier = Modifier
) {
    val barShape = RoundedCornerShape(12.dp)
    val isLive = liveState.isLiveActive

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(barShape)
            .background(if (isLive) Color(0xFF1E0812) else Color(0xFF061B15))
            .border(
                width = 1.3.dp,
                color = if (isLive) PulseRose else MatrixGreen,
                shape = barShape
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("multimodal_live_studio_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Primary Start / Stop Multimodal Live Button (Voice + Camera + Screen + Phone Tools)
        Surface(
            color = if (isLive) PulseRose else MatrixGreen,
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier
                .weight(1.25f)
                .clickable { onToggleLiveSession() }
                .testTag("btn_start_multimodal_live")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (isLive) Icons.Default.Stop else Icons.Default.RadioButtonChecked,
                    contentDescription = "Multimodal Live Toggle",
                    tint = if (isLive) Color.White else Color(0xFF02130B),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (isLive) {
                        "LIVE ON • VOICE+CAM+SCREEN"
                    } else {
                        "START MULTIMODAL LIVE"
                    },
                    color = if (isLive) Color.White else Color(0xFF02130B),
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 2. Open Multimodal Live API Hub & Key/WebSocket Exporter Button ("বের করে দেওয়া")
        Surface(
            color = Color(0xFF092327),
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(1.1.dp, CyberCyan),
            modifier = Modifier
                .weight(0.95f)
                .clickable { onOpenLiveApiHub() }
                .testTag("btn_open_live_api_hub")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = "Multimodal Live API Hub",
                    tint = CyberCyan,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "LIVE API KEY & WS",
                    color = CyberCyan,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 9.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultimodalLiveHudOverlayCard(
    liveState: MultimodalLiveSessionState,
    isFrontCamera: Boolean,
    visibleScreenText: String,
    onToggleMicMute: () -> Unit,
    onToggleCameraStream: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleScreenStream: () -> Unit,
    onToggleToolsArmed: () -> Unit,
    onTriggerBargeIn: () -> Unit,
    onSwitchEngine: (LiveConnectionEngine) -> Unit,
    onCameraBitmapCaptured: (android.graphics.Bitmap) -> Unit,
    onAnalyzeLiveNow: () -> Unit,
    onOpenLiveApiHub: () -> Unit,
    onStopLiveSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPerm = granted
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF071A15)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, PulseRose.copy(alpha = 0.9f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("multimodal_live_hud_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Status Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(PulseRose)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "MULTIMODAL LIVE • ${liveState.engineMode.badgeText}",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp
                    )
                }
                Text(
                    text = "${liveState.fpsTelemetry} • ফ্রেম #${liveState.framesSentCount}",
                    color = CyberCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp
                )
            }

            // Modality Status Pills: MIC LIVE | CAM STREAM | SCREEN LIVE | TOOLS ARMED
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LiveModalityPill(
                    label = if (liveState.isMicMuted) "MIC MUTED" else "MIC LIVE",
                    active = !liveState.isMicMuted,
                    activeColor = MatrixGreen,
                    onClick = onToggleMicMute,
                    tag = "live_pill_mic"
                )
                LiveModalityPill(
                    label = if (liveState.isCameraStreamEnabled) {
                        if (isFrontCamera) "CAM: FRONT" else "CAM: BACK"
                    } else {
                        "CAM OFF"
                    },
                    active = liveState.isCameraStreamEnabled,
                    activeColor = CyberCyan,
                    onClick = onToggleCameraStream,
                    tag = "live_pill_cam"
                )
                LiveModalityPill(
                    label = if (liveState.isScreenStreamEnabled) "SCREEN LIVE" else "SCREEN OFF",
                    active = liveState.isScreenStreamEnabled,
                    activeColor = MatrixGreen,
                    onClick = onToggleScreenStream,
                    tag = "live_pill_screen"
                )
                LiveModalityPill(
                    label = if (liveState.isToolsArmed) "TOOLS ARMED" else "TOOLS OFF",
                    active = liveState.isToolsArmed,
                    activeColor = CircuitOrange,
                    onClick = onToggleToolsArmed,
                    tag = "live_pill_tools"
                )
            }

            // Split View: Live Camera Feed (Left) + Live Screen & Waveform Telemetry (Right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Camera Preview Box
                Box(
                    modifier = Modifier
                        .weight(0.95f)
                        .height(108.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF030D0A))
                        .border(1.dp, CyberCyan.copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (liveState.isCameraStreamEnabled && hasCameraPerm) {
                        LiveCameraXPreviewBox(
                            isFrontCamera = isFrontCamera,
                            onFrameCaptured = onCameraBitmapCaptured,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(6.dp)
                        ) {
                            Icon(
                                imageVector = if (liveState.isCameraStreamEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = null,
                                tint = CyberCyan,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (!liveState.isCameraStreamEnabled) {
                                    "ক্যামেরা স্ট্রিম বন্ধ"
                                } else {
                                    "লাইভ ক্যামেরা ফিড চালু করুন"
                                },
                                color = Color.White,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (liveState.isCameraStreamEnabled && !hasCameraPerm) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    color = MatrixGreen,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.clickable {
                                        cameraPermLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                ) {
                                    Text(
                                        text = "ক্যামেরা পারমিশন দিন",
                                        color = Color.Black,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Lens flip mini badge
                    Surface(
                        color = Color(0xAA02100C),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .clickable { onFlipCamera() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlipCameraAndroid,
                                contentDescription = "Flip Camera",
                                tint = CyberCyan,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = if (isFrontCamera) "FRONT" else "BACK",
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Live Screen Share Feed + Audio Waveform Column
                val latestScreenBitmap by ScreenCaptureSessionBus.latestBitmapFlow.collectAsStateWithLifecycle()

                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .height(108.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF04120E))
                        .border(1.dp, MatrixGreen.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .clickable { onToggleScreenStream() },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = latestScreenBitmap
                    if (liveState.isScreenStreamEnabled && bmp != null && !bmp.isRecycled) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Live Screen Share Preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Surface(
                            color = Color(0xCC02100C),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                        ) {
                            Text(
                                text = "● SCREEN LIVE",
                                color = MatrixGreen,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ScreenShare,
                                    contentDescription = null,
                                    tint = MatrixGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (liveState.isScreenStreamEnabled) {
                                        "স্ক্রিন শেয়ার চালু..."
                                    } else {
                                        "স্ক্রিন শেয়ার চালু করতে ট্যাপ করুন"
                                    },
                                    color = MatrixGreen,
                                    fontSize = 9.2.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Text(
                                text = "স্ক্রিন টেক্সট: ${visibleScreenText.take(55)}",
                                color = Color(0xFFD0F5EA),
                                fontSize = 8.5.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            // 16-Bar Live Audio Waveform
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(20.dp),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                liveState.audioWaveformLevels.forEachIndexed { idx, level ->
                                    val barColor = if (idx % 2 == 0) MatrixGreen else CyberCyan
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height((20f * level).dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(barColor)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Live Tool Execution Telemetry Banner
            Surface(
                color = Color(0xFF0B231D),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, CircuitOrange.copy(alpha = 0.65f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = liveState.lastToolExecuted,
                    color = CircuitOrange,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }

            // Action Row: Barge-In | Analyze Live Stream Now | API Hub | Stop Live
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onTriggerBargeIn,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .testTag("btn_live_barge_in")
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = MatrixGreen, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("বার্জ-ইন (থামিয়ে বলুন)", fontSize = 9.sp, color = MatrixGreen, maxLines = 1)
                }

                Button(
                    onClick = onAnalyzeLiveNow,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberCyan,
                        contentColor = Color.Black
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .weight(1.05f)
                        .height(34.dp)
                        .testTag("btn_analyze_live_stream_now")
                ) {
                    Text("⚡ লাইভ ভিশন বিশ্লেষণ", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }

                OutlinedButton(
                    onClick = onStopLiveSession,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("btn_stop_live_hud")
                ) {
                    Text("বন্ধ", fontSize = 9.sp, color = PulseRose)
                }
            }
        }
    }
}

@Composable
private fun LiveModalityPill(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    tag: String
) {
    Surface(
        color = if (active) activeColor.copy(alpha = 0.18f) else Color(0xFF0A1815),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (active) activeColor else Color(0xFF28463E)),
        modifier = Modifier
            .clickable { onClick() }
            .testTag(tag)
    ) {
        Text(
            text = label,
            color = if (active) activeColor else Color(0xFF8AA8A4),
            fontSize = 8.8.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun LiveCameraXPreviewBox(
    isFrontCamera: Boolean,
    onFrameCaptured: (android.graphics.Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(isFrontCamera, lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                var lastCaptureTime = 0L
                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    val now = System.currentTimeMillis()
                    if (now - lastCaptureTime >= 1600L) {
                        lastCaptureTime = now
                        try {
                            val bmp = imageProxy.toBitmap()
                            onFrameCaptured(bmp)
                        } catch (_: Exception) {
                        }
                    }
                    imageProxy.close()
                }

                val selector = if (isFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageAnalysis
                )
            } catch (_: Exception) {
            }
        }
        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultimodalLiveApiHubSheet(
    liveState: MultimodalLiveSessionState,
    customApiKey: String,
    onSaveCustomApiKey: (String) -> Unit,
    onSelectEngineMode: (LiveConnectionEngine) -> Unit,
    onToggleLiveSession: () -> Unit,
    onCopyConfigItem: (String, String) -> Unit,
    onExportLiveApiToArcherFolder: () -> Unit,
    onClose: () -> Unit
) {
    val cfg = liveState.exportConfig
    var customKeyDraft by rememberSaveable(customApiKey) { mutableStateOf(customApiKey) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val textToolbar = androidx.compose.ui.platform.LocalTextToolbar.current

    DisposableEffect(Unit) {
        onDispose {
            runCatching { textToolbar.hide() }
            runCatching { focusManager.clearFocus(force = true) }
        }
    }

    val safeClose = {
        runCatching { textToolbar.hide() }
        runCatching { focusManager.clearFocus(force = true) }
        onClose()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6020907))
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF061512)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, CyberCyan, RoundedCornerShape(22.dp))
                .testTag("multimodal_live_api_hub_modal")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "MULTIMODAL LIVE API HUB • লাইভ এপিআই এক্সপোর্টার",
                            style = MaterialTheme.typography.titleSmall,
                            color = MatrixGreen,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "ভয়েস + ক্যামেরা + স্ক্রিন শেয়ারিং + ওয়েবসকেট এপিআই কি এক্সপোর্ট",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberCyan
                        )
                    }
                    IconButton(onClick = safeClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    // 1. Start / Stop Live & Dual-Engine Mode Selector
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF09221C)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "১. মাল্টিমোডাল লাইভ ইঞ্জিন ও সেশন কন্ট্রোল",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MatrixGreen,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    LiveConnectionEngine.entries.forEach { engine ->
                                        FilterChip(
                                            selected = liveState.engineMode == engine,
                                            onClick = { onSelectEngineMode(engine) },
                                            label = { Text(engine.badgeText, fontSize = 10.sp) },
                                            modifier = Modifier.testTag("chip_live_engine_${engine.id}")
                                        )
                                    }
                                }
                                Text(
                                    text = liveState.engineMode.labelBn,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFC8F7E5)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = onToggleLiveSession,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (liveState.isLiveActive) PulseRose else MatrixGreen,
                                        contentColor = if (liveState.isLiveActive) Color.White else Color.Black
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("hub_btn_toggle_live_session")
                                ) {
                                    Icon(
                                        imageVector = if (liveState.isLiveActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (liveState.isLiveActive) {
                                            "লাইভ সেশন বন্ধ করুন (STOP MULTIMODAL LIVE)"
                                        } else {
                                            "এখনই মাল্টিমোডাল লাইভ চালু করুন (VOICE + CAM + SCREEN)"
                                        },
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }

                    // 2. Extracted Built-in Cloud API Key & Endpoints ("বের করে দেওয়া")
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF081D20)),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "২. বের করে দেওয়া Multimodal Live API Key ও WebSocket তথ্য",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = CyberCyan,
                                    fontWeight = FontWeight.Bold
                                )
                                if (liveState.lastCopiedLabel.isNotBlank()) {
                                    Text(
                                        text = "✓ সর্বশেষ কপি করা হয়েছে: ${liveState.lastCopiedLabel}",
                                        color = MatrixGreen,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                ExportableCredentialRow(
                                    title = "Built-in Firebase Gemini API Key",
                                    value = cfg.builtInFirebaseApiKey.ifBlank { "App Check Managed Cloud Key" },
                                    onCopy = {
                                        onCopyConfigItem(
                                            "Built-in Gemini API Key",
                                            cfg.builtInFirebaseApiKey
                                        )
                                    },
                                    tag = "btn_copy_builtin_live_key"
                                )

                                ExportableCredentialRow(
                                    title = "Active Effective Live API Key",
                                    value = cfg.effectiveApiKey.ifBlank { "Built-in Cloud Stream Active" },
                                    onCopy = {
                                        onCopyConfigItem(
                                            "Effective Live API Key",
                                            cfg.effectiveApiKey
                                        )
                                    },
                                    tag = "btn_copy_effective_live_key"
                                )

                                ExportableCredentialRow(
                                    title = "Project ID & App Package",
                                    value = "Project: ${cfg.projectId} | Pkg: ${cfg.packageName}",
                                    onCopy = {
                                        onCopyConfigItem(
                                            "Project & Package Info",
                                            "project_id=${cfg.projectId}\napp_id=${cfg.applicationId}\npackage=${cfg.packageName}"
                                        )
                                    },
                                    tag = "btn_copy_project_info"
                                )

                                ExportableCredentialRow(
                                    title = "Multimodal Live WebSocket URL (BidiGenerateContent)",
                                    value = cfg.fullWebsocketUrlWithKey,
                                    onCopy = {
                                        onCopyConfigItem(
                                            "Multimodal Live WebSocket URL",
                                            cfg.fullWebsocketUrlWithKey
                                        )
                                    },
                                    tag = "btn_copy_live_ws_url"
                                )
                            }
                        }
                    }

                    // 3. Custom Gemini Live API Key Input & Override
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1E1B)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "৩. কাস্টম Gemini Live API Key সেট বা পরিবর্তন করুন",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MatrixGreen,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "খালি রাখলে অ্যাপের বিল্ট-ইন ক্লাউড এআই ও প্রজেক্ট কি স্বয়ংক্রিয়ভাবে কাজ করবে।",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFB4D6D2)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = customKeyDraft,
                                        onValueChange = { customKeyDraft = it },
                                        placeholder = { Text("AIzaSy... (Custom Gemini Live Key)") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("input_custom_live_api_key")
                                    )
                                    Button(
                                        onClick = {
                                            runCatching { textToolbar.hide() }
                                            runCatching { focusManager.clearFocus(force = true) }
                                            onSaveCustomApiKey(customKeyDraft.trim())
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MatrixGreen,
                                            contentColor = Color.Black
                                        ),
                                        modifier = Modifier.testTag("btn_save_custom_live_key")
                                    ) {
                                        Text("সেভ", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // 4. Export Setup JSON, Tools Schema & Save to 'archer' Folder
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF071915)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "৪. WebSocket Setup JSON, Tools Schema ও 'archer' ফোল্ডার এক্সপোর্ট",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = CyberCyan,
                                    fontWeight = FontWeight.Bold
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            onCopyConfigItem(
                                                "WebSocket Setup JSON",
                                                cfg.setupPayloadJson
                                            )
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("btn_copy_setup_json")
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = MatrixGreen, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Setup JSON কপি", color = MatrixGreen, fontSize = 10.sp)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            onCopyConfigItem(
                                                "Live Function Tools JSON",
                                                cfg.toolsDeclarationJson
                                            )
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("btn_copy_tools_json")
                                    ) {
                                        Icon(Icons.Default.Code, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Tools JSON কপি", color = CyberCyan, fontSize = 10.sp)
                                    }
                                }

                                Button(
                                    onClick = onExportLiveApiToArcherFolder,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CyberCyan,
                                        contentColor = Color.Black
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("btn_export_live_api_to_archer")
                                ) {
                                    Icon(Icons.Default.FolderZip, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "📦 'archer' ফোল্ডারে Live API Key, JSON ও ক্লায়েন্ট কোড সেভ করুন (ZIP)",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 10.5.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportableCredentialRow(
    title: String,
    value: String,
    onCopy: () -> Unit,
    tag: String
) {
    Surface(
        color = Color(0xFF041214),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFF1B454B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MatrixGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = value,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onCopy,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag(tag)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    tint = CyberCyan,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("কপি", fontSize = 10.sp, color = CyberCyan, fontWeight = FontWeight.Bold)
            }
        }
    }
}
