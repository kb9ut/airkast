package com.example.airkast.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.airkast.AirkastProgram
import com.example.airkast.MainViewModel
import com.example.airkast.R
import com.example.airkast.TimeFilter
import com.example.airkast.UiState
import com.example.airkast.favorites.FavoritesViewModel
import com.example.airkast.favorites.favoriteKeyOf
import com.example.airkast.isProgramDownloaded
import com.example.airkast.ui.components.DateSelector
import com.example.airkast.ui.components.LoadingMessage
import com.example.airkast.ui.components.ProgramRow
import com.example.airkast.ui.components.StateMessage
import com.example.airkast.ui.components.StationSelector
import com.example.airkast.ui.components.TimeFilterChips
import com.example.airkast.ui.resolveProgramDownloadState
import com.example.airkast.ui.theme.AirkastMotion
import com.example.airkast.ui.theme.Spacing
import com.example.airkast.ui.theme.groupedItemShape

/**
 * 番組を探す画面。
 * [contentBottomPadding] はミニプレイヤーが重なる高さ(リスト末尾が隠れないための余白)。
 */
@Composable
fun FindProgramsScreen(
    viewModel: MainViewModel,
    favoritesViewModel: FavoritesViewModel,
    contentBottomPadding: Dp,
    onBeforeEnableAutoDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stationUiState by viewModel.stationUiState.collectAsState()
    val selectedStationId by viewModel.selectedStationId.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val programGuideUiState by viewModel.programGuideUiState.collectAsState()
    val dateList by viewModel.dateList.collectAsState()
    val selectedTimeFilter by viewModel.selectedTimeFilter.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        when (val state = stationUiState) {
            is UiState.Loading -> LoadingMessage(label = stringResource(R.string.auth_loading))

            is UiState.Error -> StateMessage(
                icon = Icons.Outlined.CloudOff,
                title = stringResource(R.string.connect_error_title),
                body = state.error.localizedMessage ?: stringResource(R.string.connect_error_fallback),
                actionLabel = stringResource(R.string.retry),
                onAction = { viewModel.authenticateAndFetchStations() },
                isError = true,
                modifier = Modifier.fillMaxSize(),
            )

            is UiState.Idle -> StateMessage(
                icon = Icons.Outlined.Radio,
                title = stringResource(R.string.auth_idle_title),
                actionLabel = stringResource(R.string.auth_and_fetch),
                onAction = { viewModel.authenticateAndFetchStations() },
                modifier = Modifier.fillMaxSize(),
            )

            is UiState.Success -> {
                StationSelector(
                    stations = state.data,
                    selectedStationId = selectedStationId,
                    onStationSelected = { viewModel.onStationSelected(it) },
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
                )
                Spacer(Modifier.height(Spacing.s))

                if (selectedStationId != null) {
                    DateSelector(
                        dateList = dateList,
                        selectedDate = selectedDate,
                        onDateSelected = { viewModel.onDateSelected(it) },
                    )
                    Spacer(Modifier.height(Spacing.s))
                }

                TimeFilterChips(
                    selectedFilter = selectedTimeFilter,
                    onFilterSelected = { viewModel.onTimeFilterSelected(it) },
                )
                Spacer(Modifier.height(Spacing.s))

                // 状態の種類が変わるときだけクロスフェード(番組データ更新では再生しない)
                AnimatedContent(
                    targetState = programGuideUiState,
                    modifier = Modifier.fillMaxSize(),
                    contentKey = { it::class },
                    transitionSpec = {
                        fadeIn(AirkastMotion.effects()) togetherWith fadeOut(AirkastMotion.effects())
                    },
                    label = "programGuide",
                ) { programState ->
                    when (programState) {
                        is UiState.Loading -> LoadingMessage(label = null)

                        is UiState.Error -> StateMessage(
                            icon = Icons.Outlined.ErrorOutline,
                            title = stringResource(R.string.program_error),
                            actionLabel = stringResource(R.string.retry),
                            onAction = { viewModel.onDateSelected(selectedDate) },
                            isError = true,
                            modifier = Modifier.fillMaxSize(),
                        )

                        is UiState.Success -> {
                            val filtered = remember(programState.data, selectedTimeFilter) {
                                filterByTime(programState.data, selectedTimeFilter)
                            }
                            if (filtered.isEmpty()) {
                                StateMessage(
                                    icon = Icons.Outlined.SearchOff,
                                    title = stringResource(R.string.no_programs),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                ProgramGuideList(
                                    programs = filtered,
                                    viewModel = viewModel,
                                    favoritesViewModel = favoritesViewModel,
                                    contentBottomPadding = contentBottomPadding,
                                    onBeforeEnableAutoDownload = onBeforeEnableAutoDownload,
                                )
                            }
                        }

                        is UiState.Idle -> StateMessage(
                            icon = Icons.Outlined.TouchApp,
                            title = stringResource(
                                if (selectedStationId == null) R.string.hint_select_station
                                else R.string.hint_select_date,
                            ),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/** 時間帯フィルタ(24時以降の深夜帯は 24〜28 として扱う)。 */
private fun filterByTime(programs: List<AirkastProgram>, filter: TimeFilter): List<AirkastProgram> =
    programs.filter { program ->
        try {
            val hour = program.startTime.substring(8, 10).toInt()
            val filterHour = if (hour < 5) hour + 24 else hour
            when (filter) {
                TimeFilter.EARLY_MORNING -> filterHour in 5..8
                TimeFilter.DAYTIME -> filterHour in 9..16
                TimeFilter.NIGHT -> filterHour in 17..23
                TimeFilter.MIDNIGHT -> filterHour in 24..28
            }
        } catch (e: Exception) {
            true
        }
    }

@Composable
private fun ProgramGuideList(
    programs: List<AirkastProgram>,
    viewModel: MainViewModel,
    favoritesViewModel: FavoritesViewModel,
    contentBottomPadding: Dp,
    onBeforeEnableAutoDownload: () -> Unit,
) {
    val favoriteKeys by favoritesViewModel.favoriteKeys.collectAsState()
    val ongoingDownloadStates by viewModel.ongoingDownloadStates.collectAsState()
    val downloadedFiles by viewModel.downloadedFiles.collectAsState()
    val chunkSize by viewModel.downloadChunkSize.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = Spacing.l,
            end = Spacing.l,
            top = Spacing.xs,
            bottom = contentBottomPadding + Spacing.l,
        ),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(programs) { index, program ->
            // ボタンは state だけから描画される。状態の決め方は resolveProgramDownloadState に集約。
            val state = resolveProgramDownloadState(
                status = ongoingDownloadStates[program.id],
                isFileDownloaded = isProgramDownloaded(downloadedFiles, program),
            )
            ProgramRow(
                program = program,
                downloadState = state,
                shape = groupedItemShape(index, programs.size),
                onDownloadClick = { viewModel.onDownloadProgram(program, chunkSize) },
                isFavorite = favoriteKeyOf(program.stationId, program.title) in favoriteKeys,
                onToggleFavorite = {
                    val adding = favoriteKeyOf(program.stationId, program.title) !in favoriteKeys
                    // 自動DL が ON になる操作(追加)の直前にだけ通知権限を確認する
                    if (adding) onBeforeEnableAutoDownload()
                    favoritesViewModel.toggleFavorite(program.stationId, program.title)
                },
            )
        }
    }
}
