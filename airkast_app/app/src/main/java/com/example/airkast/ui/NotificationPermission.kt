package com.example.airkast.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Android 13 以降で、通知権限がまだ無いときだけ要求する。 */
internal fun shouldRequestNotificationPermission(sdkInt: Int, granted: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted

/**
 * 通知権限の要求ゲート。戻り値の関数を「自動DL を ON にする操作の直前」に呼ぶ。
 * 権限が必要で未許可のときだけ OS のダイアログを出し、拒否されたら [onDenied] を呼ぶ
 * (「今後表示しない」の状態では、ダイアログなしで即 [onDenied] になる)。
 * 登録や自動DL 自体は権限に関わらず有効なので、呼び出し側は結果を待たずに処理を続けてよい。
 */
@Composable
fun rememberNotificationPermissionGate(onDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnDenied by rememberUpdatedState(onDenied)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) currentOnDenied()
    }
    return remember(context, launcher) {
        {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (shouldRequestNotificationPermission(Build.VERSION.SDK_INT, granted)) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
