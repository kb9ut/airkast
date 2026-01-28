package com.example.airkast.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.example.airkast.MainActivity
import com.example.airkast.PlaybackPositionManager
import com.example.airkast.AirkastApplication
import com.example.airkast.AirkastProgram
import com.example.airkast.AirkastClient
import kotlinx.coroutines.*
import java.io.File
import android.media.MediaMetadataRetriever
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.util.UUID

@OptIn(UnstableApi::class) class PlayerService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var player: ExoPlayer? = null

    private lateinit var client: AirkastClient
    private lateinit var positionManager: PlaybackPositionManager

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    // Cache for streaming
    private var downloadCache: SimpleCache? = null
    
    // 現在再生中のメディアID
    private var currentMediaId: String? = null
    
    // 現在再生中のファイルパス (ローカルファイル再生時のみ)
    private var currentFilePath: String? = null
    
    // プログラムから取得したduration (AAC-ADTSファイルではExoPlayerがdurationを返さないため)
    private var programDurationMs: Long = 0L
    
    // 位置を定期保存するJob
    private var positionSaveJob: Job? = null
    
    companion object {
        // 位置保存の間隔（ミリ秒）
        private const val POSITION_SAVE_INTERVAL_MS = 5000L
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        client = (application as AirkastApplication).airkastClient
        positionManager = PlaybackPositionManager(this)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)

        // Initialize Cache
        val cacheDir = File(cacheDir, "media_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val databaseProvider = StandaloneDatabaseProvider(this)
        downloadCache = SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024), databaseProvider)

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractorsFactory))
            .setSeekBackIncrementMs(15000) // 15 seconds back
            .setSeekForwardIncrementMs(30000) // 30 seconds forward
            .build()
        
        // プレーヤーイベントリスナーを追加（位置保存用）
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        // 再生完了時は位置をクリア
                        currentMediaId?.let { positionManager.clearPosition(it) }
                        stopPositionSaveJob()
                    }
                    Player.STATE_READY -> {
                        // 再生準備完了時
                        if (player?.playWhenReady == true) {
                            startPositionSaveJob()
                        }
                    }
                    Player.STATE_IDLE -> {
                        stopPositionSaveJob()
                        saveCurrentPosition()
                    }
                }
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startPositionSaveJob()
                } else {
                    stopPositionSaveJob()
                    saveCurrentPosition()
                }
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("PlayerService", "Player error: ${error.message}", error)
                
                // エラーメッセージをユーザーに通知
                val userMessage = when {
                    error.cause?.message?.contains("403") == true -> 
                        "エリア外エラー: この番組はお住まいの地域では再生できません"
                    error.cause?.message?.contains("404") == true -> 
                        "番組が見つかりません"
                    error.cause?.message?.contains("401") == true -> 
                        "認証エラー: アプリを再起動してください"
                    error.cause is java.net.UnknownHostException -> 
                        "ネットワークエラー: インターネット接続を確認してください"
                    error.cause is java.net.SocketTimeoutException -> 
                        "タイムアウト: 通信に時間がかかっています"
                    else -> 
                        "再生エラー: ${error.message ?: "不明なエラー"}"
                }
                
                serviceScope.launch {
                    (application as AirkastApplication).emitPlayerServiceMessage(userMessage)
                }
            }
        })

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        // PlayerをForwardingPlayerで包み、常にシーク/スキップコマンドを「利用可能」として報告させる
        val underlyingPlayer = player!!
        val forwardingPlayer = object : ForwardingPlayer(underlyingPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return Player.Commands.Builder()
                    .addAllCommands()
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return true // 全てのコマンドを利用可能として報告
            }

            override fun seekTo(positionMs: Long) {
                Log.d("PlayerService", "forwardingPlayer.seekTo: $positionMs")
                underlyingPlayer.seekTo(positionMs)
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                Log.d("PlayerService", "forwardingPlayer.seekTo idx=$mediaItemIndex: $positionMs")
                underlyingPlayer.seekTo(mediaItemIndex, positionMs)
            }

            override fun seekForward() {
                Log.d("PlayerService", "forwardingPlayer.seekForward")
                underlyingPlayer.seekForward()
            }

            override fun seekBack() {
                Log.d("PlayerService", "forwardingPlayer.seekBack")
                underlyingPlayer.seekBack()
            }
        }

        mediaSession = MediaLibrarySession.Builder(this, forwardingPlayer, CustomMediaLibrarySessionCallback())
            .setSessionActivity(pendingIntent)
            .build()

        setMediaNotificationProvider(CustomNotificationProvider())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // アプリがタスクから削除された時に位置を保存
        saveCurrentPosition()
        player?.let {
            if (!it.playWhenReady || it.playbackState == Player.STATE_ENDED) {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        // サービス終了時に位置を保存
        saveCurrentPosition()
        stopPositionSaveJob()
        positionSaveJob = null
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        downloadCache?.release()
        downloadCache = null
        serviceJob.cancel()
        super.onDestroy()
    }
    
    /**
     * 現在の再生位置を保存します。
     */
    private fun saveCurrentPosition() {
        val mediaId = currentMediaId ?: return
        val currentPlayer = player ?: return
        
        val position = currentPlayer.currentPosition
        val duration = currentPlayer.duration
        
        if (position > 0 && duration > 0) {
            positionManager.savePosition(mediaId, position, duration)
        }
    }
    
    /**
     * 定期的に位置を保存するJobを開始します。
     */
    private fun startPositionSaveJob() {
        if (positionSaveJob?.isActive == true) return
        
        positionSaveJob = serviceScope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                saveCurrentPosition()
            }
        }
    }
    
    /**
     * 位置保存Jobを停止します。
     */
    private fun stopPositionSaveJob() {
        positionSaveJob?.cancel()
        positionSaveJob = null
    }

    private inner class CustomMediaLibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            sessionCommands.add(SessionCommand("custom_command_play_from_file", Bundle.EMPTY))
            sessionCommands.add(SessionCommand("custom_command_play_stream", Bundle.EMPTY))
            sessionCommands.add(SessionCommand("custom_command_seek_to", Bundle.EMPTY))
            sessionCommands.add(SessionCommand("custom_command_seek_forward", Bundle.EMPTY))
            sessionCommands.add(SessionCommand("custom_command_seek_back", Bundle.EMPTY))
            sessionCommands.add(SessionCommand("custom_command_get_duration", Bundle.EMPTY))
            
            val playerCommands = Player.Commands.Builder()
                .addAllCommands()
                .build()
            
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands.build())
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == "custom_command_play_stream") {
                 Log.d("PlayerService", "=== Received custom_command_play_stream ===")
                 val url = args.getString("url")
                 val authToken = args.getString("auth_token")
                 Log.d("PlayerService", "Stream URL: ${url?.take(80)}...")
                 val program = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                     args.getParcelable("program", AirkastProgram::class.java)
                 } else {
                     @Suppress("DEPRECATION")
                     args.getParcelable("program")
                 }
                 Log.d("PlayerService", "Program: ${program?.title}, durationMs: ${program?.durationMs}")

                 if (url != null && authToken != null) {
                     val headers = mapOf(
                        "X-Radiko-Authtoken" to authToken,
                        "Referer" to "https://radiko.jp/",
                        "Origin" to "https://radiko.jp",
                         "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                     )

                     val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                         .setUserAgent(headers["User-Agent"])
                         .setDefaultRequestProperties(headers)

                     val dataSourceFactory = DefaultDataSource.Factory(this@PlayerService, httpDataSourceFactory)
                     
                     // Wrap with CacheDataSource
                     val cacheDataSourceFactory = CacheDataSource.Factory()
                         .setCache(downloadCache!!)
                         .setUpstreamDataSourceFactory(dataSourceFactory)
                         .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

                     // メタデータを含むMediaItemを作成
                     val mediaMetadata = MediaMetadata.Builder()
                         .setTitle(program?.title ?: "Unknown")
                         .setArtist(program?.performer ?: "")
                         .setExtras(Bundle().apply { 
                             if (program != null) putParcelable("program", program) 
                         })
                         .build()
                     
                     val mediaItem = MediaItem.Builder()
                         .setUri(url)
                         .setMediaMetadata(mediaMetadata)
                         .build()
                     
                     val mediaSource = HlsMediaSource.Factory(cacheDataSourceFactory)
                         .createMediaSource(mediaItem)

                     // 現在のメディアIDを更新（ストリーミングではファイルパスはなし）
                     if (program != null) {
                         currentMediaId = program.id
                         currentFilePath = null  // ストリーミングなのでファイルパスはなし
                     }
                     serviceScope.launch {
                         player?.apply {
                             setMediaSource(mediaSource)
                             prepare()
                             play()
                         }
                     }
                     return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                 }
                 return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
            } 
            if (customCommand.customAction == "custom_command_play_from_file") {
                Log.d("PlayerService", "=== Received custom_command_play_from_file ===")
                val filePath = args.getString("file_path")
                Log.d("PlayerService", "File path: $filePath")
                val program = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    args.getParcelable("program", AirkastProgram::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    args.getParcelable("program")
                }
                Log.d("PlayerService", "Program: ${program?.title}, durationMs: ${program?.durationMs}")

                if (!filePath.isNullOrEmpty() && program != null) {
                    playFile(filePath, program)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            if (customCommand.customAction == "custom_command_seek_to") {
                val positionMs = args.getLong("position_ms", -1)
                Log.d("PlayerService", "custom_command_seek_to: positionMs=$positionMs, playerDuration=${player?.duration}")
                if (positionMs >= 0) {
                    player?.seekTo(positionMs)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
            }
            if (customCommand.customAction == "custom_command_seek_forward") {
                player?.let {
                    val currentPos = it.currentPosition
                    val duration = it.duration
                    Log.d("PlayerService", "custom_command_seek_forward: currentPos=$currentPos, duration=$duration")
                    val newPosition = if (duration > 0 && duration != C.TIME_UNSET) {
                        (currentPos + 10_000).coerceAtMost(duration)
                    } else {
                        currentPos + 10_000
                    }
                    it.seekTo(newPosition)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == "custom_command_seek_back") {
                player?.let {
                    val currentPos = it.currentPosition
                    Log.d("PlayerService", "custom_command_seek_back: currentPos=$currentPos")
                    val newPosition = (currentPos - 10_000).coerceAtLeast(0)
                    it.seekTo(newPosition)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == "custom_command_get_duration") {
                // 優先順位:
                // 1. programDurationMs (番組のstartTime/endTimeから計算した正確なduration)
                // 2. ExoPlayerのduration
                // 3. MediaMetadataRetriever (AAC-ADTSでは不正確なことが多い)
                var duration = if (programDurationMs > 0) {
                    Log.d("PlayerService", "custom_command_get_duration: using programDurationMs=$programDurationMs")
                    programDurationMs
                } else {
                    val exoDuration = player?.duration ?: C.TIME_UNSET
                    Log.d("PlayerService", "custom_command_get_duration: exoplayer duration=$exoDuration")
                    
                    if (exoDuration != C.TIME_UNSET && exoDuration > 0) {
                        exoDuration
                    } else {
                        // フォールバック: MediaMetadataRetriever (通常は不正確)
                        val filePath = currentFilePath
                        if (filePath != null) {
                            try {
                                val retriever = MediaMetadataRetriever()
                                retriever.setDataSource(filePath)
                                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                retriever.release()
                                val metaDuration = durationStr?.toLongOrNull() ?: C.TIME_UNSET
                                Log.d("PlayerService", "custom_command_get_duration: MediaMetadataRetriever duration=$metaDuration")
                                metaDuration
                            } catch (e: Exception) {
                                Log.e("PlayerService", "MediaMetadataRetriever error: ${e.message}")
                                C.TIME_UNSET
                            }
                        } else {
                            C.TIME_UNSET
                        }
                    }
                }
                
                val resultExtras = Bundle().apply {
                    putLong("duration_ms", duration)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, resultExtras))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val libraryRoot = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Radiko Downloads").setIsPlayable(false).setIsBrowsable(true).build())
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(libraryRoot, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
             // Playlists implementation would go here
             return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
        }

        override fun onGetItem(
             session: MediaLibrarySession,
             browser: MediaSession.ControllerInfo,
             mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
             return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    private fun playFile(filePath: String, program: AirkastProgram) {
        val file = File(filePath)
        if (!file.exists()) {
            serviceScope.launch {
                (application as AirkastApplication).emitPlayerServiceMessage("ファイルが見つかりません: ${file.name}")
            }
            return
        }

        // 現在のメディアIDとファイルパスを更新
        currentMediaId = program.id
        currentFilePath = filePath
        programDurationMs = program.durationMs
        Log.d("PlayerService", "playFile: programDurationMs=$programDurationMs")

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(program.title)
            .setArtist(program.performer)
            .setGenre("Radio")
            .setExtras(Bundle().apply { putParcelable("program", program) })
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(program.id)
            .setUri(android.net.Uri.fromFile(file).toString())
            .setMimeType("audio/aac")
            .setMediaMetadata(mediaMetadata)
            .build()

        // 保存された再生位置を取得
        val savedPosition = positionManager.getPosition(program.id)

        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            
            // 保存された位置があれば、そこから再生を再開
            if (savedPosition > 0) {
                seekTo(savedPosition)
            }
            
            play()
        }
    }

    @UnstableApi
    private inner class CustomNotificationProvider : androidx.media3.session.DefaultMediaNotificationProvider(this@PlayerService) {
        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            customLayout: ImmutableList<androidx.media3.session.CommandButton>,
            showInCompactViewIndices: Boolean
        ): ImmutableList<androidx.media3.session.CommandButton> {
            val player = session.player
            val isPlaying = player.isPlaying
            
            val playPauseButton = androidx.media3.session.CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                .setIconResId(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                .setDisplayName(if (isPlaying) "Pause" else "Play")
                .build()

            val seekBackButton = androidx.media3.session.CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setIconResId(android.R.drawable.ic_media_rew) 
                .setDisplayName("Seek Back 15s")
                .build()

            val seekForwardButton = androidx.media3.session.CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setIconResId(android.R.drawable.ic_media_ff)
                .setDisplayName("Seek Forward 30s")
                .build()

            return ImmutableList.of(seekBackButton, playPauseButton, seekForwardButton)
        }
    }
}

