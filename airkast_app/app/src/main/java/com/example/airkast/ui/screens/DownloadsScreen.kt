package com.example.airkast.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.airkast.AirkastProgram
import com.example.airkast.MainViewModel
import com.example.airkast.R
import com.example.airkast.UiState
import com.example.airkast.service.HlsDownloadService
import com.example.airkast.ui.components.DownloadProgressCard
import com.example.airkast.ui.components.DownloadedFileRow
import com.example.airkast.ui.components.StateMessage
import com.example.airkast.ui.theme.MinTouchTarget
import com.example.airkast.ui.theme.Spacing
import com.example.airkast.ui.theme.groupedItemShape
import java.io.File

/** ダウンロード画面。進行中カード + ダウンロード済みリスト。 */
@Composable
fun DownloadsScreen(
    viewModel: MainViewModel,
    contentBottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val downloadedFiles by viewModel.downloadedFiles.collectAsState()
    val ongoingDownloadStates by viewModel.ongoingDownloadStates.collectAsState()
    val programGuideState by viewModel.programGuideUiState.collectAsState()

    val ongoingTasks = remember(ongoingDownloadStates) {
        ongoingDownloadStates.filterValues {
            it is HlsDownloadService.DownloadStatus.InProgress ||
                it is HlsDownloadService.DownloadStatus.Pending ||
                it is HlsDownloadService.DownloadStatus.Muxing
        }.toList()
    }

    val programMap = remember(programGuideState) {
        val state = programGuideState
        if (state is UiState.Success) state.data.associateBy { it.id } else emptyMap()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.l,
            end = Spacing.l,
            top = Spacing.s,
            bottom = contentBottomPadding + Spacing.l,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // ── ダウンロード中 ──
        if (ongoingTasks.isNotEmpty()) {
            item {
                SectionHeader(title = stringResource(R.string.section_ongoing))
            }
            items(ongoingTasks) { (programId, status) ->
                DownloadProgressCard(
                    status = status,
                    onCancel = { viewModel.cancelDownload(programId) },
                    modifier = Modifier.padding(bottom = Spacing.s),
                )
            }
            item { Spacer(Modifier.height(Spacing.s)) }
        }

        // ── ダウンロード済み ──
        item {
            SectionHeader(
                title = stringResource(R.string.section_downloaded),
                trailing = {
                    IconButton(
                        onClick = { viewModel.onScanForDownloads() },
                        modifier = Modifier.size(MinTouchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }

        if (downloadedFiles.isEmpty()) {
            item {
                StateMessage(
                    icon = Icons.Outlined.LibraryMusic,
                    title = stringResource(R.string.empty_downloads_title),
                    body = stringResource(R.string.empty_downloads_body),
                    modifier = Modifier.padding(vertical = Spacing.xxl),
                )
            }
        } else {
            itemsIndexed(downloadedFiles) { index, file ->
                val parsed = HlsDownloadService.parseFileName(file.name)
                val title = parsed?.title ?: file.name.substringBeforeLast(".")
                val subInfo = downloadedFileSubInfo(parsed)
                DownloadedFileRow(
                    title = title,
                    subInfo = subInfo,
                    shape = groupedItemShape(index, downloadedFiles.size),
                    onPlayClick = { viewModel.onPlayFile(file, programForFile(file, programMap)) },
                    onDeleteClick = { viewModel.onDeleteFile(file) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.xs, top = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = Spacing.s),
        )
        trailing()
    }
}

private fun downloadedFileSubInfo(parsed: HlsDownloadService.Companion.ParsedFileName?): String {
    if (parsed == null || parsed.startTime.isEmpty()) return ""
    val s = parsed.startTime
    val dateStr = "${s.substring(4, 6)}/${s.substring(6, 8)} ${s.substring(8, 10)}:${s.substring(10, 12)}"
    return if (parsed.stationId.isNotEmpty()) "${parsed.stationId}  ·  $dateStr" else dateStr
}

/** ダウンロード済みファイルから再生用の [AirkastProgram] を組み立てる(従来ロジックそのまま)。 */
private fun programForFile(file: File, programMap: Map<String, AirkastProgram>): AirkastProgram {
    val fileName = file.name
    val parsed = HlsDownloadService.parseFileName(fileName)

    return if (parsed != null && parsed.startTime.isNotEmpty()) {
        val s = parsed.startTime
        val e = parsed.endTime
        val hour = if (s.length >= 10) s.substring(8, 10) else ""
        val minute = if (s.length >= 12) s.substring(10, 12) else ""
        val endHour = if (e.length >= 10) e.substring(8, 10) else ""
        val endMinute = if (e.length >= 12) e.substring(10, 12) else ""

        AirkastProgram(
            id = fileName,
            stationId = parsed.stationId,
            title = parsed.title,
            description = "",
            performer = "${parsed.stationId} | ${s.substring(4, 6)}/${s.substring(6, 8)} | $hour:$minute - $endHour:$endMinute",
            startTime = parsed.startTime,
            endTime = parsed.endTime,
            imageUrl = null,
        )
    } else {
        val programId = fileName.substringBefore("_")
        val titlePart = fileName.substringAfter("_").substringBeforeLast(".")
        programMap[programId] ?: AirkastProgram(
            id = programId,
            stationId = "",
            title = titlePart,
            description = "",
            performer = "",
            startTime = "",
            endTime = "",
            imageUrl = null,
        )
    }
}
