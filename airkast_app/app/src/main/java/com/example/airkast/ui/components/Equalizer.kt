package com.example.airkast.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

private val RestLevels = floatArrayOf(0.35f, 0.6f, 0.45f)

/**
 * 再生状態を示す 3 本バー。再生中のみ無限アニメーション(停止中は静止して電力を使わない)。
 * 装飾なので TalkBack には公開しない(再生状態は再生ボタン側が読み上げる)。
 */
@Composable
fun EqualizerBars(
    playing: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (playing) {
        val transition = rememberInfiniteTransition(label = "equalizer")
        val a = transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse),
            label = "bar1",
        )
        val b = transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(410, easing = LinearEasing), RepeatMode.Reverse),
            label = "bar2",
        )
        val c = transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(640, easing = LinearEasing), RepeatMode.Reverse),
            label = "bar3",
        )
        // State の読み取りは描画フェーズ内で行い、再コンポーズを避ける
        Canvas(modifier.size(24.dp)) {
            drawBars(color, a.value, b.value, c.value)
        }
    } else {
        Canvas(modifier.size(24.dp)) {
            drawBars(color, RestLevels[0], RestLevels[1], RestLevels[2])
        }
    }
}

private fun DrawScope.drawBars(color: Color, h1: Float, h2: Float, h3: Float) {
    val barWidth = size.width * 0.2f
    val gap = (size.width - barWidth * 3) / 2f
    val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
    listOf(h1, h2, h3).forEachIndexed { i, level ->
        val h = size.height * level
        drawRoundRect(
            color = color,
            topLeft = Offset(i * (barWidth + gap), size.height - h),
            size = Size(barWidth, h),
            cornerRadius = radius,
        )
    }
}
