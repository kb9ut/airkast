package com.example.airkast.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.airkast.AirkastProgram
import com.example.airkast.R
import com.example.airkast.service.HlsDownloadService
import com.example.airkast.ui.ProgramDownloadState
import com.example.airkast.ui.theme.AirkastMotion
import com.example.airkast.ui.theme.MinTouchTarget
import com.example.airkast.ui.theme.PillShape
import com.example.airkast.ui.theme.Spacing
import com.example.airkast.ui.theme.TabularNumbers
import com.example.airkast.ui.theme.pressScale

// ────────────────────────────────────────────────────────────
// 番組行(番組を探す)
// ────────────────────────────────────────────────────────────

/**
 * 番組表の 1 行。左に時刻、中央にタイトルと出演者、右にダウンロードボタン。
 * [downloadState] だけから描画するので、状態判定は呼び出し側([resolveProgramDownloadState])に集約される。
 */
@Composable
fun ProgramRow(
    program: AirkastProgram,
    downloadState: ProgramDownloadState,
    shape: Shape,
    onDownloadClick: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val times = program.formattedDisplayTime.split(" - ")
    val downloadable = program.isDownloadable

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = colors.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 72.dp)
                .padding(start = Spacing.l, end = Spacing.s, top = Spacing.m, bottom = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 時刻
            Column(modifier = Modifier.widthIn(min = 52.dp)) {
                Text(
                    text = times.first(),
                    style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
                    color = if (downloadable) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1,
                )
                if (times.size == 2) {
                    Text(
                        text = times[1],
                        style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.l))

            // タイトル + 出演者
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (program.performer.isNotEmpty()) {
                    Text(
                        text = program.performer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 操作(お気に入りの★は onToggleFavorite があるときだけ)
            if (onToggleFavorite != null) {
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(MinTouchTarget)) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(
                            if (isFavorite) R.string.favorite_remove else R.string.favorite_add,
                        ),
                        tint = if (isFavorite) colors.primary else colors.onSurfaceVariant,
                    )
                }
            }
            if (downloadable) {
                DownloadButton(
                    state = downloadState,
                    programTitle = program.title,
                    onClick = onDownloadClick,
                )
            } else {
                val description = stringResource(R.string.program_not_downloadable)
                Box(
                    modifier = Modifier
                        .size(MinTouchTarget)
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
// ダウンロード進行カード(ダウンロード画面)
// ────────────────────────────────────────────────────────────

@Composable
fun DownloadProgressCard(
    status: HlsDownloadService.DownloadStatus,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isMuxing = status is HlsDownloadService.DownloadStatus.Muxing
    val percent = when (status) {
        is HlsDownloadService.DownloadStatus.InProgress -> status.progress
        is HlsDownloadService.DownloadStatus.Muxing -> status.progress
        else -> 0
    }.coerceIn(0, 100)
    val statusText = when (status) {
        is HlsDownloadService.DownloadStatus.Pending -> stringResource(R.string.state_pending)
        is HlsDownloadService.DownloadStatus.InProgress -> stringResource(R.string.state_downloading, percent)
        is HlsDownloadService.DownloadStatus.Muxing -> stringResource(R.string.state_muxing, percent)
        else -> ""
    }
    val progressColor = if (isMuxing) colors.tertiary else colors.primary
    val animatedProgress by animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = AirkastMotion.effects(),
        label = "progress",
    )
    val program = status.program

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(start = Spacing.l, top = Spacing.m, end = Spacing.s, bottom = Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = program.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${program.formattedDisplayDate} ${program.formattedDisplayTime}",
                        style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(MinTouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_cancel_download, program.title),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge.merge(TabularNumbers),
                color = progressColor,
            )
            Spacer(Modifier.height(Spacing.s))
            Box(modifier = Modifier.padding(end = Spacing.m)) {
                if (status is HlsDownloadService.DownloadStatus.Pending) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = progressColor,
                        trackColor = colors.surfaceVariant,
                        strokeCap = StrokeCap.Round,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = progressColor,
                        trackColor = colors.surfaceVariant,
                        strokeCap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────
// ダウンロード済みファイル行
// ────────────────────────────────────────────────────────────

@Composable
fun DownloadedFileRow(
    title: String,
    subInfo: String,
    shape: Shape,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        onClick = onPlayClick,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interactionSource, pressedScale = 0.98f),
        shape = shape,
        color = colors.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 72.dp)
                .padding(start = Spacing.l, end = Spacing.s, top = Spacing.m, bottom = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Surface(
                shape = PillShape,
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
                modifier = Modifier.size(MinTouchTarget),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.cd_play, title),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subInfo.isNotEmpty()) {
                    Text(
                        text = subInfo,
                        style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier.size(MinTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_delete, title),
                    tint = colors.onSurfaceVariant,
                )
            }
        }
    }
}
