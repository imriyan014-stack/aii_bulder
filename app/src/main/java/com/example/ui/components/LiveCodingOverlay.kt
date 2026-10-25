package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.CodingSessionBus
import kotlinx.coroutines.delay

/**
 * Full-screen Argon 4 page: code is typed live, then the "saved to archer" banner appears.
 */
@Composable
fun LiveCodingOverlay() {
    val session by CodingSessionBus.session.collectAsState()
    val s = session ?: return

    val green = Color(0xFF00FF9C)
    val total = s.code.length
    var shown by remember(s.id) { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val typingDone = shown >= total

    LaunchedEffect(s.id) {
        val step = (total / 350).coerceAtLeast(4)   // whole file types in ~6 seconds at most
        var frame = 0
        while (shown < total) {
            shown = (shown + step).coerceAtMost(total)
            frame++
            if (frame % 4 == 0) scroll.scrollTo(scroll.maxValue)
            delay(16)
        }
        scroll.scrollTo(scroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF030A07))
            .safeDrawingPadding()
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ARGON 4 • লাইভ কোডিং", color = green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(s.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Text(
                "✕", color = Color(0xFFFF4D6A), fontSize = 22.sp,
                modifier = Modifier.clickable { CodingSessionBus.dismiss() }.padding(8.dp)
            )
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(s.fileName, green)
            if (s.language.isNotBlank()) Chip(s.language, Color(0xFF00E5FF))
            Chip("${s.code.take(shown).count { it == '\n' } + 1} লাইন", Color(0xFF8FA89B))
        }
        LinearProgressIndicator(
            progress = { if (total == 0) 1f else shown.toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = green,
            trackColor = Color(0xFF12281D)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, green.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .background(Color(0xFF06120C))
        ) {
            Text(
                text = s.code.take(shown) + if (!typingDone) "▌" else "",
                color = Color(0xFFB8FFE0),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.verticalScroll(scroll).padding(10.dp)
            )
        }
        if (typingDone) {
            Column(
                Modifier.fillMaxWidth().padding(top = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(green.copy(alpha = 0.12f))
                    .border(1.dp, green, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    if (s.isSaved) "✔ 'archer' ফোল্ডারে সেভ হয়েছে" else "সেভ হচ্ছে…",
                    color = green, fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
                if (s.isSaved && s.savedPath.isNotBlank()) {
                    Text(s.savedPath, color = Color(0xFFB8FFE0), fontSize = 11.sp)
                }
                Text(
                    "বন্ধ করুন", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp).clickable { CodingSessionBus.dismiss() }
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 11.sp, maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
