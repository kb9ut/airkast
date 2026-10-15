package com.example.airkast.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.airkast.R
import com.example.airkast.ui.theme.AirkastMotion
import com.example.airkast.ui.theme.MinTouchTarget
import com.example.airkast.ui.theme.PillShape
import com.example.airkast.ui.theme.Spacing
import com.example.airkast.ui.theme.TabularNumbers
import kotlinx.coroutines.withTimeoutOrNull

/** この時間より長く押し続けるとターボ開始(ミリ秒)。 */
private const val TURBO_HOLD_MS = 200L

/**
 * ターボボタン。長押し中だけ N 倍速(離すと停止)、短いタップで倍速設定を開く。
 * 操作仕様は従来どおり(200ms で長押し判定)。TalkBack からはカスタム onClick で設定を開ける。
 */
@Composable
fun TurboButton(
    turboSpeed: Float,
    isTurboActive: Boolean,
    onTurboStart: () -> Unit,
    onTurboStop: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (isTurboActive) colors.tertiary else colors.tertiaryContainer,
        animationSpec = AirkastMotion.effects(),
        label = "turboContainer",
    )
    val content by animateColorAsState(
        targetValue = if (isTurboActive) colors.onTertiary else colors.onTertiaryContainer,
        animationSpec = AirkastMotion.effects(),
        label = "turboContent",
    )
    val scale by animateFloatAsState(
        targetValue = if (isTurboActive) 1.06f else 1f,
        animationSpec = AirkastMotion.spatial(),
        label = "turboScale",
    )

    val currentOnTurboStart by rememberUpdatedState(onTurboStart)
    val currentOnTurboStop by rememberUpdatedState(onTurboStop)
    val currentOnTap by rememberUpdatedState(onTap)
    val speed = turboSpeed.toInt()
    val description = stringResource(R.string.player_turbo_description, speed)
    val actionLabel = stringResource(R.string.player_turbo_action)

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .heightIn(min = MinTouchTarget)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                onClick(label = actionLabel) {
                    currentOnTap()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var released = false
                    val up = withTimeoutOrNull(TURBO_HOLD_MS) {
                        val result = waitForUpOrCancellation()
                        released = true
                        result
                    }
                    if (released) {
                        // 200ms 以内に離した → タップ(設定を開く)
                        if (up != null) {
                            up.consume()
                            currentOnTap()
                        }
                    } else {
                        // 長押し → ターボ開始、離したら停止
                        currentOnTurboStart()
                        waitForUpOrCancellation()
                        currentOnTurboStop()
                    }
                }
            },
        shape = PillShape,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.FastForward,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.s))
            Text(
                text = stringResource(R.string.player_turbo) + " " +
                    stringResource(R.string.player_turbo_value, speed),
                style = MaterialTheme.typography.labelLarge.merge(TabularNumbers),
            )
        }
    }
}
