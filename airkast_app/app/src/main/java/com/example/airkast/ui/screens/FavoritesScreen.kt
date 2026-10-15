package com.example.airkast.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.airkast.DownloadRegistry
import com.example.airkast.MainViewModel
import com.example.airkast.R
import com.example.airkast.UiState
import com.example.airkast.favorites.FavoritesViewModel
import com.example.airkast.favorites.episodeKey
import com.example.airkast.favorites.favoriteKeyOf
import com.example.airkast.findDownloadedFile
import com.example.airkast.isProgramDownloaded
import com.example.airkast.ui.ProgramDownloadState
import com.example.airkast.ui.components.FavoriteRowCard
import com.example.airkast.ui.components.StateMessage
import com.example.airkast.ui.resolveProgramDownloadState
import com.example.airkast.ui.theme.Spacing
import com.example.airkast.ui.theme.groupedItemShape

/** お気に入りタブ。各番組の最新回を 1 タップで再生/ダウンロードできる。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    favoritesViewModel: FavoritesViewModel,
    mainViewModel: MainViewModel,
    contentBottomPadding: Dp,
    onBeforeEnableAutoDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows by favoritesViewModel.rows.collectAsState()
    val ongoing by mainViewModel.ongoingDownloadStates.collectAsState()
    val downloadedFiles by mainViewModel.downloadedFiles.collectAsState()
    val chunkSize by mainViewModel.downloadChunkSize.collectAsState()
    val stationState by mainViewModel.stationUiState.collectAsState()
    val claimed by DownloadRegistry.claimed.collectAsState()
    var refreshing by remember { mutableStateOf(false) }

    // タブを開くたびに最新回を更新する(キャッシュ済みでも取り直す)
    LaunchedEffect(Unit) { favoritesViewModel.refresh(force = true) }

    val stationNames = remember(stationState) {
        (stationState as? UiState.Success)?.data?.associate { it.id to it.name }.orEmpty()
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            // refresh は即座に return する(取得は ViewModel 側で進む)ので、インジケータはすぐ戻る
            refreshing = true
            favoritesViewModel.refresh(force = true)
            refreshing = false
        },
        modifier = modifier.fillMaxSize(),
    ) {
        if (rows.isEmpty()) {
            StateMessage(
                icon = Icons.Outlined.StarBorder,
                title = stringResource(R.string.favorites_empty_title),
                body = stringResource(R.string.favorites_empty_body),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.l,
                    end = Spacing.l,
                    top = Spacing.xs,
                    bottom = contentBottomPadding + Spacing.l,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(
                    rows,
                    key = { _, row -> favoriteKeyOf(row.favorite.stationId, row.favorite.title) },
                ) { index, row ->
                    val latest = row.latest
                    val state = if (latest == null) {
                        ProgramDownloadState.Idle
                    } else {
                        resolveProgramDownloadState(
                            status = ongoing[latest.id],
                            isFileDownloaded = isProgramDownloaded(downloadedFiles, latest),
                        )
                    }
                    FavoriteRowCard(
                        row = row,
                        stationName = stationNames[row.favorite.stationId] ?: row.favorite.stationId,
                        downloadState = state,
                        autoRunning = latest != null && latest.episodeKey in claimed,
                        shape = groupedItemShape(index, rows.size),
                        onDownloadClick = { latest?.let { mainViewModel.onDownloadProgram(it, chunkSize) } },
                        onPlayClick = {
                            latest?.let { program ->
                                findDownloadedFile(downloadedFiles, program)
                                    ?.let { mainViewModel.onPlayFile(it, program) }
                            }
                        },
                        onAutoDownloadChange = { enabled ->
                            if (enabled) onBeforeEnableAutoDownload()
                            favoritesViewModel.setAutoDownload(row.favorite.stationId, row.favorite.title, enabled)
                        },
                        onRemove = { favoritesViewModel.removeFavorite(row.favorite.stationId, row.favorite.title) },
                    )
                }
            }
        }
    }
}
