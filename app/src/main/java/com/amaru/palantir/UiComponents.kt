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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Enum de estados
enum class PalantirState {
    WAITING_WAKE_WORD, // Escuchando "Oye Palantir"
    LISTENING,          // Grabando consulta tras wake word
    THINKING,           // Procesando con Gemini
    SPEAKING            // Hablando respuesta
}

// Enum de estilos actualizado con los nuevos diseños
enum class UiStyle {
    EYES,
    SCI_FI,
    AUDIO_REACTIVE,
    MYSTIC,
    HALO,
    RADIAL
}

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
            UiStyle.MYSTIC -> MysticOrbUi(state = state, amplitude = amplitude)
            UiStyle.HALO -> HaloRingUi(state = state, amplitude = amplitude)
            UiStyle.RADIAL -> RadialWaveformUi(state = state, amplitude = amplitude)
        }
    }
}

// -------------------------------------------------------------
// 1. CARA DE ROBOT / OJOS ANIMADOS
// -------------------------------------------------------------
@Composable
fun AnimatedEyesUi(state: PalantirState, amplitude: Int) {
    var isBlinking by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        while (state == PalantirState.WAITING_WAKE_WORD) {
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

    val eyeHeight: Dp = when {
        isBlinking -> 3.dp
        state == PalantirState.WAITING_WAKE_WORD -> 14.dp
        state == PalantirState.LISTENING -> 34.dp
        state == PalantirState.THINKING -> 18.dp
        state == PalantirState.SPEAKING -> (24 + (amplitude / 800).coerceIn(0, 16)).dp
        else -> 14.dp
    }

    val eyeWidth: Dp = if (state == PalantirState.LISTENING) 26.dp else 22.dp

    val eyeColor = when (state) {
        PalantirState.WAITING_WAKE_WORD -> Color(0xFF64B5F6)
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
// 2. ORBE SCI-FI / JARVIS
// -------------------------------------------------------------
@Composable
fun SciFiOrbUi(state: PalantirState) {
    val duration = when (state) {
        PalantirState.WAITING_WAKE_WORD -> 8000
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
        PalantirState.WAITING_WAKE_WORD -> Color(0xFF00E5FF)
        PalantirState.LISTENING -> Color(0xFFFF1744)
        PalantirState.THINKING -> Color(0xFFFF9100)
        PalantirState.SPEAKING -> Color(0xFF00E676)
    }

    Canvas(
        modifier = Modifier
            .size(110.dp)
            .scale(if (state == PalantirState.WAITING_WAKE_WORD) pulseScale else 1.0f)
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
// 3. ORBE REACTIVO
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
        PalantirState.WAITING_WAKE_WORD -> idleScale
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
        PalantirState.WAITING_WAKE_WORD -> Color(0xFF29B6F6)
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

// -------------------------------------------------------------
// 4. ORBE MÍSTICO (MYSTIC)
// -------------------------------------------------------------
@Composable
fun MysticOrbUi(state: PalantirState, amplitude: Int) {
    val rotationDuration = when (state) {
        PalantirState.WAITING_WAKE_WORD -> 10000
        PalantirState.LISTENING -> 4000
        PalantirState.THINKING -> 1500
        PalantirState.SPEAKING -> 3000
    }

    val infiniteTransition = rememberInfiniteTransition(label = "mysticTransition")
    val mistPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(rotationDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mistPhase"
    )

    val voicePulse = (amplitude / 8000f).coerceIn(0f, 0.35f)
    val baseScale = if (state == PalantirState.THINKING) 1.08f else 1.0f
    val animatedScale by animateFloatAsState(
        targetValue = baseScale + voicePulse,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "mysticScale"
    )

    // Gradientes de colores según el estado
    val mistColors = when (state) {
        PalantirState.WAITING_WAKE_WORD -> listOf(
            Color(0xFF8A2BE2), // Violeta
            Color(0xFF00FFFF), // Cyan
            Color(0xFF1E90FF), // Azul brillante
            Color(0xFF0A0E29),
            Color.Transparent
        )
        PalantirState.LISTENING -> listOf(
            Color(0xFFFF1744), // Rojo
            Color(0xFFFF5252),
            Color(0xFF880E4F),
            Color(0xFF1A0005),
            Color.Transparent
        )
        PalantirState.THINKING -> listOf(
            Color(0xFFFF9100), // Naranja
            Color(0xFFFFD54F),
            Color(0xFFFF6D00),
            Color(0xFF261200),
            Color.Transparent
        )
        PalantirState.SPEAKING -> listOf(
            Color(0xFF00E676), // Verde
            Color(0xFF00B0FF),
            Color(0xFF1DE9B6),
            Color(0xFF002611),
            Color.Transparent
        )
    }

    Canvas(
        modifier = Modifier
            .size(115.dp)
            .scale(animatedScale)
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f

        // Anillo exterior con pequeñas runas/marcas sutiles
        val numRunes = 32
        val runeRadius = radius * 0.98f
        for (i in 0 until numRunes) {
            val angle = (i * 360f / numRunes) * (PI / 180f)
            val runeLength = 8f
            val start = Offset(
                (center.x + runeRadius * cos(angle)).toFloat(),
                (center.y + runeRadius * sin(angle)).toFloat()
            )
            val end = Offset(
                (center.x + (runeRadius - runeLength) * cos(angle + 0.08)).toFloat(),
                (center.y + (runeRadius - runeLength) * sin(angle + 0.08)).toFloat()
            )
            drawLine(
                color = Color(0xFF444466).copy(alpha = 0.5f),
                start = start,
                end = end,
                strokeWidth = 1.dp.toPx()
            )
        }

        // Desplazamiento dinámico del núcleo para simular movimiento místico
        val movingCenter = Offset(
            center.x + cos(mistPhase) * 12.dp.toPx(),
            center.y + sin(mistPhase) * 12.dp.toPx()
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = mistColors,
                center = movingCenter,
                radius = radius * 0.9f
            ),
            radius = radius * 0.9f
        )
    }
}

// -------------------------------------------------------------
// 5. ANILLO ENERGÉTICO (HALO)
// -------------------------------------------------------------
@Composable
fun HaloRingUi(state: PalantirState, amplitude: Int) {
    val duration = when (state) {
        PalantirState.WAITING_WAKE_WORD -> 6000
        PalantirState.LISTENING -> 2000
        PalantirState.THINKING -> 800
        PalantirState.SPEAKING -> 2500
    }

    val infiniteTransition = rememberInfiniteTransition(label = "haloRotation")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val strokeWidth = when (state) {
        PalantirState.SPEAKING, PalantirState.LISTENING -> (3 + (amplitude / 2000).coerceIn(0, 5)).dp
        PalantirState.THINKING -> 5.dp
        else -> 3.dp
    }

    val haloColors = when (state) {
        PalantirState.WAITING_WAKE_WORD -> listOf(
            Color(0xFF00FFFF),
            Color(0xFFFF00FF),
            Color(0xFFFF8C00),
            Color(0xFFFFFF00),
            Color(0xFF00FFFF)
        )
        PalantirState.LISTENING -> listOf(
            Color(0xFFFF1744),
            Color(0xFFFF5252),
            Color(0xFFFF8A80),
            Color(0xFFFF1744)
        )
        PalantirState.THINKING -> listOf(
            Color(0xFFFF9100),
            Color(0xFFFFD54F),
            Color(0xFFFFAB40),
            Color(0xFFFF9100)
        )
        PalantirState.SPEAKING -> listOf(
            Color(0xFF00E676),
            Color(0xFF00B0FF),
            Color(0xFF69F0AE),
            Color(0xFF00E676)
        )
    }

    Canvas(modifier = Modifier.size(120.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = (size.minDimension / 2f) - strokeWidth.toPx()

        rotate(rotationAngle, pivot = center) {
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = haloColors,
                    center = center
                ),
                radius = radius,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

// -------------------------------------------------------------
// 6. FORMA DE ONDA RADIAL (RADIAL)
// -------------------------------------------------------------
@Composable
fun RadialWaveformUi(state: PalantirState, amplitude: Int) {
    val numBars = 40
    val barHeights = remember { mutableStateListOf<Float>().apply { repeat(numBars) { add(0.3f) } } }

    LaunchedEffect(state, amplitude) {
        while (true) {
            for (i in 0 until numBars) {
                barHeights[i] = when (state) {
                    PalantirState.WAITING_WAKE_WORD -> 0.2f + (sin(i + System.currentTimeMillis() / 300.0) * 0.1f).toFloat()
                    PalantirState.LISTENING, PalantirState.SPEAKING -> {
                        val baseAmp = (amplitude / 6000f).coerceIn(0.1f, 0.9f)
                        (baseAmp * Random.nextFloat()).coerceIn(0.15f, 0.95f)
                    }
                    PalantirState.THINKING -> 0.3f + (sin(i * 0.5 + System.currentTimeMillis() / 150.0) * 0.3f).toFloat()
                }
            }
            kotlinx.coroutines.delay(60)
        }
    }

    val (startColor, endColor) = when (state) {
        PalantirState.WAITING_WAKE_WORD -> Color(0xFF00FFFF) to Color(0xFF0000FF)
        PalantirState.LISTENING -> Color(0xFFFF5252) to Color(0xFF880E4F)
        PalantirState.THINKING -> Color(0xFFFFD54F) to Color(0xFFFF6D00)
        PalantirState.SPEAKING -> Color(0xFF69F0AE) to Color(0xFF00838F)
    }

    Canvas(modifier = Modifier.size(120.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val innerRadius = size.minDimension / 5f
        val maxOuterRadius = size.minDimension / 2f

        for (i in 0 until numBars) {
            val angle = (i * 360f / numBars) * (PI / 180f)
            val currentBarHeightFactor = barHeights[i]
            val outerRadius = innerRadius + (maxOuterRadius - innerRadius) * currentBarHeightFactor

            val start = Offset(
                (center.x + innerRadius * cos(angle)).toFloat(),
                (center.y + innerRadius * sin(angle)).toFloat()
            )
            val end = Offset(
                (center.x + outerRadius * cos(angle)).toFloat(),
                (center.y + outerRadius * sin(angle)).toFloat()
            )

            drawLine(
                brush = Brush.linearGradient(
                    0f to startColor,
                    1f to endColor,
                    start = start,
                    end = end
                ),
                start = start,
                end = end,
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}