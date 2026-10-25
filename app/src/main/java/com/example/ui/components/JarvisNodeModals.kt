package com.example.ui.components

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Shop
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AgentMemoryNoteEntity
import com.example.data.PlatformAccountEntity
import com.example.model.AgentPlanResponse
import com.example.model.AgentStep
import com.example.model.HeadlineItem
import com.example.model.MusicMood
import com.example.service.AccessibilityEngineState
import com.example.service.CodingWorkspaceState
import com.example.service.CubeSentinelBus
import com.example.service.CubeSentinelState
import com.example.service.DeviceHardwareSnapshot
import com.example.service.MusicPlayerState
import com.example.service.NotificationHubState
import com.example.service.SavedArcherArtifact
import com.example.service.VoiceBridgeState
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun MemoryAndPlatformIdsSheet(
    memories: List<AgentMemoryNoteEntity>,
    accounts: List<PlatformAccountEntity>,
    onAddAccount: (String, String, String, String) -> Unit,
    onDeleteAccount: (Int) -> Unit,
    onAddMemory: (String, String) -> Unit,
    onDeleteMemory: (Int) -> Unit,
    onRunCommand: (String) -> Unit,
    onClose: () -> Unit
) {
    var selectedPlatform by rememberSaveable { mutableStateOf("Telegram") }
    var contactName by rememberSaveable { mutableStateOf("") }
    var handleOrUrl by rememberSaveable { mutableStateOf("") }
    var memTitle by rememberSaveable { mutableStateOf("") }
    var memBody by rememberSaveable { mutableStateOf("") }

    JarvisModalContainer(
        title = "MEMORY • নিউরাল মেমোরি ও সব প্লাটফর্ম আইডি",
        accentColor = CyberCyan,
        onClose = onClose
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Text(
                    text = "কিউব আপনার সব কথা, ওয়েবসাইটের তথ্য এবং সব প্লাটফর্মের আইডি/গ্রুপ এখানে মনে রাখে।",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB4D6D2)
                )
            }

            // Add Platform ID / Channel / Website Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1F1D)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "প্লাটফর্ম আইডি / গ্রুপ / ওয়েবসাইট যুক্ত করুন",
                            style = MaterialTheme.typography.titleSmall,
                            color = CyberCyan
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Telegram", "WhatsApp", "Chrome Site", "Facebook").forEach { plat ->
                                FilterChip(
                                    selected = selectedPlatform == plat,
                                    onClick = { selectedPlatform = plat },
                                    label = { Text(plat, style = MaterialTheme.typography.labelMedium) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = contactName,
                            onValueChange = { contactName = it },
                            placeholder = { Text("নাম বা গ্রুপ (যেমন: Movie Channel / রাহিম)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = handleOrUrl,
                                onValueChange = { handleOrUrl = it },
                                placeholder = { Text("@username / +8801... / https://...") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (contactName.isNotBlank() && handleOrUrl.isNotBlank()) {
                                        onAddAccount(selectedPlatform, contactName, handleOrUrl, "")
                                        contactName = ""
                                        handleOrUrl = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberCyan,
                                    contentColor = Color.Black
                                )
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                            }
                        }
                    }
                }
            }

            // Saved Platform Accounts List
            items(accounts, key = { "acc_${it.id}" }) { acc ->
                Surface(
                    color = Color(0xFF091917),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .clickable {
                            onRunCommand("${acc.platformName} এ ${acc.contactOrGroupName} (${acc.handleOrNumberOrUrl}) খোলো")
                            onClose()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "[${acc.platformName}] ${acc.contactOrGroupName}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MatrixGreen
                            )
                            Text(
                                text = acc.handleOrNumberOrUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                        }
                        IconButton(onClick = { onDeleteAccount(acc.id) }) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "মুছুন",
                                tint = Color(0xFF8AA8A4)
                            )
                        }
                    }
                }
            }

            // Add Custom Memory Note
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1F1D)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "কিউব অটো-লার্নিং মেমোরি ভল্ট (${memories.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MatrixGreen
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = memBody,
                                onValueChange = { memBody = it },
                                placeholder = { Text("কিউবকে নতুন তথ্য বা অভ্যাস শেখান...") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (memBody.isNotBlank()) {
                                        onAddMemory(memTitle.ifBlank { "স্যারের নির্দেশনা" }, memBody)
                                        memBody = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MatrixGreen,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text("সেভ")
                            }
                        }
                    }
                }
            }

            items(memories, key = { "mem_${it.id}" }) { mem ->
                Surface(
                    color = Color(0xFF091816),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MatrixGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${mem.title} • ${mem.category}",
                                style = MaterialTheme.typography.labelLarge,
                                color = CyberCyan
                            )
                            Text(
                                text = mem.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                        }
                        IconButton(onClick = { onDeleteMemory(mem.id) }) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "মুছুন",
                                tint = Color(0xFF8AA8A4)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatAndUniversalCrossAppSheet(
    notificationState: NotificationHubState,
    plans: List<AgentPlanResponse>,
    onSendCommand: (String) -> Unit,
    onInstallFromPlayStore: (String) -> Unit,
    onChromeAction: (String) -> Unit,
    onTelegramDownload: (String, String) -> Unit,
    onQuickReplyNotification: (String, String) -> Unit,
    onExecuteStep: (String, AgentStep) -> Unit,
    onClose: () -> Unit,
    onSpeakText: (String) -> Unit = {},
    voiceState: VoiceBridgeState = VoiceBridgeState(),
    onToggleVoiceListening: () -> Unit = {}
) {
    var autonomousCommandText by rememberSaveable { mutableStateOf("") }
    var playStoreQuery by rememberSaveable { mutableStateOf("") }
    var telegramFileQuery by rememberSaveable { mutableStateOf("") }
    var chromeUrlOrFile by rememberSaveable { mutableStateOf("") }
    var inlineReplyText by rememberSaveable { mutableStateOf("জি ভাই, আমি দেখছি—৫ মিনিটের মধ্যে জানাচ্ছি।") }

    JarvisModalContainer(
        title = "CHAT & OWN DECISION • অটোনোমাস কমান্ড ও সব অ্যাপ",
        accentColor = CircuitOrange,
        onClose = onClose
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 0. Autonomous Own-Decision Command Input (Voice + Text)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A2019)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "অটোনোমাস আদেশ (ভয়েস বা টেক্সট)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MatrixGreen
                            )
                            Text(
                                text = if (voiceState.isListening) "● শুনছি (${voiceState.languageLabel})" else voiceState.languageLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (voiceState.isListening) Color(0xFFFF4D6D) else CyberCyan
                            )
                        }
                        if (voiceState.isListening && voiceState.partialTranscript.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "লাইভ ভয়েস: \"${voiceState.partialTranscript}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = autonomousCommandText,
                                onValueChange = { autonomousCommandText = it },
                                placeholder = { Text("মুখে বলুন বা লিখুন—AI নিজে ঠিক করবে...") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("agent_command_input")
                            )
                            IconButton(
                                onClick = onToggleVoiceListening,
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(if (voiceState.isListening) Color(0xFFFF2A54) else Color(0xFF0E2F26))
                                    .border(1.dp, if (voiceState.isListening) Color.White else CyberCyan, CircleShape)
                                    .testTag("chat_sheet_voice_mic_btn")
                            ) {
                                Icon(
                                    imageVector = if (voiceState.isListening) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = "Voice Command",
                                    tint = Color.White
                                )
                            }
                            Button(
                                onClick = {
                                    if (autonomousCommandText.isNotBlank()) {
                                        onSendCommand(autonomousCommandText)
                                        autonomousCommandText = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MatrixGreen,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.testTag("send_command_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            }
                        }
                    }
                }
            }

            // 1. One-Command Play Store & APK Package Auto-Installer
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1E1C)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Shop, contentDescription = null, tint = MatrixGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "APK ও প্লে স্টোর অটো অ্যাপ ইনস্টলার (APK / Play Store)",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = playStoreQuery,
                                onValueChange = { playStoreQuery = it },
                                placeholder = { Text("অ্যাপের নাম বা ডাইরেক্ট .apk লিংক দিন...") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (playStoreQuery.isNotBlank()) {
                                        onInstallFromPlayStore(playStoreQuery)
                                    } else {
                                        onInstallFromPlayStore("apk")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MatrixGreen,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text("APK ইনস্টল")
                            }
                        }
                    }
                }
            }

            // 2. Telegram & Chrome File/Image/Movie Auto-Downloader
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1E1C)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = CyberCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "টেলিগ্রাম ও ক্রোম ফাইল/ছবি অটো-ডাউনলোডার",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = telegramFileQuery,
                                onValueChange = { telegramFileQuery = it },
                                placeholder = { Text("টেলিগ্রাম ফাইল/মুভি/পিডিএফ সার্চ...") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (telegramFileQuery.isNotBlank()) {
                                        onTelegramDownload("@telegram", telegramFileQuery)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberCyan,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text("টেলিগ্রাম DL")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = chromeUrlOrFile,
                                onValueChange = { chromeUrlOrFile = it },
                                placeholder = { Text("ক্রোম সাইট বা ফাইল/ছবি লিংক...") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (chromeUrlOrFile.isNotBlank()) {
                                        onChromeAction(chromeUrlOrFile)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CircuitOrange,
                                    contentColor = Color.Black
                                )
                            ) {
                                Icon(Icons.Default.Public, contentDescription = null)
                            }
                        }
                    }
                }
            }

            // 3. Incoming WhatsApp / Telegram / Messenger Messages & Auto-Reply + Read Aloud
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "হোয়াটসঅ্যাপ, টেলিগ্রাম ও নোটিফিকেশন রিডার:",
                        style = MaterialTheme.typography.titleSmall,
                        color = CircuitOrange,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = { onSendCommand("আমার সব নোটিফিকেশন ও মেসেজ পড়ে শোনাও") },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("মেসেজ পড়ুন", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = inlineReplyText,
                    onValueChange = { inlineReplyText = it },
                    label = { Text("অটো-রিপ্লাই মেসেজ টেক্সট") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            items(notificationState.messages, key = { it.key }) { msg ->
                Surface(
                    color = Color(0xFF0A1918),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CircuitOrange.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "[${msg.appLabel}] ${msg.senderOrTitle}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MatrixGreen
                            )
                            FilledTonalButton(
                                onClick = { onQuickReplyNotification(msg.senderOrTitle, inlineReplyText) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("অটো-রিপ্লাই", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = msg.messageBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                        if (msg.lastRepliedText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "পাঠানো রিপ্লাই: \"${msg.lastRepliedText}\"",
                                style = MaterialTheme.typography.labelMedium,
                                color = CyberCyan
                            )
                        }
                    }
                }
            }

            // Recent AI Action Plans
            item {
                Text(
                    text = "সাম্প্রতিক কিউব টাস্ক ও অ্যাকশন লগ:",
                    style = MaterialTheme.typography.titleSmall,
                    color = CyberCyan
                )
            }

            items(plans.take(6), key = { "plan_${it.id}" }) { plan ->
                Surface(
                    color = Color(0xFF081514),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CyberCyan.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = plan.summaryTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = { onSpeakText(plan.spokenReply) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("plan_speak_btn_${plan.id}")
                            ) {
                                Text("🔊 শুনুন", style = MaterialTheme.typography.labelSmall, color = MatrixGreen)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = plan.spokenReply,
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberCyan
                        )
                        plan.steps.forEach { step ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "• ${step.title} (${step.executionResult.ifBlank { "প্রস্তুত" }})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MatrixGreen,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = { onExecuteStep(plan.id, step) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("চালান", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoulAndMusicWakeSheet(
    sentinelState: CubeSentinelState,
    musicState: MusicPlayerState,
    onToggleSentinel: (Boolean) -> Unit,
    onSimulateHeyCube: () -> Unit,
    onSelectMood: (MusicMood) -> Unit,
    onTogglePlayMusic: () -> Unit,
    onOpenExternalMusic: (String, String) -> Unit,
    onClose: () -> Unit
) {
    var greetingText by rememberSaveable { mutableStateOf(sentinelState.bossGreeting) }

    JarvisModalContainer(
        title = "SOUL • ওয়েক-ওয়ার্ড, পার্সোনালিটি ও মিউজিক সিন্থ",
        accentColor = SoulSilver,
        onClose = onClose
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1E1C)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "\"Hey Cube\" লক-স্ক্রিন ভয়েস গার্ড",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White
                                )
                                Text(
                                    text = if (sentinelState.isSentinelActive) "সক্রিয় • লক স্ক্রিনেও কথা শুনবে" else "সুইচ অন করে সক্রিয় করুন",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MatrixGreen
                                )
                            }
                            Switch(
                                checked = sentinelState.isSentinelActive,
                                onCheckedChange = onToggleSentinel
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = greetingText,
                            onValueChange = {
                                greetingText = it
                                CubeSentinelBus.updateBossGreeting(it)
                            },
                            label = { Text("ডাক দিলে কিউব কি বলবে?") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = onSimulateHeyCube,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberCyan,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("\"Hey Cube\" কল টেস্ট করুন")
                        }
                    }
                }
            }

            // Music Mood Engine inside SOUL
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1E1C)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "কিউব মিউজিক ও স্যাড/রোমান্টিক সং ইঞ্জিন",
                            style = MaterialTheme.typography.titleSmall,
                            color = CyberCyan
                        )
                        Text(
                            text = "বর্তমান ট্র্যাক: ${musicState.currentTrack.title} (${musicState.currentTrack.artist})",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MusicMood.entries.forEach { mood ->
                                FilterChip(
                                    selected = musicState.activeMood == mood && musicState.isPlaying,
                                    onClick = { onSelectMood(mood) },
                                    label = { Text(mood.titleBn) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onTogglePlayMusic,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MatrixGreen,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (musicState.isPlaying) "পজ করুন" else "গান বাজান")
                            }
                            OutlinedButton(
                                onClick = {
                                    onOpenExternalMusic(
                                        musicState.currentTrack.youtubeQuery,
                                        musicState.currentTrack.artist
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, tint = CyberCyan)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("ইউটিউবে শুনুন", color = CyberCyan)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsAndPermissionsSheet(
    accessibilityState: AccessibilityEngineState,
    notificationState: NotificationHubState,
    hardwareState: DeviceHardwareSnapshot,
    geminiApiKey: String,
    onSaveGeminiApiKey: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onToggleFlashlight: (Boolean) -> Unit,
    onVolumeChange: (String, Int) -> Unit,
    onOpenSystemSetting: (String) -> Unit,
    onTestScreenMacro: (String, String) -> Unit,
    onClose: () -> Unit,
    voiceState: VoiceBridgeState = VoiceBridgeState(),
    onToggleTts: () -> Unit = {},
    onTestTtsVoice: () -> Unit = {},
    onChangeSpeechRate: (Float) -> Unit = {},
    onChangeSpeechPitch: (Float) -> Unit = {},
    onSelectVoiceProfile: (String) -> Unit = {},
    onToggleHandsFreeStt: (Boolean) -> Unit = {},
    onSelectSttLanguage: (String) -> Unit = {},
    onTriggerVoiceListen: () -> Unit = {},
    lockedReplyLanguage: String = "AUTO",
    persistentUserRule: String = "",
    onSetLockedLanguage: (String) -> Unit = {},
    onSavePersistentRule: (String) -> Unit = {},
    onToggleQuickSettingTile: (String, Boolean?) -> Unit = { _, _ -> },
    hasContactsAndCallPermission: Boolean = false,
    onRequestContactsAndCallPermission: () -> Unit = {},
    onOpenCodingWorkspace: () -> Unit = {}
) {
    var apiKeyInput by rememberSaveable(geminiApiKey) { mutableStateOf(geminiApiKey) }
    var macroTextToClick by rememberSaveable { mutableStateOf("Install") }
    var customRuleDraft by rememberSaveable(persistentUserRule) { mutableStateOf(persistentUserRule) }

    JarvisModalContainer(
        title = "SETTINGS • ফুল মোবাইল অ্যাক্সেস, ডিপ ভয়েস ও কন্ট্রোল",
        accentColor = MatrixGreen,
        onClose = onClose
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 0. Gemini API Key & Argon 4 Brain Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF09221A)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "GEMINI & ARGON-4 AI KEY",
                                style = MaterialTheme.typography.titleSmall,
                                color = MatrixGreen,
                                fontWeight = FontWeight.Bold
                            )
                            FilledTonalButton(
                                onClick = onOpenCodingWorkspace,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(30.dp)
                                    .testTag("open_argon_coding_from_settings_btn")
                            ) {
                                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Argon 4 কোডিং", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text(
                            text = if (geminiApiKey.isNotBlank()) {
                                "কাস্টম Gemini API Key সক্রিয় • Argon 4 ও ফুল মোবাইল ব্রেন প্রস্তুত"
                            } else {
                                "বিল্ট-ইন Gemini ও Argon 4 সক্রিয় আছে • চাইলে আপনার নিজস্ব API Key বসাতে পারেন"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberCyan
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                placeholder = { Text("AIzaSy... (আপনার Gemini API Key)") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("gemini_api_key_input")
                            )
                            Button(
                                onClick = { onSaveGeminiApiKey(apiKeyInput) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MatrixGreen,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.testTag("save_gemini_key_btn")
                            ) {
                                Text("সেভ")
                            }
                        }
                    }
                }
            }

            // 0A. Persistent Instruction & Language Obedience Lock Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A241E)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("persistent_rule_lock_card")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "স্থায়ী নির্দেশনা ও ভাষা লক (Persistent Obedience)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MatrixGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "আপনি বাংলায় বললেও যদি 'English Only' লক থাকে তবে এজেন্ট শুধু ইংরেজিতেই উত্তর দেবে।",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFC4EAE2)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "AUTO" to "🌐 সব ভাষা (Auto)",
                                "ENGLISH_ONLY" to "🇬🇧 শুধু ইংরেজি (English Only)",
                                "BENGALI_ONLY" to "🇧🇩 শুধু বাংলা (বাংলা Only)",
                                "HINDI_ONLY" to "🇮🇳 শুধু হিন্দি (हिन्दी Only)"
                            ).forEach { (mode, label) ->
                                FilterChip(
                                    selected = lockedReplyLanguage == mode,
                                    onClick = { onSetLockedLanguage(mode) },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("lang_lock_chip_$mode")
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = customRuleDraft,
                                onValueChange = { customRuleDraft = it },
                                placeholder = { Text("স্থায়ী আদেশ (যেমন: সবসময় ইংরেজিতে সংক্ষেপে উত্তর দেবে)...") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("persistent_rule_input")
                            )
                            Button(
                                onClick = { onSavePersistentRule(customRuleDraft) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberCyan,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.testTag("save_persistent_rule_btn")
                            ) {
                                Text("লক করুন")
                            }
                        }
                    }
                }
            }

            // 0B. Deep & Clean TTS Voice Profile + Pitch & Speed Sliders Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A211C)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tts_engine_settings_card")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "DEEP & CLEAN VOICE (TTS ভয়েস প্রোফাইল ও পিচ স্লাইডার)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = CyberCyan,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (voiceState.ttsEnabled) {
                                        "সক্রিয়: ${voiceState.voiceProfile} • Pitch ${String.format("%.2fx", voiceState.pitch)} • Speed ${String.format("%.2fx", voiceState.speechRate)}"
                                    } else {
                                        "মিউট করা আছে • ডিপ ভয়েস শুনতে সুইচ অন করুন"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (voiceState.ttsEnabled) MatrixGreen else CircuitOrange
                                )
                            }
                            Switch(
                                checked = voiceState.ttsEnabled,
                                onCheckedChange = { onToggleTts() },
                                modifier = Modifier.testTag("tts_enable_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "ভয়েস প্রোফাইল নির্বাচন করুন (Deep & Clean Profiles):",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "DEEP_JARVIS" to "🎙️ Deep Jarvis (ডিপ ও ক্লিন)",
                                "CLEAN_STUDIO" to "✨ Clean Studio (স্পষ্ট ক্লিন)",
                                "ULTRA_DEEP" to "🔊 Ultra Deep (ভারী বেস)",
                                "NATURAL" to "🗣️ Natural (স্বাভাবিক)"
                            ).forEach { (profileId, label) ->
                                FilterChip(
                                    selected = voiceState.voiceProfile == profileId,
                                    onClick = { onSelectVoiceProfile(profileId) },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("voice_profile_chip_$profileId")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "কণ্ঠের গভীরতা / পিচ স্লাইডার (Pitch: ${String.format("%.2fx", voiceState.pitch)} — বামে ডিপ, ডানে শার্প)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MatrixGreen
                        )
                        Slider(
                            value = voiceState.pitch.coerceIn(0.55f, 1.35f),
                            onValueChange = { onChangeSpeechPitch(it) },
                            valueRange = 0.55f..1.35f,
                            colors = SliderDefaults.colors(
                                thumbColor = MatrixGreen,
                                activeTrackColor = MatrixGreen,
                                inactiveTrackColor = Color(0xFF193C30)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tts_pitch_slider")
                        )

                        Text(
                            text = "কথার গতি স্লাইডার (Speed: ${String.format("%.2fx", voiceState.speechRate)})",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberCyan
                        )
                        Slider(
                            value = voiceState.speechRate.coerceIn(0.65f, 1.45f),
                            onValueChange = { onChangeSpeechRate(it) },
                            valueRange = 0.65f..1.45f,
                            colors = SliderDefaults.colors(
                                thumbColor = CyberCyan,
                                activeTrackColor = CyberCyan,
                                inactiveTrackColor = Color(0xFF18363B)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tts_rate_slider")
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Button(
                            onClick = onTestTtsVoice,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MatrixGreen,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tts_test_voice_btn")
                        ) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("🔊 ডিপ ও ক্লিন ভয়েস টেস্ট করুন (Test Deep Voice)", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 0C. Speech-to-Text (STT) Hands-Free Voice Recognition Engine Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF09241D)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stt_engine_settings_card")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "SPEECH-TO-TEXT (STT) হ্যান্ডস-ফ্রি ভয়েস রিকগনিশন",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MatrixGreen,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (voiceState.isHandsFreeContinuous) {
                                        "হ্যান্ডস-ফ্রি অটো-লিসেন সক্রিয় • বাংলা, বাংলিশ, ইংরেজি ও হিন্দি সাপোর্ট"
                                    } else {
                                        "ম্যানুয়াল মাইক মোড • অটো-লিসেন চালু করতে সুইচ অন করুন"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (voiceState.isHandsFreeContinuous) CyberCyan else Color(0xFFB4D6D2)
                                )
                            }
                            Switch(
                                checked = voiceState.isHandsFreeContinuous,
                                onCheckedChange = { onToggleHandsFreeStt(it) },
                                modifier = Modifier.testTag("stt_hands_free_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "bn-BD" to "বাংলা (bn-BD)",
                                "en-US" to "English (en-US)",
                                "hi-IN" to "हिन्दी (hi-IN)"
                            ).forEach { (code, label) ->
                                FilterChip(
                                    selected = voiceState.languageCode == code,
                                    onClick = { onSelectSttLanguage(code) },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("stt_lang_chip_$code")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = onTriggerVoiceListen,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (voiceState.isListening) Color(0xFFFF2A54) else CyberCyan,
                                contentColor = if (voiceState.isListening) Color.White else Color.Black
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("stt_test_listen_btn")
                        ) {
                            Icon(
                                imageVector = if (voiceState.isListening) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (voiceState.isListening) "⏹ ভয়েস লিসেনিং চলছে... (থামাতে ট্যাপ করুন)" else "🎤 এখনই মুখে কমান্ড দিন (Start Voice STT)",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // 1. Notification Bar / Quick Settings Auto-Toggle Panel (Wi-Fi, SIM Data, Bluetooth, Airplane Mode, Hotspot, Location, Rotate, DND, Torch)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A221D)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("quick_settings_auto_toggle_card")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "নোটিফিকেশন বার ও কুইক সেটিংস অটো-টগল (অন/অফ)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MatrixGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "ট্যাপ করলে ডাইরেক্ট ব্যাকগ্রাউন্ডে অথবা নোটিফিকেশন বার নামিয়ে অটোমেটিক টগল বাটনে ক্লিক করবে:",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberCyan
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val quickTiles = listOf(
                                Triple("WIFI", "📶 ওয়াইফাই (${if (hardwareState.isWifiEnabled) "ON" else "OFF"})", hardwareState.isWifiEnabled),
                                Triple("MOBILE_DATA", "📱 সিমের নেট (${if (hardwareState.isNetworkConnected) "ON" else "OFF"})", hardwareState.isNetworkConnected),
                                Triple("BLUETOOTH", "🦷 ব্লুটুথ (${if (hardwareState.isBluetoothEnabled) "ON" else "OFF"})", hardwareState.isBluetoothEnabled),
                                Triple("AIRPLANE_MODE", "✈️ এরোপ্লেন মোড (${if (hardwareState.isAirplaneModeOn) "ON" else "OFF"})", hardwareState.isAirplaneModeOn),
                                Triple("FLASHLIGHT", "🔦 টর্চলাইট (${if (hardwareState.isFlashlightOn) "ON" else "OFF"})", hardwareState.isFlashlightOn),
                                Triple("HOTSPOT", "📡 হটস্পট টগল", false),
                                Triple("LOCATION", "📍 লোকেশন (GPS)", false),
                                Triple("AUTO_ROTATE", "🔄 অটো-রোটেট (${if (hardwareState.isAutoRotateOn) "ON" else "OFF"})", hardwareState.isAutoRotateOn),
                                Triple("DND", "🔕 DND সাইলেন্ট (${if (hardwareState.ringerMode == "SILENT") "ON" else "OFF"})", hardwareState.ringerMode == "SILENT"),
                                Triple("DARK_MODE", "🌙 ডার্ক মোড", true),
                                Triple("BATTERY_SAVER", "🔋 ব্যাটারি সেভার", false),
                                Triple("QUICK_SETTINGS", "⬇️ নোটিফিকেশন বার নামান", false)
                            )

                            quickTiles.forEach { (targetKey, label, isOn) ->
                                FilterChip(
                                    selected = isOn,
                                    onClick = {
                                        if (targetKey == "FLASHLIGHT") {
                                            onToggleFlashlight(!hardwareState.isFlashlightOn)
                                        } else {
                                            onToggleQuickSettingTile(targetKey, null)
                                        }
                                    },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("qs_tile_btn_$targetKey")
                                )
                            }
                        }
                    }
                }
            }

            // 2. Full 6-Stream Sound & Volume Controller Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A201D)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("full_audio_streams_card")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = MatrixGreen)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "সব ধরনের সাউন্ড কন্ট্রোল (${hardwareState.ringerMode})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MatrixGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onVolumeChange("ALL", 100) },
                                colors = ButtonDefaults.buttonColors(containerColor = MatrixGreen, contentColor = Color.Black),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🔊 সব ফুল (100%)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { onVolumeChange("ALL", 50) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🔉 সব ৫০%", color = CyberCyan, fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { onVolumeChange("ALL", 0) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🔇 সব মিউট", color = Color(0xFFFF6B8B), fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val streamItems = listOf(
                            Triple("MUSIC", "🎵 মিডিয়া / গান (Media)", hardwareState.musicVolumePercent),
                            Triple("RING", "📞 রিংটোন (Ringtone)", hardwareState.ringVolumePercent),
                            Triple("NOTIFICATION", "🔔 নোটিফিকেশন (Notification)", hardwareState.notificationVolumePercent),
                            Triple("ALARM", "⏰ অ্যালার্ম (Alarm)", hardwareState.alarmVolumePercent),
                            Triple("VOICE_CALL", "🗣️ কল সাউন্ড (Voice Call)", hardwareState.voiceCallVolumePercent),
                            Triple("SYSTEM", "⚙️ সিস্টেম ও টাচ সাউন্ড (System)", hardwareState.systemVolumePercent)
                        )

                        streamItems.forEach { (streamKey, label, currentPct) ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "$label: $currentPct%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "[-]",
                                            color = CyberCyan,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clickable { onVolumeChange(streamKey, (currentPct - 15).coerceAtLeast(0)) }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                        Text(
                                            text = "[+]",
                                            color = MatrixGreen,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clickable { onVolumeChange(streamKey, (currentPct + 15).coerceAtMost(100)) }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Slider(
                                    value = currentPct.toFloat(),
                                    onValueChange = { onVolumeChange(streamKey, it.roundToInt()) },
                                    valueRange = 0f..100f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = MatrixGreen,
                                        activeTrackColor = MatrixGreen,
                                        inactiveTrackColor = Color(0xFF17352C)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(26.dp)
                                        .testTag("volume_slider_$streamKey")
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }

            // 3. Full Mobile Permissions Card (Accessibility Screen Vision/Touch + Notification + Contacts/Call)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1F1D)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "ফুল মোবাইল ও স্ক্রিন পারমিশন কন্ট্রোল:",
                            style = MaterialTheme.typography.titleSmall,
                            color = CyberCyan,
                            fontWeight = FontWeight.Bold
                        )

                        // A. Accessibility Full Screen Vision & Touch
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "১. পুরো স্ক্রিন দেখা, টাচ ও কুইক সেটিংস কন্ট্রোল (Accessibility)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                                Text(
                                    text = if (accessibilityState.isServiceEnabled) {
                                        "সক্রিয় • স্ক্রিন দেখা, নাম খোঁজা ও অটো-টাচ চালু আছে"
                                    } else {
                                        "স্ক্রিন দেখা ও অটো-টাচের জন্য অন করুন"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (accessibilityState.isServiceEnabled) MatrixGreen else CircuitOrange
                                )
                            }
                            Button(
                                onClick = onOpenAccessibilitySettings,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MatrixGreen,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text(if (accessibilityState.isServiceEnabled) "সক্রিয়" else "অন করুন")
                            }
                        }

                        // B. Contacts & Direct Phone Call Permission
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "২. কন্টাক্ট লিস্ট নাম সার্চ ও ডাইরেক্ট কল (Contacts & Call)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                                Text(
                                    text = if (hasContactsAndCallPermission) {
                                        "সক্রিয় • যেকোনো নাম বললে কন্টাক্ট ও স্ক্রিন খুঁজে কাজ করবে"
                                    } else {
                                        "কন্টাক্ট থেকে নাম খুঁজে কল দেওয়ার জন্য পারমিশন দিন"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (hasContactsAndCallPermission) MatrixGreen else CircuitOrange
                                )
                            }
                            Button(
                                onClick = onRequestContactsAndCallPermission,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberCyan,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.testTag("grant_contacts_call_perm_btn")
                            ) {
                                Text(if (hasContactsAndCallPermission) "সক্রিয়" else "অনুমতি দিন")
                            }
                        }

                        // C. Notification Listener
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "৩. হোয়াটসঅ্যাপ ও টেলিগ্রাম মেসেজ রিডার (Notification)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                                Text(
                                    text = if (notificationState.isListenerConnected) {
                                        "সক্রিয় • সব মেসেজ পড়া ও অটো-রিপ্লাই চালু"
                                    } else {
                                        "মেসেজ পড়ার জন্য পারমিশন অন করুন"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (notificationState.isListenerConnected) MatrixGreen else CircuitOrange
                                )
                            }
                            Button(
                                onClick = onOpenNotificationSettings,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberCyan,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text(if (notificationState.isListenerConnected) "সক্রিয়" else "অন করুন")
                            }
                        }
                    }
                }
            }

            // 4. Universal Screen Touch Gesture & Name Scanner Tester
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1F1D)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "স্ক্রিন টাচ জেসচার ও নাম ক্লিক কন্ট্রোলার",
                            style = MaterialTheme.typography.titleSmall,
                            color = CyberCyan
                        )
                        Text(
                            text = "সর্বশেষ স্ট্যাটাস: ${accessibilityState.lastAutomationResult}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MatrixGreen
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = macroTextToClick,
                                onValueChange = { macroTextToClick = it },
                                placeholder = { Text("যেকোনো নাম বা বাটন টেক্সট (যেমন: রাহিম / Install)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { onTestScreenMacro("CLICK", macroTextToClick) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberCyan,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text("খুঁজে ক্লিক")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "TAP_CENTER" to "👆 ট্যাপ সেন্টার",
                                "DOUBLE_TAP" to "👆👆 ডাবল ট্যাপ",
                                "SWIPE_UP" to "⬆️ সোয়াইপ আপ",
                                "SWIPE_DOWN" to "⬇️ সোয়াইপ ডাউন",
                                "SWIPE_LEFT" to "⬅️ সোয়াইপ বামে",
                                "SWIPE_RIGHT" to "➡️ সোয়াইপ ডানে",
                                "BACK" to "🔙 ব্যাক",
                                "HOME" to "🏠 হোম"
                            ).forEach { (cmd, label) ->
                                OutlinedButton(
                                    onClick = { onTestScreenMacro(cmd, "") },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(label, color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodingAndArgonWorkspaceSheet(
    workspaceState: CodingWorkspaceState,
    onSelectModel: (String) -> Unit,
    onToggleAutoRouting: () -> Unit,
    onGenerateCodeWithAi: (String) -> Unit,
    onSaveManualCodeToArcher: (String, String, String, String) -> Unit,
    onShareArtifact: (SavedArcherArtifact) -> Unit,
    onClose: () -> Unit
) {
    var codingPrompt by rememberSaveable { mutableStateOf("") }
    var projectTitle by rememberSaveable(workspaceState.currentProjectTitle) {
        mutableStateOf(workspaceState.currentProjectTitle.ifBlank { "archer_app" })
    }
    var fileName by rememberSaveable(workspaceState.currentFileName) {
        mutableStateOf(workspaceState.currentFileName.ifBlank { "main.py" })
    }
    var language by rememberSaveable(workspaceState.currentLanguage) {
        mutableStateOf(workspaceState.currentLanguage.ifBlank { "Python" })
    }
    var codeEditorText by rememberSaveable(workspaceState.currentCode) {
        mutableStateOf(
            workspaceState.currentCode.ifBlank {
                "# ARCHER AI • Argon 4 Coding Engine\nprint('Hello Sir! Saved inside archer folder.')"
            }
        )
    }

    JarvisModalContainer(
        title = "ARGON 4 & CODING • 'archer' ফোল্ডার আর্কাইভার",
        accentColor = MatrixGreen,
        onClose = onClose
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 1. Model Selector (Argon 4 / Gemini 3.8 Flash / Auto Routing)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF08221B)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "সক্রিয় মডেল: ${workspaceState.activeModelId}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MatrixGreen,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "কাজের ধরন বুঝে নিজে নিজে Argon 4 ও ফাস্ট মডেল ব্যবহার করবে",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CyberCyan
                                )
                            }
                            Switch(
                                checked = workspaceState.isAutoModelRouting,
                                onCheckedChange = { onToggleAutoRouting() },
                                modifier = Modifier.testTag("argon_auto_routing_switch")
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "models/gemini-4-argon" to "⚡ Argon 4 (Pro Coding)",
                                "gemini-3.8-flash" to "🚀 Gemini 3.8 Flash",
                                "gemini-3.5-flash-lite" to "⚡ Gemini 3.5 Lite"
                            ).forEach { (id, label) ->
                                FilterChip(
                                    selected = workspaceState.activeModelId == id ||
                                        (id.contains("argon") && workspaceState.activeModelId.contains("argon")),
                                    onClick = { onSelectModel(id) },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("model_select_chip_$id")
                                )
                            }
                        }
                    }
                }
            }

            // 2. Ask Argon 4 to Write Code & Save to 'archer' Folder
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1F1C)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Argon 4-কে কোডিং প্রজেক্ট বা স্ক্রিপ্ট লিখতে বলুন",
                            style = MaterialTheme.typography.titleSmall,
                            color = CyberCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = codingPrompt,
                                onValueChange = { codingPrompt = it },
                                placeholder = { Text("যেমন: Python ক্যালকুলেটর বা একটি সুন্দর HTML ওয়েবসাইট তৈরি করো...") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("argon_coding_prompt_input")
                            )
                            Button(
                                onClick = {
                                    if (codingPrompt.isNotBlank()) {
                                        onGenerateCodeWithAi("Argon 4 দিয়ে কোড লিখে archer ফোল্ডারে ফাইল, ফোল্ডার ও জিপ আকারে সেভ করো: $codingPrompt")
                                        codingPrompt = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MatrixGreen,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.testTag("argon_generate_code_btn")
                            ) {
                                Text("কোড তৈরি")
                            }
                        }
                    }
                }
            }

            // 3. Live Code Editor & Direct 'archer' Folder Saver (File + Folder + ZIP)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF071915)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "সরাসরি কোড এডিটর ও 'archer' ফোল্ডার সেভার (File + Folder + ZIP)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MatrixGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = projectTitle,
                                onValueChange = { projectTitle = it },
                                label = { Text("প্রজেক্ট ফোল্ডার নাম") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = fileName,
                                onValueChange = { fileName = it },
                                label = { Text("ফাইলের নাম (main.py / index.html)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = codeEditorText,
                            onValueChange = { codeEditorText = it },
                            label = { Text("কোড কন্টেন্ট ($language)") },
                            minLines = 5,
                            maxLines = 9,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("argon_code_editor_field")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                onSaveManualCodeToArcher(projectTitle, fileName, language, codeEditorText)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberCyan,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("save_to_archer_folder_btn")
                        ) {
                            Icon(Icons.Default.FolderZip, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "💾 মেইন 'archer' ফোল্ডারে ফাইল, ফোল্ডার ও .ZIP আকারে সেভ করুন",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // 4. Saved Projects in 'archer' Folder (Documents/archer & Download/archer)
            item {
                Text(
                    text = "📂 মেইন ফোল্ডার 'archer'-এ সংরক্ষিত কোড ও জিপ ফাইল (${workspaceState.savedArtifacts.size}):",
                    style = MaterialTheme.typography.titleSmall,
                    color = MatrixGreen,
                    fontWeight = FontWeight.Bold
                )
            }

            items(workspaceState.savedArtifacts, key = { it.id }) { artifact ->
                Surface(
                    color = Color(0xFF081C18),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MatrixGreen.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📦 ${artifact.projectTitle} (${artifact.primaryFileName})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MatrixGreen,
                                fontWeight = FontWeight.Bold
                            )
                            FilledTonalButton(
                                onClick = { onShareArtifact(artifact) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("ZIP শেয়ার", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ভাষা: ${artifact.language} • ফোল্ডার: Documents/archer ও Download/archer",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberCyan
                        )
                        Text(
                            text = "• ফাইল: ${artifact.singleFilePath}\n• প্রজেক্ট ফোল্ডার: ${artifact.folderPath}\n• জিপ আর্কাইভ: ${artifact.zipFilePath}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFFD6F5EC)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LiveCameraAndScreenVisionSheet(
    isFrontCamera: Boolean,
    visibleScreenText: String,
    onFlipCamera: () -> Unit,
    onLaunchSystemCamera: () -> Unit,
    onAnalyzeScreenWithAi: () -> Unit,
    onSendVisionPrompt: (String) -> Unit,
    onClose: () -> Unit
) {
    var visionQuestion by rememberSaveable { mutableStateOf("স্ক্রিনে বা ক্যামেরায় কি দেখা যাচ্ছে বিশ্লেষণ করে বলো") }

    JarvisModalContainer(
        title = "LIVE CAMERA & SCREEN VISION • ভিশন এআই",
        accentColor = MatrixGreen,
        onClose = onClose
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF061512))
                    .border(1.5.dp, MatrixGreen, RoundedCornerShape(18.dp))
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = MatrixGreen,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isFrontCamera) "FRONT LENS • ফ্রন্ট ক্যামেরা ও স্ক্রিন ভিশন প্রস্তুত" else "REAR LENS • ব্যাক ক্যামেরা ও স্ক্রিন ভিশন প্রস্তুত",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Text(
                        text = "স্ক্রিন রিডার টেক্সট: ${visibleScreenText.take(90)}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberCyan
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        com.example.service.ScreenCaptureSessionBus.requestOneTimeScreenCapturePermission()
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MatrixGreen,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.weight(1.1f)
                ) {
                    Icon(Icons.Default.ScreenShare, contentDescription = null)
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("স্ক্রিন শেয়ার")
                }

                Button(
                    onClick = onLaunchSystemCamera,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberCyan,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("ক্যামেরা")
                }

                OutlinedButton(
                    onClick = onFlipCamera,
                    modifier = Modifier.weight(0.95f)
                ) {
                    Icon(Icons.Default.FlipCameraAndroid, contentDescription = null, tint = CyberCyan)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isFrontCamera) "ব্যাক" else "ফ্রন্ট", color = CyberCyan)
                }
            }

            OutlinedTextField(
                value = visionQuestion,
                onValueChange = { visionQuestion = it },
                label = { Text("ভিশন বা স্ক্রিন সম্পর্কে কিউবকে প্রশ্ন করুন") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    onSendVisionPrompt("$visionQuestion (বর্তমান স্ক্রিন তথ্য: $visibleScreenText)")
                    onClose()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberCyan,
                    contentColor = Color.Black
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("এআই দিয়ে স্ক্রিন ও ভিশন বিশ্লেষণ করুন")
            }
        }
    }
}

@Composable
fun HeadlineDetailSheet(
    headline: HeadlineItem,
    onOpenInChrome: (String) -> Unit,
    onAskAiAboutHeadline: (String) -> Unit,
    onClose: () -> Unit
) {
    JarvisModalContainer(
        title = "TODAY HEADLINE • নিউজ ও সাইট ইন্টেলিজেন্স",
        accentColor = CyberCyan,
        onClose = onClose
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = headline.title,
                style = MaterialTheme.typography.titleMedium,
                color = MatrixGreen,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = headline.summaryBn,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        onOpenInChrome(headline.sourceUrl)
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberCyan,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("ক্রোমে সাইট খুলুন")
                }
                OutlinedButton(
                    onClick = {
                        onAskAiAboutHeadline("এই বিষয়টি বিস্তারিত বুঝিয়ে বলো এবং মেমোরিতে রাখো: ${headline.title}")
                        onClose()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("বিস্তারিত শুনুন", color = MatrixGreen)
                }
            }
        }
    }
}

@Composable
private fun JarvisModalContainer(
    title: String,
    accentColor: Color,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD9030A08))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF061311)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, accentColor, RoundedCornerShape(24.dp))
                .testTag("jarvis_active_modal")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "বন্ধ করুন",
                            tint = Color.White
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                content()
            }
        }
    }
}
