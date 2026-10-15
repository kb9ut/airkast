package com.example.airkast.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TabsTest {
    @Test
    fun favoritesIsTheDefaultTab() {
        assertEquals(TAB_FAVORITES, tabIndexForExtra(null))
        assertEquals(TAB_FAVORITES, tabIndexForExtra(""))
        assertEquals(TAB_FAVORITES, tabIndexForExtra("unknown-tab"))
    }

    @Test
    fun knownNamesMapToTheirTabs() {
        assertEquals(TAB_FAVORITES, tabIndexForExtra(OPEN_TAB_FAVORITES))
        assertEquals(TAB_FIND, tabIndexForExtra("find"))
        assertEquals(TAB_DOWNLOADS, tabIndexForExtra("downloads"))
    }

    @Test
    fun tabOrderIsFavoritesFindDownloads() {
        assertEquals(listOf(0, 1, 2), listOf(TAB_FAVORITES, TAB_FIND, TAB_DOWNLOADS))
    }
}
