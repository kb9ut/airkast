package com.example.airkast.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.airkast.R
import com.example.airkast.ui.ProgramDownloadState
import com.example.airkast.ui.theme.AirkastMotion
import com.example.airkast.ui.theme.MinTouchTarget
import com.example.airkast.ui.theme.PillShape

private enum class DownloadButtonKind { Idle, Queued, Active, Done, Failed }

private val ProgramDownloadState.kind: DownloadButtonKind
    get() = when (this) {
        ProgramDownloadState.Idle -> DownloadButtonKind.Idle
        ProgramDownloadState.Queued -> DownloadButtonKind.Queued
        is ProgramDownloadState.Downloading, is ProgramDownloadState.Converting -> DownloadButtonKind.Active
        ProgramDownloadState.Completed -> DownloadButtonKind.Done
        is ProgramDownloadState.Failed -> DownloadButtonKind.Failed
    }

/**
 * 番組のダウンロードボタン。渡された [state] のみから描画する(副作用・参照なし)。
 *
 *  - Idle: 塗りトーンの丸ボタン(押すと [onClick])
 *  - Queued: 不確定の円形インジケータ
 *  - Downloading / Converting: 進捗リング(変換中は tertiary 色)
 *  - Completed: チェックのバッジ(押せない)
 *  - Failed: エラー色の再試行ボタン(押すと [onClick])
 *
 * 状態の種類が変わるときだけ拡縮+フェードで切り替わる(進捗値の更新では再生しない)。
 */
@Composable
fun DownloadButton(
    state: ProgramDownloadState,
    programTitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = state.kind,
        modifier = modifier.size(MinTouchTarget),
        contentAlignment = Alignment.Center,
        transitionSpec = {
            (fadeIn(AirkastMotion.effects()) +
                scaleIn(AirkastMotion.spatial(), initialScale = 0.6f)) togetherWith
                (fadeOut(AirkastMotion.effects()) +
                    scaleOut(AirkastMotion.effects(), targetScale = 0.6f))
        },
        label = "downloadButton",
    ) { kind ->
        when (kind) {
            DownloadButtonKind.Idle -> FilledTonalIconButton(
                onClick = onClick,
                modifier = Modifier.size(MinTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = stringResource(R.string.cd_download, programTitle),
                )
            }

            DownloadButtonKind.Queued -> {
                val description = stringResource(R.string.cd_download_queued, programTitle)
                Box(
                    modifier = Modifier
                        .size(MinTouchTarget)
                        .semantics(mergeDescendants = true) { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        strokeCap = StrokeCap.Round,
                    )
                }
            }

            DownloadButtonKind.Active -> ActiveProgress(state = state, programTitle = programTitle)

            DownloadButtonKind.Done -> {
                val description = stringResource(R.string.cd_downloaded, programTitle)
                Box(
                    modifier = Modifier
                        .size(MinTouchTarget)
                        .semantics(mergeDescendants = true) { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = PillShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            DownloadButtonKind.Failed -> FilledTonalIconButton(
                onClick = onClick,
                modifier = Modifier.size(MinTouchTarget),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.cd_download_failed, programTitle),
                )
            }
        }
    }
}

@Composable
private fun ActiveProgress(state: ProgramDownloadState, programTitle: String) {
    val isConverting = state is ProgramDownloadState.Converting
    val percent = when (state) {
        is ProgramDownloadState.Downloading -> state.progress
        is ProgramDownloadState.Converting -> state.progress
        else -> 0
    }.coerceIn(0, 100)

    val animated by animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = AirkastMotion.effects(),
        label = "downloadProgress",
    )
    val color = if (isConverting) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val description = stringResource(
        if (isConverting) R.string.state_muxing else R.string.state_downloading,
        percent,
    )

    Box(
        modifier = Modifier
            .size(MinTouchTarget)
            .semantics(mergeDescendants = true) {
                contentDescription = programTitle
                stateDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(percent / 100f, 0f..1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { animated },
            modifier = Modifier.size(36.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 3.5.dp,
            strokeCap = StrokeCap.Round,
        )
        Icon(
            imageVector = Icons.Default.ArrowDownward,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color,
        )
    }
}
