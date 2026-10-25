package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.example.service.TouchEffectBus
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.MultimodalLiveSessionState

/**
 * One slim row replacing the old full-screen Live HUD (which filled the screen with telemetry text).
 * [dot] status | one-line status | mic | screen-share | settings | LIVE/STOP
 */
@Composable
fun LiveCompactBar(
    liveState: MultimodalLiveSessionState,
    onToggleLive: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleScreen: () -> Unit,
    onOpenHub: () -> Unit,
    modifier: Modifier = Modifier
) {
    val live = liveState.isLiveActive
    val green = Color(0xFF00FF9C)
    val amber = Color(0xFFFFB300)
    val red = Color(0xFFFF4D6A)
    val grey = Color(0xFF5C7468)
    val shape = RoundedCornerShape(14.dp)

    val connected = live && liveState.isWebSocketConnected
    val ctx = LocalContext.current
    val touchFx by TouchEffectBus.enabled.collectAsState()
    val dotColor = when {
        !live -> grey
        connected -> green
        else -> amber
    }
    val statusText = when {
        !live -> "GEMINI LIVE • কথা বলতে চাপুন"
        !connected -> liveState.statusMessageBn.take(70).ifBlank { "সংযোগ হচ্ছে…" }
        liveState.isAgentSpeakingLive -> "বলছে… (মাঝে বললেই থেমে যাবে)"
        else -> "শুনছি…"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .border(1.dp, dotColor.copy(alpha = 0.7f), shape)
            .background(Color(0xFF07140E))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(8.dp))
        Text(
            text = statusText,
            color = Color(0xFFB8FFE0),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (live) {
            Icon(
                imageVector = if (liveState.isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = "mic",
                tint = if (liveState.isMicMuted) red else green,
                modifier = Modifier.padding(horizontal = 6.dp).size(22.dp).clickable { onToggleMic() }
            )
            Icon(
                imageVector = if (liveState.isScreenStreamEnabled) Icons.Default.ScreenShare else Icons.Default.StopScreenShare,
                contentDescription = "screen",
                tint = if (liveState.isScreenStreamEnabled) green else grey,
                modifier = Modifier.padding(horizontal = 6.dp).size(22.dp).clickable { onToggleScreen() }
            )
        }

        Icon(
            imageVector = Icons.Default.TouchApp,
            contentDescription = "touch effect",
            tint = if (touchFx) green else grey,
            modifier = Modifier.padding(horizontal = 6.dp).size(22.dp).clickable { TouchEffectBus.set(ctx, !touchFx) }
        )
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = "settings",
            tint = grey,
            modifier = Modifier.padding(horizontal = 6.dp).size(20.dp).clickable { onOpenHub() }
        )

        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (live) red.copy(alpha = 0.18f) else green.copy(alpha = 0.18f))
                .border(1.dp, if (live) red else green, RoundedCornerShape(10.dp))
                .clickable { onToggleLive() }
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(
                text = if (live) "STOP" else "LIVE",
                color = if (live) red else green,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
