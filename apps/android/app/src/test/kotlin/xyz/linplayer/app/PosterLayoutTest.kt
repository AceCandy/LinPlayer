package xyz.linplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.linplayer.app.ui.components.posterLayout

class PosterLayoutTest {
    private fun assertLayout(width: Float, height: Float, columns: Int, cell: Float, tv: Boolean = false) {
        val (c, w) = posterLayout(width, height, tv)!!
        assertEquals(columns, c)
        assertEquals(cell, w.value, 0.1f)
    }

    @Test fun 手机竖屏沿用固定刻度() {
        assertNull(posterLayout(411f, 891f))
        assertNull(posterLayout(599f, 1000f))
    }

    @Test fun 平板首页与网格同宽且不超上限() {
        assertLayout(800f, 1280f, 5, 145.6f)
        assertLayout(1024f, 1366f, 6, 157f)
        assertLayout(1280f, 800f, 7, 169.7f)
    }

    @Test fun 横屏手机保持手机海报宽只多排列() {
        assertLayout(891f, 411f, 8, 98.6f)
    }

    @Test fun 电视网格扣除页面边距后按宽屏刻度排布() {
        assertLayout(960f - 32f * 2, 540f, 5, 164.8f)
    }

    @Test fun 电视窄窗口仍按可用宽度自适应() {
        assertLayout(480f - 32f * 2, 300f, 4, 88.5f, tv = true)
    }
}
