package com.example.airkast

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.example.airkast.favorites.FavoritesViewModel
import com.example.airkast.ui.EXTRA_OPEN_TAB
import com.example.airkast.ui.MainApp
import com.example.airkast.ui.theme.AirkastTheme
import kotlinx.coroutines.launch

/**
 * 画面の入口。UI は `ui/` パッケージ(theme / components / screens / AirkastApp)に分割している。
 */
@UnstableApi
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val favoritesViewModel: FavoritesViewModel by viewModels()

    /** 通知タップなどで開くよう求められたタブ(コールドスタートは onCreate、起動中は onNewIntent で受ける)。 */
    private var openTabRequest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-Edge。システムバーのアイコン色は OS のライト/ダークに自動で合わせる
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 画面回転などの再生成では、すでに表示中のタブを維持するため extra を読み直さない
        if (savedInstanceState == null) openTabRequest = intent.getStringExtra(EXTRA_OPEN_TAB)
        // 起動しっぱなし/バックグラウンド復帰/日跨ぎで日付リストが古くならないよう、表示中は日付変化を監視する
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 復帰時に保存先を再スキャンする(バックグラウンドの自動ダウンロードで増えたファイルを拾う)
                viewModel.onScanForDownloads()
                viewModel.watchDateChanges()
            }
        }
        // アプリ復帰時にお気に入りの最新回を更新する
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { favoritesViewModel.refresh(force = true) }
        }
        setContent {
            AirkastTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainApp(
                        viewModel = viewModel,
                        favoritesViewModel = favoritesViewModel,
                        openTabRequest = openTabRequest,
                        onOpenTabHandled = { openTabRequest = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTabRequest = intent.getStringExtra(EXTRA_OPEN_TAB)
    }
}
