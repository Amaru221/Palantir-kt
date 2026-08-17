package com.amaru.palantir

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.random.Random

// Enum de estados que utiliza la interfaz
enum class PalantirState {
    IDLE,       // Reposo / Esperando
    LISTENING,  // Escuchando / Grabando
    THINKING,   // Procesando con Gemini
    SPEAKING    // Hablando respuesta
}

enum class UiStyle { EYES, SCI_FI, AUDIO_REACTIVE }

@Composable
fun InterfaceSelector(
    style: UiStyle,
    state: PalantirState,
    amplitude: Int
) {
    Box(
        modifier = Modifier.size(130.dp),
        contentAlignment = Alignment.Center
    ) {
        when (style) {
            UiStyle.EYES -> AnimatedEyesUi(state = state, amplitude = amplitude)
            UiStyle.SCI_FI -> SciFiOrbUi(state = state)
            UiStyle.AUDIO_REACTIVE -> AudioReactiveOrbUi(state = state, amplitude = amplitude)
        }
    }
}

// -------------------------------------------------------------
// 1. CARA DE ROBOT / OJOS ANIMADOS CON EXPRESIONES
// -------------------------------------------------------------
@Composable
fun AnimatedEyesUi(state: PalantirState, amplitude: Int) {
    var isBlinking by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        while (state == PalantirState.IDLE) {
            kotlinx.coroutines.delay(Random.nextLong(2000, 4500))
            isBlinking = true
            kotlinx.coroutines.delay(120)
            isBlinking = false
        }
    }

    val thinkingTransition = rememberInfiniteTransition(label = "thinkingOffset")
    val thinkingOffsetY by thinkingTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offsetY"
    )

    val eyeHeight = when {
        isBlinking -> 3.dp
        state == PalantirState.IDLE -> 14.dp
        state == PalantirState.LISTENING -> 34.dp
        state == PalantirState.THINKING -> 18.dp
        state == PalantirState.SPEAKING -> (24 + (amplitude / 800).coerceIn(0, 16)).dp
        else -> 28.dp
    }

    val eyeWidth = if (state == PalantirState.LISTENING) 26.dp else 22.dp

    val eyeColor = when (state) {
        PalantirState.IDLE -> Color(0xFF64B5F6)
        PalantirState.LISTENING -> Color(0xFFFF5252)
        PalantirState.THINKING -> Color(0xFFFFB74D)
        PalantirState.SPEAKING -> Color(0xFF81C784)
    }

    val currentOffsetY = if (state == PalantirState.THINKING) thinkingOffsetY.dp else 0.dp

    Row(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.offset(y = currentOffsetY)
    ) {
        Box(
            modifier = Modifier
                .width(eyeWidth)
                .height(eyeHeight)
                .clip(CircleShape)
                .background(eyeColor)
        )
        Box(
            modifier = Modifier
                .width(eyeWidth)
                .height(eyeHeight)
                .clip(CircleShape)
                .background(eyeColor)
        )
    }
}

// -------------------------------------------------------------
// 2. ORBE SCI-FI / JARVIS CON ANILLOS DINÁMICOS
// -------------------------------------------------------------
@Composable
fun SciFiOrbUi(state: PalantirState) {
    val duration = when (state) {
        PalantirState.IDLE -> 8000
        PalantirState.LISTENING -> 3000
        PalantirState.THINKING -> 800
        PalantirState.SPEAKING -> 2000
    }

    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "angle"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val color = when (state) {
        PalantirState.IDLE -> Color(0xFF00E5FF)
        PalantirState.LISTENING -> Color(0xFFFF1744)
        PalantirState.THINKING -> Color(0xFFFF9100)
        PalantirState.SPEAKING -> Color(0xFF00E676)
    }

    Canvas(
        modifier = Modifier
            .size(110.dp)
            .scale(if (state == PalantirState.IDLE) pulseScale else 1.0f)
    ) {
        val center = Offset(size.width / 2, size.height / 2)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color, color.copy(alpha = 0.25f), Color.Transparent),
                center = center,
                radius = size.width / 2
            )
        )

        val sweepAngle1 = if (state == PalantirState.THINKING) 240f else 120f
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = sweepAngle1,
            useCenter = false,
            style = Stroke(width = 4.dp.toPx())
        )

        drawArc(
            color = color.copy(alpha = 0.8f),
            startAngle = -angle * 1.5f,
            sweepAngle = 160f,
            useCenter = false,
            style = Stroke(width = 2.5.dp.toPx()),
            size = Size(size.width * 0.7f, size.height * 0.7f),
            topLeft = Offset(size.width * 0.15f, size.height * 0.15f)
        )
    }
}

// -------------------------------------------------------------
// 3. ORBE ORGANICO REACTIVO Y VIBRANTE
// -------------------------------------------------------------
@Composable
fun AudioReactiveOrbUi(state: PalantirState, amplitude: Int) {
    val idleTransition = rememberInfiniteTransition(label = "idleBreathe")
    val idleScale by idleTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 0.96f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    val voiceScale = 0.9f + (amplitude / 7000f).coerceIn(0f, 0.55f)

    val currentScale = when (state) {
        PalantirState.IDLE -> idleScale
        PalantirState.THINKING -> 1.0f
        else -> voiceScale
    }

    val animatedScale by animateFloatAsState(
        targetValue = currentScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    val color = when (state) {
        PalantirState.IDLE -> Color(0xFF29B6F6)
        PalantirState.LISTENING -> Color(0xFFEF5350)
        PalantirState.THINKING -> Color(0xFFFFA726)
        PalantirState.SPEAKING -> Color(0xFF66BB6A)
    }

    Box(
        modifier = Modifier
            .size(95.dp)
            .scale(animatedScale)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        color,
                        color.copy(alpha = 0.6f),
                        color.copy(alpha = 0.1f)
                    )
                )
            )
    )
}