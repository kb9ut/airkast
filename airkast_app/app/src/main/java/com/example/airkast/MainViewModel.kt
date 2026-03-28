package com.example.airkast

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.example.airkast.service.HlsDownloadService
import com.example.airkast.service.PlayerService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "MainViewModel"

/**
 * メイン画面のビジネスロジックを担当するViewModel。
 * 認証、放送局リスト、番組表、ダウンロードの状態を管理します。
 */
class MainViewModel(
    application: Application
) : AndroidViewModel(application) {

    // Use shared AirkastClient instance from Application to share auth token
    private val client: AirkastClient = (application as AirkastApplication).airkastClient

    private val _stationUiState = MutableStateFlow<UiState<List<AirkastStation>>>(UiState.Idle)
    val stationUiState: StateFlow<UiState<List<AirkastStation>>> = _stationUiState.asStateFlow()

    private val _selectedStationId = MutableStateFlow<String?>(null)
    val selectedStationId: StateFlow<String?> = _selectedStationId.asStateFlow()

    private val _selectedDate = MutableStateFlow(Date())
    val selectedDate: StateFlow<Date> = _selectedDate.asStateFlow()

    private val _programGuideUiState = MutableStateFlow<UiState<List<AirkastProgram>>>(UiState.Idle)
    val programGuideUiState: StateFlow<UiState<List<AirkastProgram>>> = _programGuideUiState.asStateFlow()

    private val _downloadedFiles = MutableStateFlow<List<File>>(emptyList())
    val downloadedFiles: StateFlow<List<File>> = _downloadedFiles.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _selectedAreaId = MutableStateFlow("JP13") // Default to Tokyo Area ID
    val selectedAreaId: StateFlow<String> = _selectedAreaId.asStateFlow()

    private val _mediaBrowserConnectionState = MutableStateFlow(MediaBrowserConnectionState.DISCONNECTED)
    val mediaBrowserConnectionState: StateFlow<MediaBrowserConnectionState> = _mediaBrowserConnectionState.asStateFlow()

    // Media3 MediaController
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val _ongoingDownloadStates = MutableStateFlow<Map<String, HlsDownloadService.DownloadStatus>>(emptyMap())
    val ongoingDownloadStates: StateFlow<Map<String, HlsDownloadService.DownloadStatus>> = _ongoingDownloadStates.asStateFlow()

    private val _selectedTimeFilter = MutableStateFlow(TimeFilter.EARLY_MORNING)
    val selectedTimeFilter: StateFlow<TimeFilter> = _selectedTimeFilter.asStateFlow()

    // --- Player State for In-App UI ---
    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _playbackDuration = MutableStateFlow(0L)
    val playbackDuration: StateFlow<Long> = _playbackDuration.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _mediaMetadata = MutableStateFlow<androidx.media3.common.MediaMetadata?>(null)
    val mediaMetadata: StateFlow<androidx.media3.common.MediaMetadata?> = _mediaMetadata.asStateFlow()

    private var positionPollingJob: kotlinx.coroutines.Job? = null

    // --- Turbo (長押し高速再生) ---
    private val _turboSpeed = MutableStateFlow(50f) // デフォルト50倍速
    val turboSpeed: StateFlow<Float> = _turboSpeed.asStateFlow()

    private val _isTurboActive = MutableStateFlow(false)
    val isTurboActive: StateFlow<Boolean> = _isTurboActive.asStateFlow()

    private var preTurboSpeed: Float = 1.0f // ターボ開始前の速度を保持

    // --- ダウンロード分割サイズ設定 ---
    private val _downloadChunkSize = MutableStateFlow(60) // デフォルト推奨(60秒)
    val downloadChunkSize: StateFlow<Int> = _downloadChunkSize.asStateFlow()

    companion object {
        private const val PREFS_NAME = "airkast_prefs"
        private const val KEY_LAST_STATION_ID = "last_station_id"
        private const val KEY_LAST_TIME_FILTER = "last_time_filter"
        private const val KEY_TURBO_SPEED = "turbo_speed"
        private const val KEY_DOWNLOAD_CHUNK_SIZE = "download_chunk_size"
    }

    init {
        loadPreferences()

        scanForDownloads()
        authenticateAndFetchStations()
        viewModelScope.launch {
            HlsDownloadService.downloadStates.collect { states ->
                _ongoingDownloadStates.value = states
                if (states.any { it.value is HlsDownloadService.DownloadStatus.Completed }) {
                    scanForDownloads()
                }
            }
        }

        // Media3 MediaController を使用して接続
        connectToPlayerService()

        viewModelScope.launch {
            (getApplication() as AirkastApplication).playerServiceMessage.collect { message ->
                _userMessage.emit(message)
            }
        }

        // Observe subscription count to playbackPosition to decide whether to poll
        viewModelScope.launch {
            _playbackPosition.subscriptionCount.collect {
                checkPollingState()
            }
        }
    }

    private fun loadPreferences() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastStationId = prefs.getString(KEY_LAST_STATION_ID, null)
        val lastTimeFilterName = prefs.getString(KEY_LAST_TIME_FILTER, TimeFilter.EARLY_MORNING.name)

        if (lastStationId != null) {
            _selectedStationId.value = lastStationId
        }

        if (lastTimeFilterName != null) {
            try {
                _selectedTimeFilter.value = TimeFilter.valueOf(lastTimeFilterName)
            } catch (e: Exception) {
                _selectedTimeFilter.value = TimeFilter.EARLY_MORNING
            }
        }

        _turboSpeed.value = prefs.getFloat(KEY_TURBO_SPEED, 50f)
        _downloadChunkSize.value = prefs.getInt(KEY_DOWNLOAD_CHUNK_SIZE, 60)
    }

    private fun savePreferences() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_LAST_STATION_ID, _selectedStationId.value)
            putString(KEY_LAST_TIME_FILTER, _selectedTimeFilter.value.name)
            putFloat(KEY_TURBO_SPEED, _turboSpeed.value)
            putInt(KEY_DOWNLOAD_CHUNK_SIZE, _downloadChunkSize.value)
            apply()
        }
    }

    fun setDownloadChunkSize(size: Int) {
        _downloadChunkSize.value = size
        savePreferences()
    }
    
    fun onTimeFilterSelected(filter: TimeFilter) {
        _selectedTimeFilter.value = filter
        savePreferences()
    }

    private fun connectToPlayerService() {
        Log.d(TAG, "PlayerService接続開始...")
        val sessionToken = SessionToken(
            getApplication(),
            ComponentName(getApplication(), PlayerService::class.java)
        )
        controllerFuture = MediaController.Builder(getApplication(), sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                mediaController?.addListener(playerListener)
                _mediaBrowserConnectionState.value = MediaBrowserConnectionState.CONNECTED
                Log.d(TAG, "PlayerService接続成功")
            } catch (e: Exception) {
                Log.e(TAG, "PlayerService接続失敗: ${e.message}", e)
                _mediaBrowserConnectionState.value = MediaBrowserConnectionState.FAILED
            }
        }, MoreExecutors.directExecutor())
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            checkPollingState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                // ExoPlayerからdurationを取得してUIに反映
                updateDurationFromPlayer()
            }
        }

        override fun onMediaMetadataChanged(metadata: androidx.media3.common.MediaMetadata) {
            _mediaMetadata.value = metadata
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            _playbackSpeed.value = playbackParameters.speed
        }
    }

    /**
     * ExoPlayerから実際のdurationを取得してUIに反映します。
     * MediaController.durationはTIME_UNSETを返すことがあるため、
     * カスタムコマンドでPlayerServiceから直接ExoPlayerのdurationを取得します。
     */
    private var durationRetryJob: kotlinx.coroutines.Job? = null
    
    private fun updateDurationFromPlayer() {
        durationRetryJob?.cancel()
        durationRetryJob = viewModelScope.launch {
            fetchDurationFromService(retryCount = 0)
        }
    }
    
    private suspend fun fetchDurationFromService(retryCount: Int) {
        if (retryCount >= 15) {
            Log.w(TAG, "Failed to get valid duration after 15 retries")
            return
        }
        
        val controller = mediaController
        if (controller == null || !controller.isConnected) {
            Log.d(TAG, "fetchDurationFromService: controller not connected, retry in 300ms")
            kotlinx.coroutines.delay(300)
            fetchDurationFromService(retryCount + 1)
            return
        }
        
        try {
            val command = SessionCommand("custom_command_get_duration", Bundle.EMPTY)
            val result = controller.sendCustomCommand(command, Bundle.EMPTY).get()
            val duration = result.extras.getLong("duration_ms", 0L)
            
            Log.d(TAG, "fetchDurationFromService: retry=$retryCount, duration=$duration")
            
            // C.TIME_UNSET = Long.MIN_VALUE + 1 = -9223372036854775807
            // また0や負の値も無効として扱う
            if (duration > 0 && duration < 360000000L) {
                _playbackDuration.value = duration
                _playbackSpeed.value = controller.playbackParameters?.speed ?: 1.0f
                Log.d(TAG, "Player READY: duration=$duration ms")
            } else {
                // durationがまだ取得できていない場合、少し待ってから再試行
                kotlinx.coroutines.delay(300)
                fetchDurationFromService(retryCount + 1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchDurationFromService error: ${e.message}")
            kotlinx.coroutines.delay(300)
            fetchDurationFromService(retryCount + 1)
        }
    }

    private fun checkPollingState() {
        if (_isPlaying.value && _playbackPosition.subscriptionCount.value > 0) {
            startPositionPolling()
        } else {
            stopPositionPolling()
        }
    }

    private fun startPositionPolling() {
        if (positionPollingJob != null) return
        positionPollingJob = viewModelScope.launch {
            while (true) {
                _playbackPosition.value = mediaController?.currentPosition ?: 0L
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private fun stopPositionPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = null
    }

    override fun onCleared() {
        super.onCleared()
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    fun onPlayFile(file: File, program: AirkastProgram) {
        Log.d(TAG, "=== onPlayFile called ===")
        Log.d(TAG, "Playing LOCAL FILE: ${file.absolutePath}")
        Log.d(TAG, "File exists: ${file.exists()}, size: ${file.length()} bytes")
        Log.d(TAG, "Program: ${program.title}, durationMs: ${program.durationMs}")
        
        val controller = mediaController
        if (controller != null && controller.isConnected) {
            val extras = Bundle().apply {
                putString("file_path", file.absolutePath)
                putParcelable("program", program)
            }
            val command = SessionCommand("custom_command_play_from_file", Bundle.EMPTY)
            controller.sendCustomCommand(command, extras)
            // 初期状態を更新
            _mediaMetadata.value = androidx.media3.common.MediaMetadata.Builder()
                .setTitle(program.title)
                .setArtist(program.performer)
                .build()
        } else {
            viewModelScope.launch { _userMessage.emit("プレーヤーサービスに接続できません。") }
        }
    }

    // --- Player Controls ---
    fun seekTo(position: Long) {
        val controller = mediaController ?: return
        Log.d(TAG, "seekTo: position=${position}ms")
        controller.seekTo(position)
    }

    fun setPlaybackSpeed(speed: Float) {
        mediaController?.setPlaybackSpeed(speed)
    }

    // --- Turbo (長押し高速再生) ---

    /**
     * ターボ倍速の設定値を変更します (10〜200倍)。
     */
    fun setTurboSpeed(speed: Float) {
        _turboSpeed.value = speed.coerceIn(10f, 200f)
        savePreferences()
    }

    /**
     * ターボモード開始: 現在の再生速度を保存し、turboSpeed に切り替える。
     * ボタンを押している間だけ呼ばれる想定。
     */
    fun startTurbo() {
        val controller = mediaController ?: return
        if (_isTurboActive.value) return
        preTurboSpeed = controller.playbackParameters?.speed ?: 1.0f
        _isTurboActive.value = true
        controller.setPlaybackSpeed(_turboSpeed.value)
        Log.d(TAG, "Turbo START: ${_turboSpeed.value}x (was ${preTurboSpeed}x)")
    }

    /**
     * ターボモード終了: 保存していた元の再生速度に戻す。
     */
    fun stopTurbo() {
        val controller = mediaController ?: return
        if (!_isTurboActive.value) return
        _isTurboActive.value = false
        controller.setPlaybackSpeed(preTurboSpeed)
        Log.d(TAG, "Turbo STOP: restored ${preTurboSpeed}x")
    }

    fun playPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    /**
     * 指定した秒数だけ前にスキップします。
     * @param seconds スキップする秒数（正の値）
     */
    fun skipForward(seconds: Int = 30) {
        val controller = mediaController ?: return
        val currentPos = controller.currentPosition
        val duration = controller.duration
        val newPos = minOf(currentPos + seconds * 1000L, if (duration > 0) duration else Long.MAX_VALUE)
        Log.d(TAG, "skipForward: +${seconds}s (${currentPos}ms -> ${newPos}ms)")
        controller.seekTo(newPos)
    }

    /**
     * 指定した秒数だけ後ろにスキップします。
     * @param seconds スキップする秒数（正の値）
     */
    fun skipBackward(seconds: Int = 15) {
        val controller = mediaController ?: return
        val currentPos = controller.currentPosition
        val newPos = maxOf(currentPos - seconds * 1000L, 0L)
        Log.d(TAG, "skipBackward: -${seconds}s (${currentPos}ms -> ${newPos}ms)")
        controller.seekTo(newPos)
    }

    fun playStream(program: AirkastProgram) {
        Log.d(TAG, "=== playStream called ===")
        Log.d(TAG, "Playing STREAM for: ${program.title}")
        Log.d(TAG, "Program: durationMs: ${program.durationMs}")
        
        val controller = mediaController
        if (controller != null && controller.isConnected) {
            viewModelScope.launch {
                try {
                    val authToken = client.authToken
                    if (authToken == null) {
                         _userMessage.emit("認証トークンがありません。再認証してください。")
                         return@launch
                    }
                    // リダイレクト解決済みのURLを取得する
                    val streamUrl = client.getHlsStreamUrl(program)
                    Log.d(TAG, "Stream URL obtained: ${streamUrl.take(80)}...")
                    
                    val extras = Bundle().apply {
                        putString("url", streamUrl)
                        putString("auth_token", authToken)
                        putParcelable("program", program)
                    }
                    val command = SessionCommand("custom_command_play_stream", Bundle.EMPTY)
                    controller.sendCustomCommand(command, extras)
                    // 初期状態を更新してIn-App Playerに表示
                    _mediaMetadata.value = androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(program.title)
                        .setArtist("${program.stationId} | ${program.formattedDisplayDate} | ${program.formattedDisplayTime}")
                        .build()
                    _userMessage.emit("ストリーミング再生を開始します...")
                } catch (e: Exception) {
                    Log.e(TAG, "ストリーミング再生エラー: ${e.message}", e)
                    _userMessage.emit("エラー: ${e.message}")
                }
            }
        } else {
            viewModelScope.launch { _userMessage.emit("プレーヤーサービスに接続できません。") }
        }
    }

    /**
     * アプリ起動時にPC版認証と放送局リストの取得を試みます。
     * PC版認証はIPアドレスから自動的にエリアを判定します。
     */
    fun authenticateAndFetchStations() {
        Log.d(TAG, "========== 認証プロセス開始 ==========")
        viewModelScope.launch {
            _stationUiState.value = UiState.Loading
            try {
                // Step 1: auth1
                Log.d(TAG, "Step 1: auth1 呼び出し中...")
                val (keyLength, keyOffset) = client.auth1()
                Log.d(TAG, "Step 1 完了: keyLength=$keyLength, keyOffset=$keyOffset")
                
                // Step 2: getPartialKey
                Log.d(TAG, "Step 2: getPartialKey 呼び出し中...")
                val partialKey = client.getPartialKey(keyOffset, keyLength)
                Log.d(TAG, "Step 2 完了: partialKey=${partialKey.take(10)}...")
                
                // Step 3: auth2
                Log.d(TAG, "Step 3: auth2 呼び出し中...")
                val detectedAreaId = client.auth2(partialKey)
                Log.d(TAG, "Step 3 完了: detectedAreaId=$detectedAreaId")
                
                // Update selected area with detected area
                _selectedAreaId.value = detectedAreaId

                // Step 4: getStations
                Log.d(TAG, "Step 4: getStations 呼び出し中...")
                val stations = client.getStations(detectedAreaId)
                Log.d(TAG, "Step 4 完了: ${stations.size}局取得")
                
                _stationUiState.value = UiState.Success(stations)
                Log.d(TAG, "========== 認証プロセス完了 ==========")
                viewModelScope.launch { _userMessage.emit("放送局を選択してください (エリア: $detectedAreaId)") }
                
                // 保存されたStationIDがある場合は、番組表を取得する
                if (_selectedStationId.value != null) {
                    fetchProgramGuide()
                }
            } catch (e: ApiException) {
                Log.e(TAG, "認証プロセス失敗: ${e.javaClass.simpleName} - ${e.localizedMessage}", e)
                _stationUiState.value = UiState.Error(e)
                viewModelScope.launch { _userMessage.emit("認証または放送局の取得に失敗: ${e.localizedMessage}") }
            } catch (e: Exception) {
                Log.e(TAG, "認証プロセス失敗 (予期しないエラー): ${e.javaClass.simpleName} - ${e.message}", e)
                _stationUiState.value = UiState.Error(StationListException(e.message ?: "Unknown error", e))
                viewModelScope.launch { _userMessage.emit("エラー: ${e.message}") }
            }
        }
    }

    /**
     * ユーザーが放送局を選択したときに呼び出されます。
     * @param stationId 選択された放送局のID。
     */
    fun onStationSelected(stationId: String) {
        _selectedStationId.value = stationId
        savePreferences()
        fetchProgramGuide()
    }

    /**
     * ユーザーがエリアを変更したときに呼び出されます。
     * @param areaId 選択されたエリアID（例: "JP13"）。
     */
    fun onAreaSelected(areaId: String) {
        _selectedAreaId.value = areaId
        _selectedStationId.value = null
        _programGuideUiState.value = UiState.Idle
        
        // 新しいエリアの放送局リストを取得
        viewModelScope.launch {
            _stationUiState.value = UiState.Loading
            try {
                val stations = client.getStations(areaId)
                _stationUiState.value = UiState.Success(stations)
                _userMessage.emit("エリアを変更しました: $areaId (${stations.size}局)")
            } catch (e: Exception) {
                _stationUiState.value = UiState.Error(StationListException(e.message ?: "Unknown error", e))
                _userMessage.emit("エラー: ${e.message}")
            }
        }
    }

    /**
     * ユーザーが日付を選択したときに呼び出されます。
     * @param date 選択された日付。
     */
    fun onDateSelected(date: Date) {
        _selectedDate.value = date
        fetchProgramGuide()
    }

    /**
     * 過去7日間の日付リスト（昇順：古い順→新しい順）。
     */
    val dateList: List<Date> by lazy {
        List(7) { i ->
            java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -(6 - i)) }.time
        }
    }

    /**
     * 選択されている放送局と日付に基づいて番組表を取得します。
     */
    private fun fetchProgramGuide() {
        val stationId = _selectedStationId.value ?: return
        viewModelScope.launch {
            _programGuideUiState.value = UiState.Loading
            try {
                val dateString = SimpleDateFormat("yyyyMMdd", Locale.JAPAN).format(_selectedDate.value)
                val programs = client.getProgramGuide(stationId, dateString)
                _programGuideUiState.value = UiState.Success(programs)
            } catch (e: ApiException) {
                _programGuideUiState.value = UiState.Error(e)
                viewModelScope.launch { _userMessage.emit("番組表の取得に失敗: ${e.localizedMessage}") }
            }
        }
    }

    /**
     * 指定された番組のダウンロードを開始します。
     * @param program ダウンロードする番組。
     * @param chunkSizeSeconds 分割ダウンロードのチャンクサイズ（秒）。
     */
    fun onDownloadProgram(program: AirkastProgram, chunkSizeSeconds: Int) {
        val intent = Intent(getApplication(), HlsDownloadService::class.java).apply {
            action = HlsDownloadService.ACTION_START_DOWNLOAD
            putExtra(HlsDownloadService.EXTRA_PROGRAM, program)
            putExtra(HlsDownloadService.EXTRA_CHUNK_SIZE, chunkSizeSeconds)
        }
        getApplication<Application>().startService(intent)
        viewModelScope.launch { _userMessage.emit("ダウンロードキューに追加しました: ${program.title} (分割: ${chunkSizeSeconds}秒)") }
    }

    /**
     * ダウンロードを中止します。
     */
    fun cancelDownload(programId: String) {
        val intent = Intent(getApplication(), HlsDownloadService::class.java).apply {
            action = HlsDownloadService.ACTION_CANCEL_DOWNLOAD
            putExtra(HlsDownloadService.EXTRA_PROGRAM_ID, programId)
        }
        getApplication<Application>().startService(intent)
        viewModelScope.launch { _userMessage.emit("ダウンロード中止をリクエストしました") }
    }

    /**
     * ダウンロード済みファイルをスキャンしてリストを更新します。
     */
    fun onScanForDownloads() {
        scanForDownloads()
    }

    /**
     * 指定されたファイルを削除します。
     * @param file 削除するファイル。
     */
    fun onDeleteFile(file: File) {
        if (file.exists() && file.delete()) {
            viewModelScope.launch { _userMessage.emit("削除しました: ${file.name}") }
            scanForDownloads()
        } else {
            viewModelScope.launch { _userMessage.emit("削除に失敗: ${file.name}") }
        }
    }

    private fun scanForDownloads() {
        val downloadDir = getApplication<Application>().getExternalFilesDir("downloads") ?: return
        val files = downloadDir.listFiles { _, name -> name.endsWith(".m4a") }
        _downloadedFiles.value = files?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}

enum class MediaBrowserConnectionState {
    CONNECTED,
    SUSPENDED,
    FAILED,
    DISCONNECTED
}
