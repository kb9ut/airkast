package com.example.airkast

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.media3.common.util.UnstableApi
import com.example.airkast.service.HlsDownloadService
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@UnstableApi
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            AirkastTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainApp(viewModel)
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Main App Shell
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: MainViewModel) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val userMessage by viewModel.userMessage.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(userMessage) {
        userMessage?.let {
            scope.launch { snackbarHostState.showSnackbar(it) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = AirkastSurfaceElevated,
                    contentColor = AirkastTextPrimary,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        bottomBar = {
            Column {
                // ミニプレイヤー
                PlayerBar(viewModel)

                // ボトムナビゲーション
                var menuExpanded by remember { mutableStateOf(false) }
                NavigationBar(
                    containerColor = AirkastSurface,
                    contentColor = AirkastTextSecondary,
                    tonalElevation = 0.dp,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    NavigationBarItem(
                        selected = false,
                        onClick = { menuExpanded = true },
                        icon = { Icon(Icons.Default.MoreVert, contentDescription = "メニュー", modifier = Modifier.size(22.dp)) },
                        label = { Text("その他", fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            unselectedIconColor = AirkastTextTertiary,
                            unselectedTextColor = AirkastTextTertiary,
                        )
                    )
                    NavigationBarItem(
                        selected = tabIndex == 0,
                        onClick = { tabIndex = 0 },
                        icon = { Icon(Icons.Default.Search, contentDescription = "番組を探す", modifier = Modifier.size(22.dp)) },
                        label = { Text("番組を探す", fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AirkastAccent,
                            selectedTextColor = AirkastAccent,
                            unselectedIconColor = AirkastTextTertiary,
                            unselectedTextColor = AirkastTextTertiary,
                            indicatorColor = AirkastAccent.copy(alpha = 0.12f),
                        )
                    )
                    NavigationBarItem(
                        selected = tabIndex == 1,
                        onClick = { tabIndex = 1 },
                        icon = { Icon(Icons.Default.Download, contentDescription = "ダウンロード", modifier = Modifier.size(22.dp)) },
                        label = { Text("ダウンロード", fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AirkastAccent,
                            selectedTextColor = AirkastAccent,
                            unselectedIconColor = AirkastTextTertiary,
                            unselectedTextColor = AirkastTextTertiary,
                            indicatorColor = AirkastAccent.copy(alpha = 0.12f),
                        )
                    )
                }

                var showAreaDialog by remember { mutableStateOf(false) }
                var showChunkSizeDialog by remember { mutableStateOf(false) }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = AirkastSurfaceElevated,
                ) {
                    DropdownMenuItem(
                        text = { Text("エリア変更", color = AirkastTextPrimary) },
                        onClick = {
                            menuExpanded = false
                            showAreaDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("ダウンロード設定", color = AirkastTextPrimary) },
                        onClick = {
                            menuExpanded = false
                            showChunkSizeDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("ライセンス情報", color = AirkastTextPrimary) },
                        onClick = {
                            menuExpanded = false
                            context.startActivity(Intent(context, OssLicensesMenuActivity::class.java))
                        }
                    )
                }

                if (showAreaDialog) {
                    AreaSelectionDialog(
                        currentAreaId = viewModel.selectedAreaId.collectAsState().value,
                        onAreaSelected = {
                            viewModel.onAreaSelected(it)
                            showAreaDialog = false
                        },
                        onDismiss = { showAreaDialog = false }
                    )
                }

                if (showChunkSizeDialog) {
                    DownloadChunkSizeDialog(
                        currentChunkSize = viewModel.downloadChunkSize.collectAsState().value,
                        onChunkSizeSelected = { viewModel.setDownloadChunkSize(it) },
                        onDismiss = { showChunkSizeDialog = false }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            when (tabIndex) {
                0 -> FindProgramsScreen(viewModel)
                1 -> DownloadsScreen(viewModel)
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// 番組を探す画面
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun FindProgramsScreen(viewModel: MainViewModel) {
    val stationUiState by viewModel.stationUiState.collectAsState()
    val selectedStationId by viewModel.selectedStationId.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val programGuideUiState by viewModel.programGuideUiState.collectAsState()
    val dateList = viewModel.dateList

    Column(modifier = Modifier.fillMaxSize()) {
        when (val state = stationUiState) {
            is UiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = AirkastAccent,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("認証中...", color = AirkastTextSecondary, fontSize = 14.sp)
                    }
                }
            }
            is UiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "接続エラー",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = AirkastTextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            state.error.localizedMessage ?: "",
                            color = AirkastTextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.authenticateAndFetchStations() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AirkastAccent,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text("再試行", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            is UiState.Success -> {
                // 放送局セレクタ
                StationDropdown(
                    stations = state.data,
                    selectedStationId = selectedStationId,
                    onStationSelected = { viewModel.onStationSelected(it) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // 日付チップ
                if (selectedStationId != null) {
                    DateChips(
                        dateList = dateList,
                        selectedDate = selectedDate,
                        onDateSelected = { viewModel.onDateSelected(it) }
                    )
                }

                // 時間帯フィルタ
                val selectedTimeFilter by viewModel.selectedTimeFilter.collectAsState()
                TimeFilterChips(
                    selectedFilter = selectedTimeFilter,
                    onFilterSelected = { viewModel.onTimeFilterSelected(it) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 番組リスト
                when (val programState = programGuideUiState) {
                    is UiState.Loading -> {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AirkastAccent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                        }
                    }
                    is UiState.Error -> {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("エラーが発生しました", color = AirkastTextSecondary, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { viewModel.onDateSelected(selectedDate) },
                                    colors = ButtonDefaults.buttonColors(containerColor = AirkastAccent, contentColor = Color.Black),
                                    shape = RoundedCornerShape(24.dp)
                                ) { Text("再試行") }
                            }
                        }
                    }
                    is UiState.Success -> {
                        val filteredPrograms = remember(programState.data, selectedTimeFilter) {
                            programState.data.filter { program ->
                                try {
                                    val hour = program.startTime.substring(8, 10).toInt()
                                    val filterHour = if (hour < 5) hour + 24 else hour
                                    when (selectedTimeFilter) {
                                        TimeFilter.EARLY_MORNING -> filterHour in 5..8
                                        TimeFilter.DAYTIME -> filterHour in 9..16
                                        TimeFilter.NIGHT -> filterHour in 17..23
                                        TimeFilter.MIDNIGHT -> filterHour in 24..28
                                    }
                                } catch (e: Exception) { true }
                            }
                        }

                        if (filteredPrograms.isEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Text("この時間帯の番組はありません", color = AirkastTextTertiary, fontSize = 14.sp)
                            }
                        } else {
                            ProgramGuideList(
                                programs = filteredPrograms,
                                viewModel = viewModel,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    is UiState.Idle -> {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                if (selectedStationId == null) "放送局を選択してください"
                                else "日付を選択してください",
                                color = AirkastTextTertiary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
            is UiState.Idle -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = { viewModel.authenticateAndFetchStations() },
                        colors = ButtonDefaults.buttonColors(containerColor = AirkastAccent, contentColor = Color.Black),
                        shape = RoundedCornerShape(24.dp)
                    ) { Text("認証＆放送局取得", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ダウンロード画面
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun DownloadsScreen(viewModel: MainViewModel) {
    val downloadedFiles by viewModel.downloadedFiles.collectAsState()
    val ongoingDownloadStates by viewModel.ongoingDownloadStates.collectAsState()
    val ongoingTasks = ongoingDownloadStates.filterValues {
        it is HlsDownloadService.DownloadStatus.InProgress ||
        it is HlsDownloadService.DownloadStatus.Pending ||
        it is HlsDownloadService.DownloadStatus.Muxing
    }.map { it.value }

    val programMap = remember(viewModel.programGuideUiState.value) {
        val uiState = viewModel.programGuideUiState.value
        if (uiState is UiState.Success) uiState.data.associateBy { it.id }
        else emptyMap()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ダウンロード中セクション
        if (ongoingTasks.isNotEmpty()) {
            item {
                Text(
                    "ダウンロード中",
                    style = MaterialTheme.typography.titleMedium,
                    color = AirkastTextPrimary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            val tasks = ongoingDownloadStates.filterValues {
                it is HlsDownloadService.DownloadStatus.InProgress ||
                it is HlsDownloadService.DownloadStatus.Pending ||
                it is HlsDownloadService.DownloadStatus.Muxing
            }.map { (programId, status) -> programId to status }.toList()
            items(tasks) { (programId, status) ->
                DownloadProgressCard(programId, status, onCancel = { viewModel.cancelDownload(programId) })
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        // ダウンロード済みセクション
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "ダウンロード済み",
                    style = MaterialTheme.typography.titleMedium,
                    color = AirkastTextPrimary,
                )
                IconButton(
                    onClick = { viewModel.onScanForDownloads() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "更新", tint = AirkastTextSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (downloadedFiles.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = AirkastTextTertiary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("ダウンロード済みの番組はありません", color = AirkastTextTertiary, fontSize = 14.sp)
                    }
                }
            }
        } else {
            items(downloadedFiles) { file ->
                DownloadedFileCard(
                    file = file,
                    programMap = programMap,
                    onPlayClick = { f ->
                        val fileName = f.name
                        val parsed = HlsDownloadService.parseFileName(fileName)

                        val program = if (parsed != null && parsed.startTime.isNotEmpty()) {
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
                                imageUrl = null
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
                                imageUrl = null
                            )
                        }
                        viewModel.onPlayFile(f, program)
                    },
                    onDeleteClick = { viewModel.onDeleteFile(it) }
                )
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// コンポーネント: 放送局ドロップダウン
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun StationDropdown(
    stations: List<AirkastStation>,
    selectedStationId: String?,
    onStationSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedStation = stations.find { it.id == selectedStationId }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = true },
            color = AirkastSurfaceElevated,
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = selectedStation?.name ?: "放送局を選択",
                    color = if (selectedStation != null) AirkastTextPrimary else AirkastTextTertiary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                )
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = AirkastTextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f),
            containerColor = AirkastSurfaceElevated,
        ) {
            stations.forEach { station ->
                DropdownMenuItem(
                    text = {
                        Text(
                            station.name,
                            color = if (station.id == selectedStationId) AirkastAccent else AirkastTextPrimary,
                            fontWeight = if (station.id == selectedStationId) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onStationSelected(station.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// コンポーネント: 日付チップ
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun DateChips(
    dateList: List<Date>,
    selectedDate: Date,
    onDateSelected: (Date) -> Unit
) {
    val shortFormatter = SimpleDateFormat("M/d", Locale.JAPAN)
    val dayFormatter = SimpleDateFormat("E", Locale.JAPAN)
    val todayCalendar = java.util.Calendar.getInstance()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        dateList.forEach { date ->
            val isSelected = SimpleDateFormat("yyyyMMdd", Locale.JAPAN).format(date) ==
                    SimpleDateFormat("yyyyMMdd", Locale.JAPAN).format(selectedDate)
            val calendar = java.util.Calendar.getInstance().apply { time = date }
            val isToday = calendar.get(java.util.Calendar.DAY_OF_YEAR) == todayCalendar.get(java.util.Calendar.DAY_OF_YEAR) &&
                    calendar.get(java.util.Calendar.YEAR) == todayCalendar.get(java.util.Calendar.YEAR)

            val bgColor = if (isSelected) AirkastAccent else AirkastSurfaceVariant
            val textColor = if (isSelected) Color.Black else AirkastTextSecondary

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bgColor)
                    .clickable { onDateSelected(date) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (isToday) "今日" else shortFormatter.format(date),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = textColor
                )
                Text(
                    text = dayFormatter.format(date),
                    fontSize = 11.sp,
                    color = textColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// コンポーネント: 時間帯フィルタ
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun TimeFilterChips(
    selectedFilter: TimeFilter,
    onFilterSelected: (TimeFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TimeFilter.values().forEach { filter ->
            val isSelected = filter == selectedFilter
            val bgColor = if (isSelected) AirkastAccent.copy(alpha = 0.15f) else AirkastSurfaceVariant
            val textColor = if (isSelected) AirkastAccent else AirkastTextSecondary

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bgColor)
                    .clickable { onFilterSelected(filter) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val (labelMain, labelSub) = when (filter) {
                    TimeFilter.EARLY_MORNING -> "早朝" to "05-09"
                    TimeFilter.DAYTIME -> "昼" to "09-17"
                    TimeFilter.NIGHT -> "夜" to "17-24"
                    TimeFilter.MIDNIGHT -> "深夜" to "24-29"
                }
                Text(text = labelMain, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textColor)
                Text(text = labelSub, fontSize = 11.sp, color = textColor.copy(alpha = 0.7f))
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// コンポーネント: 番組リスト（カードUI）
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun ProgramGuideList(
    programs: List<AirkastProgram>,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val ongoingDownloadStates by viewModel.ongoingDownloadStates.collectAsState()
    val downloadedFiles by viewModel.downloadedFiles.collectAsState()
    val chunkSize by viewModel.downloadChunkSize.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(programs) { program ->
            ProgramCard(
                program = program,
                ongoingDownloadStates = ongoingDownloadStates,
                downloadedFiles = downloadedFiles,
                onDownloadClick = { viewModel.onDownloadProgram(program, chunkSize) }
            )
        }
    }
}

@Composable
fun ProgramCard(
    program: AirkastProgram,
    ongoingDownloadStates: Map<String, HlsDownloadService.DownloadStatus>,
    downloadedFiles: List<File>,
    onDownloadClick: () -> Unit
) {
    val isDownloading = ongoingDownloadStates.containsKey(program.id)
    val isDownloaded = downloadedFiles.any { it.name.contains(program.id) || it.name.contains(program.startTime) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AirkastSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // 左アクセントバー
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(
                        if (program.isDownloadable) AirkastAccent
                        else AirkastTextTertiary.copy(alpha = 0.3f)
                    )
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 番組情報
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = program.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = AirkastTextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = program.formattedDisplayTime,
                            fontSize = 13.sp,
                            color = AirkastAccent,
                            fontWeight = FontWeight.Medium,
                        )
                        if (program.performer.isNotEmpty()) {
                            Text("  ·  ", fontSize = 13.sp, color = AirkastTextTertiary)
                            Text(
                                text = program.performer,
                                fontSize = 12.sp,
                                color = AirkastTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                }

                // ダウンロードボタン（1タップで即開始）
                if (program.isDownloadable) {
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isDownloading) {
                        CircularProgressIndicator(
                            color = AirkastAccent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        IconButton(
                            onClick = { if (!isDownloaded) onDownloadClick() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "ダウンロード",
                                tint = if (isDownloaded) AirkastAccent else AirkastTextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// コンポーネント: ダウンロード進捗カード
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun DownloadProgressCard(
    programId: String,
    status: HlsDownloadService.DownloadStatus,
    onCancel: () -> Unit
) {
    Surface(
        color = AirkastSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        status.program.title,
                        maxLines = 1,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = AirkastTextPrimary,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val statusText = when (status) {
                        is HlsDownloadService.DownloadStatus.Pending -> "待機中"
                        is HlsDownloadService.DownloadStatus.InProgress -> "ダウンロード中: ${status.progress}%"
                        is HlsDownloadService.DownloadStatus.Muxing -> "変換中: ${status.progress}%"
                        else -> ""
                    }
                    Text(statusText, fontSize = 12.sp, color = AirkastTextTertiary)
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "中止", tint = AirkastError, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val progress = when (status) {
                is HlsDownloadService.DownloadStatus.InProgress -> status.progress.toFloat()
                is HlsDownloadService.DownloadStatus.Muxing -> status.progress.toFloat()
                else -> 0f
            }
            val progressColor = if (status is HlsDownloadService.DownloadStatus.Muxing) {
                Color(0xFFFFAB40)
            } else {
                AirkastAccent
            }
            val animatedProgress by animateFloatAsState(
                targetValue = progress / 100f,
                animationSpec = tween(300),
                label = "progress"
            )

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = progressColor,
                trackColor = AirkastSurfaceVariant,
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// コンポーネント: ダウンロード済みファイルカード
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun DownloadedFileCard(
    file: File,
    programMap: Map<String, AirkastProgram>,
    onPlayClick: (File) -> Unit,
    onDeleteClick: (File) -> Unit
) {
    val fileName = file.name
    val parsed = HlsDownloadService.parseFileName(fileName)

    val title = parsed?.title ?: fileName.substringBeforeLast(".")
    val subInfo = if (parsed != null && parsed.startTime.isNotEmpty()) {
        val s = parsed.startTime
        val dateStr = "${s.substring(4, 6)}/${s.substring(6, 8)} ${s.substring(8, 10)}:${s.substring(10, 12)}"
        if (parsed.stationId.isNotEmpty()) "${parsed.stationId}  ·  $dateStr" else dateStr
    } else ""

    Surface(
        color = AirkastSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPlayClick(file) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 再生アイコン
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AirkastAccent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = AirkastAccent,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = AirkastTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subInfo.isNotEmpty()) {
                    Text(subInfo, fontSize = 12.sp, color = AirkastTextTertiary)
                }
            }

            IconButton(
                onClick = { onDeleteClick(file) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "削除",
                    tint = AirkastTextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// コンポーネント: プレイヤーバー（没入感のある大型プレイヤー）
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun PlayerBar(viewModel: MainViewModel) {
    val mediaMetadata by viewModel.mediaMetadata.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val position by viewModel.playbackPosition.collectAsState()
    val duration by viewModel.playbackDuration.collectAsState()
    val speed by viewModel.playbackSpeed.collectAsState()

    if (mediaMetadata == null && duration == 0L) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            AirkastSurface.copy(alpha = 0.95f),
                            AirkastBlack,
                        )
                    ),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                )
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .fillMaxWidth()
            ) {
                // 番組情報
                Text(
                    text = mediaMetadata?.title?.toString() ?: "再生中...",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = AirkastTextPrimary,
                )
                val subText = mediaMetadata?.artist?.toString() ?: ""
                if (subText.isNotEmpty()) {
                    Text(
                        text = subText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = AirkastAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // シークバー（カスタムスタイル）
                val animatedPosition by animateFloatAsState(
                    targetValue = if (duration > 0) position.toFloat() else 0f,
                    animationSpec = tween(300),
                    label = "seekbar"
                )
                Slider(
                    value = animatedPosition,
                    onValueChange = { viewModel.seekTo(it.toLong()) },
                    valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = AirkastAccent,
                        activeTrackColor = AirkastAccent,
                        inactiveTrackColor = AirkastSurfaceVariant,
                    ),
                )

                // 時間表示
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(position), fontSize = 11.sp, color = AirkastTextTertiary)
                    Text(formatTime(duration), fontSize = 11.sp, color = AirkastTextTertiary)
                }

                Spacer(modifier = Modifier.height(4.dp))

                // コントロール
                val turboSpeed by viewModel.turboSpeed.collectAsState()
                val isTurboActive by viewModel.isTurboActive.collectAsState()
                var showTurboDialog by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 速度セレクタ
                    var speedMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { speedMenuExpanded = true },
                            color = AirkastSurfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = "${speed}x",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AirkastTextSecondary,
                            )
                        }
                        DropdownMenu(
                            expanded = speedMenuExpanded,
                            onDismissRequest = { speedMenuExpanded = false },
                            containerColor = AirkastSurfaceElevated,
                        ) {
                            listOf(0.5f, 0.8f, 1.0f, 1.25f, 1.5f, 1.8f, 2.0f).forEach { s ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${s}x",
                                            color = if (s == speed) AirkastAccent else AirkastTextPrimary,
                                            fontWeight = if (s == speed) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        viewModel.setPlaybackSpeed(s)
                                        speedMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // スキップ + 再生/停止ボタン
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // 60秒戻し
                        SkipButton(onClick = { viewModel.skipBackward(60) }, label = "60", isForward = false)
                        // 30秒戻し
                        SkipButton(onClick = { viewModel.skipBackward(30) }, label = "30", isForward = false)

                        // 再生/停止ボタン（大型）
                        Spacer(modifier = Modifier.width(4.dp))
                        FilledIconButton(
                            onClick = { viewModel.playPause() },
                            modifier = Modifier.size(52.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = AirkastAccent,
                                contentColor = Color.Black,
                            ),
                            shape = CircleShape,
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "停止" else "再生",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))

                        // 60秒送り
                        SkipButton(onClick = { viewModel.skipForward(60) }, label = "60", isForward = true)
                        // 120秒送り
                        SkipButton(onClick = { viewModel.skipForward(120) }, label = "2m", isForward = true)
                    }

                    // ターボボタン (長押しでN倍速、タップで倍速設定)
                    TurboButton(
                        turboSpeed = turboSpeed,
                        isTurboActive = isTurboActive,
                        onTurboStart = { viewModel.startTurbo() },
                        onTurboStop = { viewModel.stopTurbo() },
                        onTap = { showTurboDialog = true },
                    )
                }

                // ターボ倍速設定ダイアログ
                if (showTurboDialog) {
                    TurboSpeedDialog(
                        currentSpeed = turboSpeed,
                        onSpeedSelected = { viewModel.setTurboSpeed(it) },
                        onDismiss = { showTurboDialog = false }
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// コンポーネント: スキップボタン
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun SkipButton(
    onClick: () -> Unit,
    label: String,
    isForward: Boolean,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isForward)
                    Icons.AutoMirrored.Filled.Redo
                else
                    Icons.AutoMirrored.Filled.Undo,
                contentDescription = if (isForward) "${label}送る" else "${label}戻る",
                modifier = Modifier.size(18.dp),
                tint = AirkastTextPrimary,
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 10.sp,
                color = AirkastTextSecondary,
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// コンポーネント: ターボボタン（長押しでN倍速、タップで設定）
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun TurboButton(
    turboSpeed: Float,
    isTurboActive: Boolean,
    onTurboStart: () -> Unit,
    onTurboStop: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isTurboActive) AirkastAccent else AirkastSurfaceVariant
    val contentColor = if (isTurboActive) Color.Black else AirkastTextSecondary
    val label = "${turboSpeed.toInt()}x"

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var turboStarted = false
                    // 200ms後にターボ開始するジョブ
                    val turboJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        kotlinx.coroutines.delay(200)
                        turboStarted = true
                        onTurboStart()
                    }
                    // 指が離れるかキャンセルされるまで待つ
                    val up = waitForUpOrCancellation()
                    if (turboStarted) {
                        // 長押しだった → ターボ停止
                        onTurboStop()
                    } else {
                        // 200ms以内にリリース → タップ（設定画面）
                        turboJob.cancel()
                        if (up != null) {
                            up.consume()
                            onTap()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.FastForward,
                contentDescription = "ターボ (長押し)",
                modifier = Modifier.size(16.dp),
                tint = contentColor,
            )
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 9.sp,
                color = contentColor,
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ダイアログ: ターボ倍速設定
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun TurboSpeedDialog(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(currentSpeed) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AirkastSurfaceElevated,
        titleContentColor = AirkastTextPrimary,
        title = { Text("ターボ倍速設定", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "長押し中の再生速度を設定します。\nCMスキップなどに便利です。",
                    color = AirkastTextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Spacer(modifier = Modifier.height(20.dp))

                // 現在値の大きな表示
                Text(
                    text = "${sliderValue.toInt()}x",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = AirkastAccent,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // スライダー
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 10f..200f,
                    steps = 18, // 10刻み: 10,20,30,...,200 → 19段階 → steps = 18
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = AirkastAccent,
                        activeTrackColor = AirkastAccent,
                        inactiveTrackColor = AirkastSurfaceVariant,
                    ),
                )

                // 範囲ラベル
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("10x", fontSize = 11.sp, color = AirkastTextTertiary)
                    Text("200x", fontSize = 11.sp, color = AirkastTextTertiary)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // プリセット
                Text("プリセット:", fontSize = 12.sp, color = AirkastTextTertiary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(10f, 30f, 50f, 100f, 200f).forEach { preset ->
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { sliderValue = preset },
                            color = if (sliderValue == preset) AirkastAccent.copy(alpha = 0.2f) else AirkastSurfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = "${preset.toInt()}x",
                                modifier = Modifier.padding(vertical = 8.dp),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (sliderValue == preset) AirkastAccent else AirkastTextSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSpeedSelected(sliderValue)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AirkastAccent, contentColor = Color.Black),
                shape = RoundedCornerShape(24.dp),
            ) { Text("保存", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル", color = AirkastTextSecondary)
            }
        }
    )
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ダイアログ: ダウンロード分割サイズ設定
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun DownloadChunkSizeDialog(
    currentChunkSize: Int,
    onChunkSizeSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        15 to "安全 (15秒)",
        60 to "推奨 (60秒)",
        300 to "高速 (5分)",
        7200 to "一括 (試験的)"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AirkastSurfaceElevated,
        titleContentColor = AirkastTextPrimary,
        textContentColor = AirkastTextSecondary,
        title = { Text("ダウンロード設定", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "ダウンロード時の分割サイズを選択してください。",
                    color = AirkastTextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))

                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onChunkSizeSelected(value)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (currentChunkSize == value),
                            onClick = {
                                onChunkSizeSelected(value)
                                onDismiss()
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = AirkastAccent,
                                unselectedColor = AirkastTextTertiary,
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = label,
                            color = if (currentChunkSize == value) AirkastAccent else AirkastTextPrimary,
                            fontWeight = if (currentChunkSize == value) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる", color = AirkastTextSecondary)
            }
        }
    )
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ダイアログ: エリア選択
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun AreaSelectionDialog(
    currentAreaId: String,
    onAreaSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val areas = listOf(
        "JP13" to "東京",
        "JP27" to "大阪",
        "JP25" to "滋賀",
        "JP24" to "三重",
        "JP36" to "徳島",
        "JP01" to "北海道",
        "JP04" to "宮城",
        "JP23" to "愛知",
        "JP26" to "京都",
        "JP28" to "兵庫",
        "JP34" to "広島",
        "JP40" to "福岡"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AirkastSurfaceElevated,
        titleContentColor = AirkastTextPrimary,
        title = { Text("エリア変更", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "※ IPアドレスが一致しないエリアの番組は再生できない場合があります",
                    fontSize = 12.sp,
                    color = AirkastError,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                LazyColumn {
                    items(areas) { (areaId, areaName) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onAreaSelected(areaId) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (currentAreaId == areaId),
                                onClick = { onAreaSelected(areaId) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AirkastAccent,
                                    unselectedColor = AirkastTextTertiary,
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "$areaName ($areaId)",
                                color = if (currentAreaId == areaId) AirkastAccent else AirkastTextPrimary,
                                fontWeight = if (currentAreaId == areaId) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる", color = AirkastTextSecondary)
            }
        }
    )
}
