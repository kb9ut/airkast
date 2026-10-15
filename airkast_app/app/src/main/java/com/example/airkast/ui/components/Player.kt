package com.example.airkast.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.airkast.MainViewModel
import com.example.airkast.R
import com.example.airkast.ui.theme.AirkastMotion
import com.example.airkast.ui.theme.MinTouchTarget
import com.example.airkast.ui.theme.PillShape
import com.example.airkast.ui.theme.Spacing
import com.example.airkast.ui.theme.TabularNumbers
import com.example.airkast.ui.theme.pressScale

/**
 * ミニプレイヤー + 展開プレイヤー。
 *
 * 画面下部(ナビゲーションバーの直上)に浮かぶカード。折りたたみ時はアートワーク風タイル・タイトル・
 * 再生/一時停止・細い進捗線だけ。タップで展開し、シーク・スキップ・再生速度が現れる。
 * リストを押し下げないよう、コンテンツ領域に重ねて描画する(重なる高さは
 * [onReservedHeightChange] で呼び出し側に通知し、リストの下余白に使う)。
 */
@Composable
fun PlayerOverlay(
    viewModel: MainViewModel,
    onReservedHeightChange: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mediaMetadata by viewModel.mediaMetadata.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val position by viewModel.playbackPosition.collectAsState()
    val duration by viewModel.playbackDuration.collectAsState()
    val speed by viewModel.playbackSpeed.collectAsState()

    val visible = mediaMetadata != null || duration != 0L
    var expanded by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current

    LaunchedEffect(visible) {
        if (!visible) {
            onReservedHeightChange(0.dp)
            expanded = false
        }
    }
    BackHandler(enabled = visible && expanded) { expanded = false }

    Box(modifier = modifier) {
        // 展開中は背面を少し落とし、外側タップで閉じる
        AnimatedVisibility(
            visible = visible && expanded,
            enter = fadeIn(AirkastMotion.effects()),
            exit = fadeOut(AirkastMotion.effects()),
        ) {
            val collapseLabel = stringResource(R.string.player_collapse)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = collapseLabel,
                    ) { expanded = false },
            )
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(AirkastMotion.spatial()) { it } + fadeIn(AirkastMotion.effects()),
            exit = slideOutVertically(AirkastMotion.spatial()) { it } + fadeOut(AirkastMotion.effects()),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.m, vertical = Spacing.s),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shadowElevation = 6.dp,
            ) {
                Column {
                    MiniBar(
                        title = mediaMetadata?.title?.toString() ?: stringResource(R.string.player_loading_title),
                        subtitle = mediaMetadata?.artist?.toString().orEmpty(),
                        isPlaying = isPlaying,
                        expanded = expanded,
                        progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onToggleExpanded = { expanded = !expanded },
                        onPlayPause = { viewModel.playPause() },
                        modifier = Modifier.onSizeChanged { size ->
                            // 浮遊カードの上下マージン(8dp x 2)+ リストとの余白(8dp)
                            onReservedHeightChange(with(density) { size.height.toDp() } + 24.dp)
                        },
                    )
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(AirkastMotion.spatial<IntSize>()) + fadeIn(AirkastMotion.effects()),
                        exit = shrinkVertically(AirkastMotion.spatial<IntSize>()) + fadeOut(AirkastMotion.effects()),
                    ) {
                        ExpandedControls(
                            viewModel = viewModel,
                            isPlaying = isPlaying,
                            position = position,
                            duration = duration,
                            speed = speed,
                        )
                    }
                }
            }
        }
    }

}

// ────────────────────────────────────────────────────────────
// 折りたたみ時の行
// ────────────────────────────────────────────────────────────

@Composable
private fun MiniBar(
    title: String,
    subtitle: String,
    isPlaying: Boolean,
    expanded: Boolean,
    progress: Float,
    onToggleExpanded: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val toggleLabel = stringResource(if (expanded) R.string.player_collapse else R.string.player_expand)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = AirkastMotion.effects(),
        label = "miniProgress",
    )
    val lineAlpha by animateFloatAsState(
        targetValue = if (expanded) 0f else 1f,
        animationSpec = AirkastMotion.effects(),
        label = "miniProgressAlpha",
    )

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = toggleLabel, onClick = onToggleExpanded)
                .padding(start = Spacing.m, end = Spacing.s, top = Spacing.m, bottom = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = colors.tertiaryContainer,
                contentColor = colors.onTertiaryContainer,
                modifier = Modifier.size(MinTouchTarget),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    EqualizerBars(playing = isPlaying, color = LocalContentColor.current)
                }
            }
            Spacer(Modifier.width(Spacing.m))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = if (expanded) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AnimatedContent(
                targetState = expanded,
                transitionSpec = {
                    (fadeIn(AirkastMotion.effects()) + scaleIn(AirkastMotion.spatial(), initialScale = 0.7f)) togetherWith
                        fadeOut(AirkastMotion.effects())
                },
                label = "miniTrailing",
            ) { isExpanded ->
                if (isExpanded) {
                    IconButton(onClick = onToggleExpanded, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.player_collapse),
                        )
                    }
                } else {
                    PlayPauseButton(isPlaying = isPlaying, onClick = onPlayPause, size = MinTouchTarget)
                }
            }
        }
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.m)
                .height(3.dp)
                .alpha(lineAlpha),
            color = colors.primary,
            trackColor = colors.outlineVariant,
            strokeCap = StrokeCap.Round,
        )
    }
}

