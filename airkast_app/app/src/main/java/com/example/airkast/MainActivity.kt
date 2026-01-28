package com.example.airkast

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.media3.common.util.UnstableApi
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import com.example.airkast.service.HlsDownloadService
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@UnstableApi
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AirkastTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainApp(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: MainViewModel) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("番組を探す", "ダウンロード")
    val snackbarHostState = remember { SnackbarHostState() }
    val userMessage by viewModel.userMessage.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(userMessage) {
        userMessage?.let {
            scope.launch {
                snackbarHostState.showSnackbar(it)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            // ステータスバーの背景のみ
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(MaterialTheme.colorScheme.primary)
            )
        },
        bottomBar = {
            Column {
                // PlayerBar（再生コントロール）
                PlayerBar(viewModel)
                
                HorizontalDivider()
                
                var menuExpanded by remember { mutableStateOf(false) }
                
                // ボトムナビゲーション（Spotifyスタイル）
                NavigationBar(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    // メニューアイコン（その他）を最初に配置
                    NavigationBarItem(
                        selected = false,
                        onClick = { menuExpanded = true },
                        icon = {
                            Icon(Icons.Default.MoreVert, contentDescription = "メニュー")
                        },
                        label = { Text("その他") }
                    )
                    
                    // タブ（番組を探す、ダウンロード）
                    tabs.forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = tabIndex == index,
                            onClick = { tabIndex = index },
                            icon = {
                                Icon(
                                    imageVector = if (index == 0) 
                                        Icons.Default.Search 
                                    else 
                                        Icons.Default.Download,
                                    contentDescription = title
                                )
                            },
                            label = { Text(title) }
                        )
                    }
                }
                
                var showAreaDialog by remember { mutableStateOf(false) }
                
                // ドロップダウンメニュー
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("エリア変更") },
                        onClick = {
                            menuExpanded = false
                            showAreaDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("ライセンス情報") },
                        onClick = {
                            menuExpanded = false
                            context.startActivity(Intent(context, OssLicensesMenuActivity::class.java))
                        }
                    )
                }
                
                // エリア選択ダイアログ
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
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tabIndex) {
                0 -> FindProgramsScreen(viewModel)
                1 -> DownloadsScreen(viewModel)
            }
        }
    }
}

