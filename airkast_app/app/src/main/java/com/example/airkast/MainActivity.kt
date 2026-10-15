package com.example.airkast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.example.airkast.ui.MainApp
import com.example.airkast.ui.theme.AirkastTheme
import kotlinx.coroutines.launch

/**
 * 画面の入口。UI は `ui/` パッケージ(theme / components / screens / AirkastApp)に分割している。
 */
@UnstableApi
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-Edge。システムバーのアイコン色は OS のライト/ダークに自動で合わせる
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 起動しっぱなし/バックグラウンド復帰/日跨ぎで日付リストが古くならないよう、表示中は日付変化を監視する
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watchDateChanges() }
        }
        setContent {
            AirkastTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainApp(viewModel)
                }
            }
        }
    }
}
