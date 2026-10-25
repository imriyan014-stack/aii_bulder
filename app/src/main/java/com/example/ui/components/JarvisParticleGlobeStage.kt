package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.JarvisNodeModal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

val MatrixGreen = Color(0xFF00FF66)
val CyberCyan = Color(0xFF00F0FF)
val CircuitOrange = Color(0xFFFF9100)
val SoulSilver = Color(0xFFE8F5E9)
val ParticleGold = Color(0xFFFFEA00)
val StopCrimson = Color(0xFFFF2E54)

private data class FibonacciSpherePoint(
    val baseX: Float,
    val baseY: Float,
    val baseZ: Float,
    val theta: Float,
    val phiIndexRatio: Float
)

@Composable
fun JarvisCircuitAndGlobeStage(
    isSpeaking: Boolean,
    isListening: Boolean,
    isThinking: Boolean,
    ttsEnabled: Boolean,
    onOpenNodeModal: (JarvisNodeModal) -> Unit,
    onGlobeTap: () -> Unit,
    onStopClick: () -> Unit,
    onToggleTts: () -> Unit,
    modifier: Modifier = Modifier,
    rmsLevel: Float = 0f
) {
    val isActiveVoice = isSpeaking || isListening || isThinking || rmsLevel > 0.08f
    val infiniteTransition = rememberInfiniteTransition(label = "archer_globe_transition")

    // Precompute 540 Fibonacci 3D Sphere points for uniform, seamless holographic globe geometry
    val fibonacciPoints = remember {
        val count = 540
        val goldenAngle = (PI * (3.0 - sqrt(5.0))).toFloat()
        List(count) { i ->
            val y = 1f - (i.toFloat() / (count - 1).toFloat()) * 2f
            val radiusAtY = sqrt((1f - y * y).coerceAtLeast(0f))
            val theta = goldenAngle * i
            val x = cos(theta) * radiusAtY
            val z = sin(theta) * radiusAtY
            FibonacciSpherePoint(
                baseX = x,
                baseY = y,
                baseZ = z,
                theta = theta,
                phiIndexRatio = i.toFloat() / count.toFloat()
            )
        }
    }

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActiveVoice) 5600 else 11500,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "globe_rotation"
    )

    val harmonicWavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActiveVoice) 1100 else 2800,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "harmonic_wave_phase"
    )

    val secondaryOrbitAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "secondary_orbit"
    )

    val wavePulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = if (isActiveVoice) 1.09f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActiveVoice) 420 else 1600,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "globe_pulse"
    )

    val circuitPulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "circuit_pulse"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(258.dp)
        ) {
            // Glowing Circuit Traces from 4 Left Pill Buttons into the 3D Holographic Globe
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pillRightEdgeX = 128.dp.toPx()
                val globeLeftEntryX = size.width * 0.49f
                val midTurnXOuter = pillRightEdgeX + (globeLeftEntryX - pillRightEdgeX) * 0.42f
                val midTurnXInner = pillRightEdgeX + (globeLeftEntryX - pillRightEdgeX) * 0.28f
                val centerGlobeY = size.height * 0.50f

                val nodeYs = listOf(
                    size.height * 0.13f,
                    size.height * 0.38f,
                    size.height * 0.62f,
                    size.height * 0.87f
                )
                val traceColors = listOf(CyberCyan, CircuitOrange, SoulSilver, MatrixGreen)
                val entryYOffsets = listOf(-12.dp.toPx(), -4.dp.toPx(), 4.dp.toPx(), 12.dp.toPx())

                for (i in 0..3) {
                    val yStart = nodeYs[i]
                    val yEnd = centerGlobeY + entryYOffsets[i]
                    val bendX1 = if (i == 0 || i == 3) midTurnXOuter else midTurnXInner
                    val bendX2 = bendX1 + 14.dp.toPx()
                    val color = traceColors[i]

                    val path = Path().apply {
                        moveTo(pillRightEdgeX, yStart)
                        lineTo(bendX1, yStart)
                        lineTo(bendX2, yEnd)
                        lineTo(globeLeftEntryX, yEnd)
                    }

                    // Wide outer neon bloom
                    drawPath(
                        path = path,
                        color = color.copy(alpha = 0.24f),
                        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    // Medium neon halo
                    drawPath(
                        path = path,
                        color = color.copy(alpha = 0.55f),
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    // Crisp bright core line
                    drawPath(
                        path = path,
                        color = Color.White.copy(alpha = 0.9f),
                        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Glowing connector node dot right on the pill's right border
                    drawCircle(
                        color = color.copy(alpha = 0.45f),
                        radius = 6.dp.toPx(),
                        center = Offset(pillRightEdgeX, yStart)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3.dp.toPx(),
                        center = Offset(pillRightEdgeX, yStart)
                    )

                    // Animated energy packet flowing along the circuit trace into the globe
                    val t = (circuitPulse + i * 0.22f) % 1f
                    val packetPos = when {
                        t < 0.35f -> {
                            val localT = t / 0.35f
                            Offset(pillRightEdgeX + (bendX1 - pillRightEdgeX) * localT, yStart)
                        }
                        t < 0.70f -> {
                            val localT = (t - 0.35f) / 0.35f
                            Offset(
                                bendX1 + (bendX2 - bendX1) * localT,
                                yStart + (yEnd - yStart) * localT
                            )
                        }
                        else -> {
                            val localT = (t - 0.70f) / 0.30f
                            Offset(bendX2 + (globeLeftEntryX - bendX2) * localT, yEnd)
                        }
                    }
                    drawCircle(
                        color = color,
                        radius = 4.5.dp.toPx(),
                        center = packetPos
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Column: 4 Pill Node Buttons (MEMORY, CHAT, SOUL, SETTINGS)
                Column(
                    modifier = Modifier
                        .width(128.dp)
                        .fillMaxHeight()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    CircuitNodePillButton(
                        label = "MEMORY",
                        icon = Icons.Default.Memory,
                        accentColor = CyberCyan,
                        onClick = { onOpenNodeModal(JarvisNodeModal.MEMORY_AND_IDS) },
                        testTag = "node_btn_memory"
                    )
                    CircuitNodePillButton(
                        label = "CHAT",
                        icon = Icons.AutoMirrored.Filled.Chat,
                        accentColor = CircuitOrange,
                        onClick = { onOpenNodeModal(JarvisNodeModal.CHAT_AND_MESSAGES) },
                        testTag = "node_btn_chat"
                    )
                    CircuitNodePillButton(
                        label = "SOUL",
                        icon = Icons.Default.Person,
                        accentColor = SoulSilver,
                        onClick = { onOpenNodeModal(JarvisNodeModal.SOUL_AND_WAKE) },
                        testTag = "node_btn_soul"
                    )
                    CircuitNodePillButton(
                        label = "SETTINGS",
                        icon = Icons.Default.Settings,
                        accentColor = MatrixGreen,
                        onClick = { onOpenNodeModal(JarvisNodeModal.SETTINGS_AND_PERMS) },
                        testTag = "node_btn_settings"
                    )
                }

                Spacer(modifier = Modifier.width(24.dp))

                // Right Stage: Upgraded 3D Fibonacci Holographic Particle Globe & Voice Wave Rings
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onGlobeTap() }
                        .testTag("jarvis_particle_globe")
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width * 0.52f, size.height * 0.49f)
                        val baseRadius = size.minDimension * 0.455f
                        val rmsBoost = 1f + rmsLevel * 0.14f
                        val sphereRadius = baseRadius * (wavePulse * rmsBoost).coerceIn(0.92f, 1.12f)

                        // 1. Deep multi-layered ambient holographic outer emerald-cyan aura
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    MatrixGreen.copy(alpha = 0.36f),
                                    CyberCyan.copy(alpha = 0.24f),
                                    Color(0xFF063820).copy(alpha = 0.22f),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = baseRadius * 1.34f
                            ),
                            radius = baseRadius * 1.34f,
                            center = center
                        )

                        // 2. Outer HUD Containment Ring (Emerald + Cyan)
                        drawCircle(
                            color = MatrixGreen.copy(alpha = 0.68f),
                            radius = baseRadius * 1.07f,
                            center = center,
                            style = Stroke(width = 1.6.dp.toPx())
                        )
                        drawCircle(
                            color = CyberCyan.copy(alpha = 0.35f),
                            radius = baseRadius * 1.11f,
                            center = center,
                            style = Stroke(width = 0.9.dp.toPx())
                        )

                        // 3. Intense Volumetric Emerald-Cyan Plasma Energy Core inside the 3D sphere
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFE0FFFF).copy(alpha = if (isActiveVoice) 0.98f else 0.90f),
                                    CyberCyan.copy(alpha = if (isActiveVoice) 0.78f else 0.65f),
                                    MatrixGreen.copy(alpha = 0.46f),
                                    Color(0xFF043822).copy(alpha = 0.28f),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = sphereRadius * 0.92f
                            ),
                            radius = sphereRadius * 0.92f,
                            center = center
                        )

                        // 4. Inner Holographic Wireframe Meridian Rings for 3D spherical structure
                        rotate(degrees = -14f, pivot = center) {
                            drawOval(
                                color = CyberCyan.copy(alpha = 0.42f),
                                topLeft = Offset(center.x - sphereRadius * 1.04f, center.y - sphereRadius * 0.32f),
                                size = Size(sphereRadius * 2.08f, sphereRadius * 0.64f),
                                style = Stroke(width = 1.3.dp.toPx())
                            )
                            drawOval(
                                color = MatrixGreen.copy(alpha = 0.26f),
                                topLeft = Offset(center.x - sphereRadius * 0.82f, center.y - sphereRadius * 0.56f),
                                size = Size(sphereRadius * 1.64f, sphereRadius * 1.12f),
                                style = Stroke(width = 0.9.dp.toPx())
                            )
                        }
                        rotate(degrees = 26f + secondaryOrbitAngle * 0.08f, pivot = center) {
                            drawOval(
                                color = CyberCyan.copy(alpha = 0.30f),
                                topLeft = Offset(center.x - sphereRadius * 0.36f, center.y - sphereRadius * 1.03f),
                                size = Size(sphereRadius * 0.72f, sphereRadius * 2.06f),
                                style = Stroke(width = 1.1.dp.toPx())
                            )
                        }

                        // 5. Dual Counter-Rotating 3D Orbital Particle Rings (Voice Wave Rings)
                        val ringCount = 36
                        val ringTiltRad1 = Math.toRadians(-16.0).toFloat()
                        val ringTiltRad2 = Math.toRadians(68.0).toFloat()
                        val cosTilt1 = cos(ringTiltRad1)
                        val sinTilt1 = sin(ringTiltRad1)
                        val cosTilt2 = cos(ringTiltRad2)
                        val sinTilt2 = sin(ringTiltRad2)

                        for (k in 0 until ringCount) {
                            val angle1 = (2.0 * PI * k / ringCount).toFloat() + rotationAngle * 1.35f
                            val rx1 = cos(angle1) * (sphereRadius * 1.06f)
                            val rz1 = sin(angle1) * (sphereRadius * 1.06f)
                            val ry1 = rz1 * sinTilt1
                            val depth1 = ((rz1 * cosTilt1 / sphereRadius) + 1f) * 0.5f
                            drawCircle(
                                color = CyberCyan.copy(alpha = (0.28f + depth1 * 0.65f).coerceIn(0.2f, 0.95f)),
                                radius = (1.1f + depth1 * 1.5f).dp.toPx(),
                                center = Offset(center.x + rx1, center.y + ry1)
                            )

                            val angle2 = (2.0 * PI * k / ringCount).toFloat() - rotationAngle * 1.1f
                            val rx2 = cos(angle2) * (sphereRadius * 1.03f)
                            val ry2 = sin(angle2) * (sphereRadius * 1.03f) * cosTilt2
                            val rz2 = sin(angle2) * sinTilt2
                            val depth2 = ((rz2 + 1f) * 0.5f).coerceIn(0.2f, 0.95f)
                            if (k % 2 == 0) {
                                drawCircle(
                                    color = if (k % 4 == 0) ParticleGold.copy(alpha = depth2 * 0.85f)
                                    else MatrixGreen.copy(alpha = depth2 * 0.8f),
                                    radius = (1.0f + depth2 * 1.4f).dp.toPx(),
                                    center = Offset(center.x + rx2 * 0.42f, center.y + ry2)
                                )
                            }
                        }

                        // 6. 540-Point 3D Fibonacci Sphere with Perspective Depth & Harmonic Voice Waves
                        val cosRotY = cos(rotationAngle)
                        val sinRotY = sin(rotationAngle)
                        val tiltX = 0.22f // Subtle 3D axial tilt for realistic globe perspective
                        val cosTiltX = cos(tiltX)
                        val sinTiltX = sin(tiltX)
                        val waveAmplitude = when {
                            isSpeaking || isListening -> 0.085f + rmsLevel * 0.08f
                            isThinking -> 0.065f
                            else -> 0.018f
                        }

                        for (idx in fibonacciPoints.indices) {
                            val pt = fibonacciPoints[idx]

                            // Rotate around Y axis
                            val xRotY = pt.baseX * cosRotY + pt.baseZ * sinRotY
                            val zRotY = -pt.baseX * sinRotY + pt.baseZ * cosRotY

                            // Tilt around X axis for true 3D globe feel
                            val y3d = pt.baseY * cosTiltX - zRotY * sinTiltX
                            val z3d = pt.baseY * sinTiltX + zRotY * cosTiltX
                            val x3d = xRotY

                            // 3D spherical harmonic displacement wave
                            val harmonicWave = 1f + waveAmplitude *
                                sin(pt.theta * 4f + harmonicWavePhase) *
                                cos(pt.phiIndexRatio * 12f - harmonicWavePhase)

                            // Perspective projection (front of sphere looks closer & larger)
                            val perspective = 2.6f / (2.6f - z3d * 0.55f)
                            val effectiveRadius = sphereRadius * harmonicWave * perspective * 0.92f

                            val projX = center.x + x3d * effectiveRadius
                            val projY = center.y + y3d * effectiveRadius

                            val depthNormalized = ((z3d + 1f) * 0.5f).coerceIn(0.14f, 1f)
                            val dotRadius = (1.05f + depthNormalized * 2.25f).dp.toPx()

                            // Signature Archer AI color distribution:
                            // Golden-yellow neural cluster on left hemisphere, emerald transition, cyber-cyan right hemisphere
                            val particleColor = when {
                                x3d < -0.20f && y3d > -0.65f && y3d < 0.68f -> {
                                    ParticleGold.copy(alpha = (0.28f + depthNormalized * 0.72f).coerceIn(0.2f, 1f))
                                }
                                x3d < -0.04f && idx % 2 == 0 -> {
                                    MatrixGreen.copy(alpha = (0.25f + depthNormalized * 0.70f).coerceIn(0.2f, 0.96f))
                                }
                                depthNormalized > 0.86f && idx % 7 == 0 -> {
                                    Color(0xFFE8FFFF).copy(alpha = 0.98f)
                                }
                                else -> {
                                    CyberCyan.copy(alpha = (0.24f + depthNormalized * 0.74f).coerceIn(0.18f, 0.98f))
                                }
                            }

                            // Subtle glow halo for front-most bright particles
                            if (depthNormalized > 0.82f && idx % 4 == 0) {
                                drawCircle(
                                    color = particleColor.copy(alpha = 0.26f),
                                    radius = dotRadius * 2.1f,
                                    center = Offset(projX, projY)
                                )
                            }

                            drawCircle(
                                color = particleColor,
                                radius = dotRadius,
                                center = Offset(projX, projY)
                            )
                        }
                    }

                    // Bottom Mic Icon + SPEAKING/LISTENING status label inside the globe circle
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color(0xFFB9F6CA),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = when {
                                isSpeaking -> "SPEAKING"
                                isListening -> "LISTENING"
                                isThinking -> "CONNECTING"
                                else -> "TAP GLOBE"
                            },
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 10.sp,
                            letterSpacing = 1.8.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFD8FFF0)
                        )
                    }
                }
            }
        }

        // STOP + Speaker Row aligned under the 3D Globe on the right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 26.dp, top = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color(0xFF14070C),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .height(36.dp)
                    .border(1.4.dp, StopCrimson, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .clickable { onStopClick() }
                    .testTag("jarvis_stop_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = StopCrimson,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "STOP",
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (ttsEnabled) Color(0xFF0097A7) else Color(0xFF102428))
                    .border(1.dp, CyberCyan.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                    .clickable { onToggleTts() }
                    .testTag("jarvis_speaker_toggle")
            ) {
                Icon(
                    imageVector = if (ttsEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = "ভয়েস স্পিকার",
                    tint = Color(0xFF03181C),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun CircuitNodePillButton(
    label: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        color = Color(0xFF06120E),
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .border(1.5.dp, accentColor, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.25f))
                    .border(1.2.dp, accentColor, CircleShape)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accentColor,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 11.sp,
                    letterSpacing = 0.7.sp,
                    fontWeight = FontWeight.ExtraBold
                ),
                color = Color.White,
                maxLines = 1
            )
        }
    }
}