@Composable
fun FindProgramsScreen(viewModel: MainViewModel) {
    val stationUiState by viewModel.stationUiState.collectAsState()
    val selectedStationId by viewModel.selectedStationId.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val programGuideUiState by viewModel.programGuideUiState.collectAsState()
    val dateList = viewModel.dateList

    Column(modifier = Modifier.fillMaxSize()) {
        // ヘッダー：放送局と日付選択（コンパクトに上部に配置）
        when (val state = stationUiState) {
            is UiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("認証中...")
                    }
                }
            }
            is UiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("エラー: ${state.error.localizedMessage}")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { viewModel.authenticateAndFetchStations() }) {
                            Text("再試行")
                        }
                    }
                }
            }
            is UiState.Success -> {
                // 放送局ドロップダウン
                StationDropdown(
                    stations = state.data,
                    selectedStationId = selectedStationId,
                    onStationSelected = { viewModel.onStationSelected(it) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // 日付チップ（横スクロール）
                if (selectedStationId != null) {
                    DateChips(
                        dateList = dateList,
                        selectedDate = selectedDate,
                        onDateSelected = { viewModel.onDateSelected(it) }
                    )
                }

                HorizontalDivider()

                // Time Filter Chips
                val selectedTimeFilter by viewModel.selectedTimeFilter.collectAsState()
                TimeFilterChips(
                    selectedFilter = selectedTimeFilter,
                    onFilterSelected = { viewModel.onTimeFilterSelected(it) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                // Program List
                when (val programState = programGuideUiState) {
                    is UiState.Loading -> {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is UiState.Error -> {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("エラーが発生しました: ${programState.error.localizedMessage}")
                                Button(onClick = { viewModel.onDateSelected(selectedDate) }) {
                                    Text("再試行")
                                }
                            }
                        }
                    }
                    is UiState.Success -> {
                        // Filter programs based on selectedTimeFilter
                        val filteredPrograms = remember(programState.data, selectedTimeFilter) {
                            programState.data.filter { program ->
                                try {
                                    val hour = program.startTime.substring(8, 10).toInt()
                                    // 深夜（24-29）はRadikoデータでは翌日の0-5時だが、前日の番組表としては24-29として扱うのが自然
                                    // ただしprogram.startTimeは実時間なので00-05となる
                                    val filterHour = if(hour < 5) hour + 24 else hour 
                                    
                                    when (selectedTimeFilter) {
                                        TimeFilter.EARLY_MORNING -> filterHour in 5..8
                                        TimeFilter.DAYTIME -> filterHour in 9..16
                                        TimeFilter.NIGHT -> filterHour in 17..23
                                        TimeFilter.MIDNIGHT -> filterHour in 24..28 // 00:00 - 05:00
                                    }
                                } catch (e: Exception) {
                                    true
                                }
                            }
                        }

                        if (filteredPrograms.isEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Text("この時間帯の番組はありません")
                            }
                        } else {
                            ProgramGuideList(
                                programs = filteredPrograms,
                                onDownloadClick = { program, chunkSize -> viewModel.onDownloadProgram(program, chunkSize) }
                            )
                        }
                    }
                    is UiState.Idle -> {
                         Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                if (selectedStationId == null) "放送局を選択してください" 
                                else "日付を選択してください"
                            )
                        }
                    }
                }
            }
            is UiState.Idle -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { viewModel.authenticateAndFetchStations() }) {
                        Text("認証＆放送局取得")
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadsScreen(viewModel: MainViewModel) {
    val downloadedFiles by viewModel.downloadedFiles.collectAsState()
    val ongoingDownloadStates by viewModel.ongoingDownloadStates.collectAsState()
    val ongoingTasks = ongoingDownloadStates.filterValues { 
        it is HlsDownloadService.DownloadStatus.InProgress || 
        it is HlsDownloadService.DownloadStatus.Pending ||
        it is HlsDownloadService.DownloadStatus.Muxing
    }.map { it.value }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val programMap = remember(viewModel.programGuideUiState.value) {
        val uiState = viewModel.programGuideUiState.value
        if (uiState is UiState.Success) {
            uiState.data.associateBy { it.id }
        }
        else {
            emptyMap()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (ongoingTasks.isNotEmpty()) {
            Text("ダウンロード中", fontWeight = FontWeight.Bold)
            DownloadQueueList(
                tasks = ongoingDownloadStates.filterValues { 
                    it is HlsDownloadService.DownloadStatus.InProgress || 
                    it is HlsDownloadService.DownloadStatus.Pending ||
                    it is HlsDownloadService.DownloadStatus.Muxing
                }.map { (programId, status) -> programId to status }.toList(),
                onCancelClick = { programId -> viewModel.cancelDownload(programId) }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text("ダウンロード済み", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { viewModel.onScanForDownloads() }) { Text("リストを更新") }
        Spacer(modifier = Modifier.height(8.dp))

        if (downloadedFiles.isNotEmpty()) {
            DownloadedFileList(
                files = downloadedFiles,
                onPlayClick = { file ->
                    val fileName = file.name
                    val parsed = HlsDownloadService.parseFileName(fileName)
                    
                    val program = if (parsed != null && parsed.startTime.isNotEmpty()) {
                        val startTime = parsed.startTime
                        val endTime = parsed.endTime
                        val title = parsed.title
                        
                        // 放送日をフォーマット
                        val dateStr = try {
                            val year = startTime.substring(0, 4)
                            val month = startTime.substring(4, 6)
                            val day = startTime.substring(6, 8)
                            val hour = startTime.substring(8, 10)
                            val minute = startTime.substring(10, 12)
                            "$year/$month/$day $hour:$minute"
                        } catch (e: Exception) { "" }
                        
                        val s = parsed.startTime
                        val e = parsed.endTime
                        val hour = if (s.length >= 10) s.substring(8, 10) else ""
                        val minute = if (s.length >= 12) s.substring(10, 12) else ""
                        val endHour = if (e.length >= 10) e.substring(8, 10) else ""
                        val endMinute = if (e.length >= 12) e.substring(10, 12) else ""

                        AirkastProgram(
                            id = fileName,
                            stationId = parsed.stationId,
                            title = title,
                            description = "",
                            performer = "${parsed.stationId} | ${s.substring(4, 6)}/${s.substring(6, 8)} | $hour:$minute - $endHour:$endMinute",
                            startTime = startTime,
                            endTime = endTime,
                            imageUrl = null
                        )
                    } else {
                        // 旧形式またはパースできない場合
                        val programId = fileName.substringBefore("_")
                        // タイトル部分を抽出（IDの後ろから拡張子の前まで）
                        val titlePart = fileName.substringAfter("_").substringBeforeLast(".")
                        
                        programMap[programId] ?: AirkastProgram(
                            id = programId,
                            stationId = "",
                            title = titlePart,
                            description = "",
                            performer = "", // 古いファイルは情報がないので空に
                            startTime = "",
                            endTime = "",
                            imageUrl = null
                        )
                    }

                    viewModel.onPlayFile(file, program)
                },
                onDeleteClick = { viewModel.onDeleteFile(it) }
            )
        } else {
            Text("ダウンロード済みのファイルはありません。")
        }
    }
}

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
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = selectedStation?.name ?: "放送局を選択",
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.MoreVert, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            stations.forEach { station ->
                DropdownMenuItem(
                    text = { Text(station.name) },
                    onClick = {
                        onStationSelected(station.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun DateChips(
    dateList: List<Date>,
    selectedDate: Date,
    onDateSelected: (Date) -> Unit
) {
    val shortFormatter = SimpleDateFormat("M/d", Locale.JAPAN)
    val dayFormatter = SimpleDateFormat("E", Locale.JAPAN)
    val todayCalendar = java.util.Calendar.getInstance()
    
    // スライドではなく固定幅で表示 (Row + weight)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        dateList.forEach { date ->
            val isSelected = SimpleDateFormat("yyyyMMdd", Locale.JAPAN).format(date) ==
                    SimpleDateFormat("yyyyMMdd", Locale.JAPAN).format(selectedDate)
            val calendar = java.util.Calendar.getInstance().apply { time = date }
            val isToday = calendar.get(java.util.Calendar.DAY_OF_YEAR) == todayCalendar.get(java.util.Calendar.DAY_OF_YEAR) &&
                    calendar.get(java.util.Calendar.YEAR) == todayCalendar.get(java.util.Calendar.YEAR)
            
            val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            val contentColor = if(isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(backgroundColor)
                    .clickable { onDateSelected(date) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                 Text(
                     text = if (isToday) "今日" else shortFormatter.format(date),
                     fontSize = 14.sp,
                     fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                     color = contentColor
                 )
                 Text(
                     text = dayFormatter.format(date),
                     fontSize = 12.sp,
                     color = contentColor
                 )
            }
        }
    }
}

// 古いセレクター（後方互換性のために残す、未使用）
@Composable
fun StationSelector(stations: List<AirkastStation>, selectedStationId: String?, onStationSelected: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(stations) { station ->
            Button(
                onClick = { onStationSelected(station.id) },
                colors = if (station.id == selectedStationId) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
            ) { Text(station.name) }
        }
    }
}

@Composable
fun DateSelector(dateList: List<Date>, selectedDate: Date, onDateSelected: (Date) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormatter = SimpleDateFormat("yyyy年MM月dd日 (E)", Locale.JAPAN)
    Box {
        Button(onClick = { expanded = true }) { Text(dateFormatter.format(selectedDate)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            dateList.forEach { date ->
                DropdownMenuItem(text = { Text(dateFormatter.format(date)) }, onClick = {
                    onDateSelected(date)
                    expanded = false
                })
            }
        }
    }
}

@Composable
fun ProgramGuideList(
    programs: List<AirkastProgram>, 
    onDownloadClick: (AirkastProgram, Int) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(programs) { program ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(program.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("時間: ${program.formattedDisplayTime}", fontSize = 14.sp)
                Text("出演者: ${program.performer}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (program.isDownloadable) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.align(Alignment.End)) {
                        Button(onClick = { onDownloadClick(program, 60) }) {
                            Text("ダウンロード")
                        }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun DownloadQueueList(tasks: List<Pair<String, HlsDownloadService.DownloadStatus>>, onCancelClick: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tasks) { (programId, status) ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(status.program.title, maxLines = 1, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        val statusText = when (status) {
                            is HlsDownloadService.DownloadStatus.Pending -> "待機中"
                            is HlsDownloadService.DownloadStatus.InProgress -> "ダウンロード中: ${status.progress}%"
                            is HlsDownloadService.DownloadStatus.Muxing -> "変換中 (Muxing): ${status.progress}%"
                            else -> ""
                        }
                        Text(statusText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onCancelClick(programId) }) {
                        Text("中止", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val progress = when (status) {
                        is HlsDownloadService.DownloadStatus.InProgress -> status.progress.toFloat()
                        is HlsDownloadService.DownloadStatus.Muxing -> status.progress.toFloat()
                        else -> 0f
                    }
                    
                    val progressColor = if (status is HlsDownloadService.DownloadStatus.Muxing) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                    
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.weight(1f),
                        color = progressColor
                    )
                }
            }
        }
    }
}

 @Composable
fun DownloadedFileList(files: List<File>, onPlayClick: (File) -> Unit, onDeleteClick: (File) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(files) { file ->
            val fileName = file.name
            val parsed = HlsDownloadService.parseFileName(fileName)
            
            val title = parsed?.title ?: fileName.substringBeforeLast(".")
            val subInfo = if (parsed != null && parsed.startTime.isNotEmpty()) {
                val s = parsed.startTime
                val dateStr = "${s.substring(4, 6)}/${s.substring(6, 8)} ${s.substring(8, 10)}:${s.substring(10, 12)}"
                if (parsed.stationId.isNotEmpty()) "${parsed.stationId} | $dateStr" else dateStr
            } else ""

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                    if (subInfo.isNotEmpty()) {
                        Text(subInfo, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { onPlayClick(file) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("再生", fontSize = 13.sp) }
                    Button(onClick = { onDeleteClick(file) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("削除", fontSize = 13.sp) }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp), thickness = 0.5.dp)
        }
    }
}



@Composable
fun TimeFilterChips(
    selectedFilter: TimeFilter,
    onFilterSelected: (TimeFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    // スライドではなく固定幅で表示
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TimeFilter.values().forEach { filter ->
            val isSelected = filter == selectedFilter
            val backgroundColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            val contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(backgroundColor)
                    .clickable { onFilterSelected(filter) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val (labelMain, labelSub) = when(filter) {
                    TimeFilter.EARLY_MORNING -> "早朝" to "05-09"
                    TimeFilter.DAYTIME -> "昼" to "09-17"
                    TimeFilter.NIGHT -> "夜" to "17-24"
                    TimeFilter.MIDNIGHT -> "深夜" to "24-29"
                }
                
                Text(text = labelMain, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = contentColor)
                Text(text = labelSub, fontSize = 12.sp, color = contentColor)
            }
        }
    }
}

@Composable
fun PlayerBar(viewModel: MainViewModel) {
    val mediaMetadata by viewModel.mediaMetadata.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val position by viewModel.playbackPosition.collectAsState()
    val duration by viewModel.playbackDuration.collectAsState()
    val speed by viewModel.playbackSpeed.collectAsState()

    // メディアがセットされていない、または duration が 0 の場合は表示しない
    if (mediaMetadata == null && duration == 0L) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
        ) {
             // Program Info Area
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text(
                    text = mediaMetadata?.title?.toString() ?: "再生中...",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(2.dp))
                // Station | Date | TimeRange
                val subText = mediaMetadata?.artist?.toString() ?: ""
                if (subText.isNotEmpty()) {
                    Text(
                        text = subText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }

            // Seekbar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTime(position),
                    fontSize = 10.sp,
                    modifier = Modifier.width(40.dp)
                )
                Slider(
                    value = if (duration > 0) position.toFloat() else 0f,
                    onValueChange = { viewModel.seekTo(it.toLong()) },
                    valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatTime(duration),
                    fontSize = 10.sp,
                    modifier = Modifier.width(40.dp)
                )
            }

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Speed Selector
                var speedMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { speedMenuExpanded = true }) {
                        Text("${speed}x")
                    }
                    DropdownMenu(
                        expanded = speedMenuExpanded,
                        onDismissRequest = { speedMenuExpanded = false }
                    ) {
                        listOf(0.5f, 0.8f, 1.0f, 1.25f, 1.5f, 1.8f, 2.0f).forEach { s ->
                            DropdownMenuItem(
                                text = { Text("${s}x") },
                                onClick = {
                                    viewModel.setPlaybackSpeed(s)
                                    speedMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 30秒戻る
                    SkipButton(
                        onClick = { viewModel.skipBackward(30) },
                        label = "30",
                        isForward = false
                    )
                    
                    // 15秒戻る
                    SkipButton(
                        onClick = { viewModel.skipBackward(15) },
                        label = "15",
                        isForward = false
                    )
                    
                    // 再生/停止
                    LargeFloatingActionButton(
                        onClick = { viewModel.playPause() },
                        modifier = Modifier.size(56.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        val icon = if (isPlaying) Icons.Default.Pause 
                                   else Icons.Default.PlayArrow
                        Icon(icon, contentDescription = if (isPlaying) "停止" else "再生")
                    }

                    // 1分送る
                    SkipButton(
                        onClick = { viewModel.skipForward(60) },
                        label = "1m",
                        isForward = true
                    )
                    
                    // 2分送る
                    SkipButton(
                        onClick = { viewModel.skipForward(120) },
                        label = "2m",
                        isForward = true
                    )
                }
                
                // Placeholder to balance the speed selector on the left
                Box(modifier = Modifier.width(48.dp))
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * 秒数ラベル付きスキップボタン
 */
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
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 10.sp
            )
        }
    }
}


@Composable
fun DownloadConfirmationDialog(
    program: AirkastProgram,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedOption by remember { mutableIntStateOf(60) } // Default 60s
    val options = listOf(
        15 to "安全 (15秒)",
        60 to "推奨 (60秒)",
        300 to "高速 (5分)",
        7200 to "一括 (試験的)"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "ダウンロード設定") },
        text = {
            Column {
                Text("「${program.title}」をダウンロードしますか？")
                Spacer(modifier = Modifier.height(16.dp))
                Text("分割サイズを選択してください:", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedOption = value }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedOption == value),
                            onClick = { selectedOption = value }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedOption) }) {
                Text("開始")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}

/**
 * エリア選択ダイアログ
 */
@Composable
fun AreaSelectionDialog(
    currentAreaId: String,
    onAreaSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // 主要なエリアリスト
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
        title = { Text("エリア変更") },
        text = {
            Column {
                Text(
                    "※ IPアドレスが一致しないエリアの番組は再生できない場合があります",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                LazyColumn {
                    items(areas) { (areaId, areaName) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAreaSelected(areaId) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (currentAreaId == areaId),
                                onClick = { onAreaSelected(areaId) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "$areaName ($areaId)",
                                fontWeight = if (currentAreaId == areaId) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}