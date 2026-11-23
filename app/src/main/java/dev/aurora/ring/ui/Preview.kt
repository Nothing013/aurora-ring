package dev.aurora.ring.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.aurora.ring.audio.BeatEngine
import dev.aurora.ring.data.Settings
import dev.aurora.ring.engine.Animator
import dev.aurora.ring.led.LedFrame
import kotlin.math.cos
import kotlin.math.sin

/**
 * Live ring preview. It renders the exact same [Animator] output the service
 * sends to hardware, so what you see here is what the camera ring does.
 */
@Composable
fun RingPreview(
    settings: Settings,
    audio: BeatEngine.Snapshot,
    modifier: Modifier = Modifier
) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            now = SystemClock.elapsedRealtime()
        }
    }

    val frame = remember(now, settings, audio) { Animator.frame(now, settings, audio) }
    val beat = if (settings.beatReactive) audio.beat.coerceIn(0f, 1f) else 0f

    val primary: Color
    val secondary: Color
    val spot: Boolean
    val spotA: Boolean
    val dual: Boolean
    when (frame) {
        is LedFrame.Solid -> {
            primary = Color(frame.color)
            secondary = primary
            dual = false
            spot = false
            spotA = true
        }
        is LedFrame.Dual -> {
            primary = Color(frame.a)
            secondary = Color(frame.b)
            dual = true
            spot = false
            spotA = true
        }
        is LedFrame.Spot -> {
            primary = Color(frame.color)
            secondary = primary
            dual = false
            spot = true
            spotA = frame.spotA
        }
        LedFrame.Off -> {
            primary = Color(0xFF1A1E2A)
            secondary = primary
            dual = false
            spot = false
            spotA = true
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.355f
            val dotRadius = size.minDimension * 0.021f
            val glowRadius = size.minDimension * 0.055f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.20f + 0.25f * beat),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * 1.45f
                ),
                radius = radius * 1.45f,
                center = center
            )

            drawCircle(
                color = Color.White.copy(alpha = 0.05f),
                radius = radius,
                center = center,
                style = Stroke(width = glowRadius)
            )

            for (i in 0 until 24) {
                val angle = Math.toRadians(-90.0 + i * 15.0)
                val pos = Offset(
                    x = center.x + cos(angle).toFloat() * radius,
                    y = center.y + sin(angle).toFloat() * radius
                )
                val c = when {
                    spot -> {
                        val spotAngle = if (spotA) -90.0 else 90.0
                        val delta = angleDelta(-90.0 + i * 15.0, spotAngle)
                        if (delta < 55.0) primary.copy(alpha = (1f - (delta / 55.0)).toFloat()) else Color(0xFF171B26)
                    }
                    dual -> if (i < 12) primary else secondary
                    else -> primary
                }
                drawCircle(
                    color = c.copy(alpha = 0.30f),
                    radius = dotRadius * 2.4f,
                    center = pos
                )
                drawCircle(color = c, radius = dotRadius, center = pos)
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF11151F),
                        Color(0xFF07090F)
                    ),
                    center = center,
                    radius = radius * 0.82f
                ),
                radius = radius * 0.80f,
                center = center
            )

            drawCircle(
                color = primary.copy(alpha = 0.16f + 0.22f * beat),
                radius = radius * 0.80f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            drawCircle(
                color = Color.White.copy(alpha = 0.05f),
                radius = radius * 0.52f,
                center = center,
                style = Stroke(width = 0.8.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

private fun angleDelta(a: Double, b: Double): Double {
    var d = Math.abs(a - b) % 360.0
    if (d > 180.0) d = 360.0 - d
    return d
}

@Composable
fun LevelMeter(
    audio: BeatEngine.Snapshot,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val values = listOf(audio.low, audio.mid, audio.high, audio.level)
        values.forEachIndexed { index, v ->
            val color = when (index) {
                0 -> Aurora.Pink
                1 -> Aurora.Primary
                2 -> Aurora.Mint
                else -> Aurora.Amber
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.05f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
                        )
                        val h = size.height * v.coerceIn(0f, 1f)
                        drawRoundRect(
                            color = color.copy(alpha = 0.85f),
                            topLeft = Offset(0f, size.height - h),
                            size = androidx.compose.ui.geometry.Size(size.width, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
                        )
                    }
                }
                Text(
                    text = listOf("LOW", "MID", "HIGH", "RMS")[index],
                    style = MaterialTheme.typography.labelMedium,
                    color = Aurora.TextDim
                )
            }
        }
    }
}

@Composable
fun BeatDot(beat: Float, bpm: Float, active: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size((10 + 14 * beat.coerceIn(0f, 1f)).dp)
                .clip(RoundedCornerShape(50))
                .padding(0.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = if (active) Aurora.Mint else Aurora.TextDim.copy(alpha = 0.4f))
            }
        }
        Text(
            text = if (active && bpm > 40f) "${bpm.toInt()} BPM" else if (active) "listening" else "idle",
            style = MaterialTheme.typography.labelLarge,
            color = if (active) Aurora.Mint else Aurora.TextDim
        )
    }
}
