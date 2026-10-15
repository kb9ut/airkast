package com.example.airkast.ui

/** タブの番号(ナビゲーションバーの並び順と同じ)。 */
const val TAB_FAVORITES = 0
const val TAB_FIND = 1
const val TAB_DOWNLOADS = 2

/** 通知タップなどで開くタブを指定する Intent の extra。 */
const val EXTRA_OPEN_TAB = "open_tab"
const val OPEN_TAB_FAVORITES = "favorites"

/** extra の値からタブ番号を決める。未指定・不明な値はお気に入りタブ。 */
fun tabIndexForExtra(name: String?): Int = when (name) {
    "find" -> TAB_FIND
    "downloads" -> TAB_DOWNLOADS
    else -> TAB_FAVORITES
}