// ────────────────────────────────────────────────────────────
// 展開時のコントロール
// ────────────────────────────────────────────────────────────

@Composable
private fun ExpandedControls(
    viewModel: MainViewModel,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    speed: Float,
) {
    val colors = MaterialTheme.colorScheme
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val max = if (duration > 0) duration.toFloat() else 1f
    val shown = (dragValue ?: position.toFloat()).coerceIn(0f, max)
    val seekLabel = stringResource(R.string.player_seek)
    val positionDescription = stringResource(
        R.string.player_position_description,
        formatTime(shown.toLong()),
        formatTime(duration),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.l),
    ) {
        // シークバー
        Slider(
            value = shown,
            onValueChange = {
                dragValue = it
                viewModel.seekTo(it.toLong())
            },
            onValueChangeFinished = { dragValue = null },
            valueRange = 0f..max,
            enabled = duration > 0,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = seekLabel
                    stateDescription = positionDescription
                },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(shown.toLong()),
                style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                color = colors.onSurfaceVariant,
            )
            Text(
                text = formatTime(duration),
                style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                color = colors.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.s))

        // 再生/一時停止(中央)と再生速度(左)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                SpeedSelector(speed = speed, onSpeedSelected = { viewModel.setPlaybackSpeed(it) })
            }
            PlayPauseButton(isPlaying = isPlaying, onClick = { viewModel.playPause() }, size = 72.dp)
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(Spacing.m))

        // スキップ: 左が戻る(60/30/15秒)、右が進む(60秒/2分/3分)。各ボタン 48dp 以上
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            SkipGroup(modifier = Modifier.weight(1f)) {
                SkipButton(seconds = 60, label = "-60", isForward = false, modifier = Modifier.weight(1f)) { viewModel.skipBackward(60) }
                SkipButton(seconds = 30, label = "-30", isForward = false, modifier = Modifier.weight(1f)) { viewModel.skipBackward(30) }
                SkipButton(seconds = 15, label = "-15", isForward = false, modifier = Modifier.weight(1f)) { viewModel.skipBackward(15) }
            }
            SkipGroup(modifier = Modifier.weight(1f)) {
                SkipButton(seconds = 60, label = "+60", isForward = true, modifier = Modifier.weight(1f)) { viewModel.skipForward(60) }
                SkipButton(seconds = 120, label = "+2m", isForward = true, modifier = Modifier.weight(1f)) { viewModel.skipForward(120) }
                SkipButton(seconds = 180, label = "+3m", isForward = true, modifier = Modifier.weight(1f)) { viewModel.skipForward(180) }
            }
        }
    }
}

/** スキップボタンを束ねるトーナルなピル。 */
@Composable
private fun SkipGroup(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            content = content,
        )
    }
}

@Composable
private fun SpeedSelector(
    speed: Float,
    onSpeedSelected: (Float) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val speedText = stringResource(R.string.player_speed_value, speed.toString())
    val description = stringResource(R.string.player_speed) + " " + speedText

    Box {
        Surface(
            onClick = { menuExpanded = true },
            modifier = Modifier
                .heightIn(min = MinTouchTarget)
                .semantics { contentDescription = description },
            shape = PillShape,
            color = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Spacing.s))
                Text(
                    text = speedText,
                    style = MaterialTheme.typography.labelLarge.merge(TabularNumbers),
                )
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            listOf(0.5f, 0.8f, 1.0f, 1.25f, 1.5f, 1.8f, 2.0f).forEach { s ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.player_speed_value, s.toString()),
                            color = if (s == speed) colors.primary else colors.onSurface,
                            fontWeight = if (s == speed) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSpeedSelected(s)
                        menuExpanded = false
                    },
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
// 部品
// ────────────────────────────────────────────────────────────

/** 再生/一時停止。再生中は角丸の四角へ、停止中は円へバネで変形する。 */
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val corner by animateIntAsState(
        targetValue = if (isPlaying) 32 else 50,
        animationSpec = AirkastMotion.spatial(),
        label = "playPauseCorner",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val description = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play)

    FilledIconButton(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .pressScale(interactionSource)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(percent = corner.coerceIn(0, 50)),
        interactionSource = interactionSource,
    ) {
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                (fadeIn(AirkastMotion.effects()) + scaleIn(AirkastMotion.spatial(), initialScale = 0.6f)) togetherWith
                    fadeOut(AirkastMotion.effects())
            },
            label = "playPauseIcon",
        ) { playing ->
            Icon(
                imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

@Composable
private fun SkipButton(
    seconds: Int,
    label: String,
    isForward: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val description = stringResource(
        if (isForward) R.string.player_skip_forward else R.string.player_skip_back,
        seconds,
    )
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .pressScale(interactionSource)
            .semantics { contentDescription = description },
        shape = PillShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = if (isForward) Icons.AutoMirrored.Filled.Redo else Icons.AutoMirrored.Filled.Undo,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.merge(TabularNumbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
