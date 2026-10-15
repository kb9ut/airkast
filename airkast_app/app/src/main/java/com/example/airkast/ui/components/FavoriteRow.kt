package com.example.airkast.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.airkast.AirkastProgram
import com.example.airkast.R
import com.example.airkast.favorites.FavoriteRow
import com.example.airkast.favorites.FavoriteRowStatus
import com.example.airkast.radikoTimestampFormat
import com.example.airkast.ui.ProgramDownloadState
import com.example.airkast.ui.theme.MinTouchTarget
import com.example.airkast.ui.theme.Spacing
import com.example.airkast.ui.theme.TabularNumbers
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * お気に入りの 1 行。番組名・局名・最新回の日時、状態に応じた操作、自動DL のスイッチ、★(解除)。
 * 状態は [downloadState] と [autoRunning] だけから描画する。
 */
@Composable
fun FavoriteRowCard(
    row: FavoriteRow,
    stationName: String,
    downloadState: ProgramDownloadState,
    autoRunning: Boolean,
    shape: Shape,
    onDownloadClick: () -> Unit,
    onPlayClick: () -> Unit,
    onAutoDownloadChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = colors.surfaceContainer) {
        Column(modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.favorite.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stationName,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(MinTouchTarget)) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = stringResource(R.string.favorite_remove),
                        tint = colors.primary,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 最新回の見出し(日時) または 状態メッセージ
                Column(modifier = Modifier.weight(1f)) {
                    val latest = row.latest
                    when {
                        row.status == FavoriteRowStatus.LOADING ->
                            Text(
                                stringResource(R.string.favorite_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        row.status == FavoriteRowStatus.ERROR ->
                            Text(
                                stringResource(R.string.favorite_load_error),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.error,
                            )
                        latest == null ->
                            Text(
                                stringResource(R.string.favorite_no_recent),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        else -> {
                            Text(
                                text = formatLatestHeadline(latest),
                                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                                color = colors.onSurface,
                            )
                            if (autoRunning && downloadState is ProgramDownloadState.Idle) {
                                Text(
                                    text = stringResource(R.string.favorite_auto_running),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.primary,
                                )
                            }
                        }
                    }
                }
                val latest = row.latest
                if (latest != null) {
                    when {
                        // DownloadButton の完了表示は押せないバッジなので、取得済みは別に再生ボタンを出す
                        downloadState is ProgramDownloadState.Completed ->
                            FilledTonalIconButton(onClick = onPlayClick, modifier = Modifier.size(MinTouchTarget)) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.cd_play, latest.title),
                                )
                            }
                        // 自動取得が(別経路で)進行中。進捗が届くまでの間はインジケータだけ出す
                        autoRunning && downloadState is ProgramDownloadState.Idle ->
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        else -> DownloadButton(
                            state = downloadState,
                            programTitle = latest.title,
                            onClick = onDownloadClick,
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.s))
                Text(
                    stringResource(R.string.favorite_auto_download),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(Spacing.xs))
                Switch(checked = row.favorite.autoDownload, onCheckedChange = onAutoDownloadChange)
            }
        }
    }
}

/** 「1/24(土) 22:00 - 23:00」のような最新回の見出し。 */
private fun formatLatestHeadline(program: AirkastProgram): String {
    val date = runCatching {
        SimpleDateFormat("M/d(E)", Locale.JAPAN).format(radikoTimestampFormat().parse(program.startTime)!!)
    }.getOrDefault("")
    return "$date ${program.formattedDisplayTime}".trim()
}
