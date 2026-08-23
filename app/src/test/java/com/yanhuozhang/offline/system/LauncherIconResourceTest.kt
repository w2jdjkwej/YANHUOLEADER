package com.yanhuozhang.offline.system

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import com.yanhuozhang.offline.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherIconResourceTest {
    @Test
    fun `launcher icon uses supplied character image on a pale blue background`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources = context.resources
        val sourceId = resources.getIdentifier("app_icon_source", "drawable", context.packageName)

        assertNotEquals(0, sourceId)
        assertTrue(resources.getDrawable(sourceId, context.theme) is BitmapDrawable)
        assertTrue(resources.getDrawable(R.drawable.ic_launcher_foreground, context.theme) is BitmapDrawable)
        assertEquals(
            0xFFD9E7FF.toInt(),
            resources.getColor(R.color.launcher_background, context.theme),
        )
    }
}
