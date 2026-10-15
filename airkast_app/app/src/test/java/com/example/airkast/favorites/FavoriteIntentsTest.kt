package com.example.airkast.favorites

import android.content.Intent
import com.example.airkast.MainActivity
import com.example.airkast.TestAirkastApplication
import com.example.airkast.ui.OPEN_TAB_FAVORITES
import com.example.airkast.ui.EXTRA_OPEN_TAB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestAirkastApplication::class)
class FavoriteIntentsTest {
    @Test
    fun openFavoritesIntentTargetsMainActivityWithTheFavoritesTab() {
        val intent = openFavoritesIntent(RuntimeEnvironment.getApplication())

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(OPEN_TAB_FAVORITES, intent.getStringExtra(EXTRA_OPEN_TAB))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
