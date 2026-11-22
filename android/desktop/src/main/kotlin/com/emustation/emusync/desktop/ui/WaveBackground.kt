package com.emustation.emusync.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.sin

/** PS3 の XMB の背景：グラデーションの上を、光の帯がゆっくり流れる */
@Composable
fun WaveBackground(colors: XmbColors, modifier: Modifier = Modifier, phase: Float? = null) {
    val t = if (phase != null) phase else {
        val anim = rememberInfiniteTransition(label = "wave")
        val p by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "phase")
        p
    }
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(colors.top, colors.bottom)))
        // 画面の中ほどを横切る、太さの違う何本かの帯
        ribbon(t, base = 0.58f, amp = 0.07f, freq = 1.1f, speed = 1f, width = 0.10f, alpha = 0.10f)
        ribbon(t, base = 0.62f, amp = 0.05f, freq = 1.6f, speed = -1f, width = 0.05f, alpha = 0.12f)
        ribbon(t, base = 0.55f, amp = 0.09f, freq = 0.8f, speed = 2f, width = 0.018f, alpha = 0.22f)
        ribbon(t, base = 0.66f, amp = 0.04f, freq = 2.1f, speed = -2f, width = 0.008f, alpha = 0.30f)
    }
}

private fun DrawScope.ribbon(t: Float, base: Float, amp: Float, freq: Float, speed: Float, width: Float, alpha: Float) {
    val w = size.width
    val h = size.height
    val steps = 64
    fun y(x: Float, shift: Float) =
        h * (base + amp * sin((x / w * freq * 2 * PI + t * speed * 2 * PI + shift).toFloat()) +
            amp * 0.35f * sin((x / w * freq * 5 * PI - t * speed * 2 * PI).toFloat()))
    val top = Path()
    val bottom = mutableListOf<Offset>()
    for (i in 0..steps) {
        val x = w * i / steps
        val y1 = y(x, 0f)
        val y2 = y1 + h * width * (0.6f + 0.4f * sin((x / w * 3 * PI + t * 2 * PI).toFloat()))
        if (i == 0) top.moveTo(x, y1) else top.lineTo(x, y1)
        bottom += Offset(x, y2)
    }
    bottom.asReversed().forEach { top.lineTo(it.x, it.y) }
    top.close()
    drawPath(
        top,
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = alpha), Color.White.copy(alpha = 0f)),
            startY = h * (base - amp), endY = h * (base + amp + width),
        ),
    )
}
