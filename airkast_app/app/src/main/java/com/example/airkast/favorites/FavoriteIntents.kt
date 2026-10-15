package com.example.airkast.favorites

import android.content.Context
import android.content.Intent
import com.example.airkast.MainActivity
import com.example.airkast.ui.EXTRA_OPEN_TAB
import com.example.airkast.ui.OPEN_TAB_FAVORITES

/** 完了通知のタップで開く Intent。 MainActivity をお気に入りタブで前面に出す。 */
fun openFavoritesIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
    putExtra(EXTRA_OPEN_TAB, OPEN_TAB_FAVORITES)
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
}
