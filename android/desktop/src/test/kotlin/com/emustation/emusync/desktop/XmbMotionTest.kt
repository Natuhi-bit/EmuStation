package com.emustation.emusync.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import com.emustation.emusync.desktop.ui.WheelStepper
import com.emustation.emusync.desktop.ui.Xmb
import com.emustation.emusync.desktop.ui.XmbCategory
import com.emustation.emusync.desktop.ui.XmbItem
import com.emustation.emusync.desktop.ui.XmbState
import com.emustation.emusync.desktop.ui.xmbItemY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** XMB の項目の動き（下・上に動かしたとき、位置が飛ばないこと）と、マウスのホイール */
@OptIn(ExperimentalTestApi::class)
class XmbMotionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun y(rel: Float) = xmbItemY(rel, belowStart = 214f, aboveStart = 34f, step = 72f, gapAfterSelected = 36f)

    @Test
    fun itemPositions_areContinuousAndOrdered() {
        // 決まった位置：選んでいる項目・すぐ下・すぐ上
        assertEquals(214f, y(0f), 0.001f)
        assertEquals(214f + 72f + 36f, y(1f), 0.001f)
        assertEquals(214f + 72f * 2 + 36f, y(2f), 0.001f)
        assertEquals(34f, y(-1f), 0.001f)
        assertEquals(34f - 72f, y(-2f), 0.001f)
        // アニメーション中（rel が少しずつ変わる）に、どこでも飛ばない・順番が入れ替わらない
        var prev = y(-5f)
        var r = -5f + 0.01f
        while (r <= 5f) {
            val cur = y(r)
            assertTrue("rel=$r で位置が飛んだ（${prev}→${cur}）", abs(cur - prev) < 2.0f)
            assertTrue("rel=$r で順番が逆になった", cur > prev)
            prev = cur
            r += 0.01f
        }
    }

    @Test
    fun wheel_oneNotchMovesOneItem_andTouchpadAccumulates() {
        val w = WheelStepper()
        assertEquals(1, w.feed(1f))
        assertEquals(-1, w.feed(-1f))
        assertEquals(3, w.feed(3f))          // 速く回すと、その分だけ
        // タッチパッドの細かい回転はためる
        assertEquals(0, w.feed(0.4f))
        assertEquals(0, w.feed(0.4f))
        assertEquals(1, w.feed(0.4f))
        // 向きが変わったら、ためた分は捨てる（逆向きに 1 目盛りで 1 つ）
        assertEquals(0, w.feed(0.6f))
        assertEquals(-1, w.feed(-1f))
        assertEquals(0, w.feed(0f))
    }

    @Test
    fun wheelOnXmb_movesOneItemPerNotch() {
        val items = (0 until 12).map { XmbItem("g$it", "ゲーム $it", icon = Icons.Filled.Star) }
        val cats = listOf(XmbCategory("games", "ゲーム", Icons.Filled.Star, items))
        val state = XmbState(initialCategory = 0)
        compose.setContent { Box(Modifier.size(1280.dp, 720.dp)) { Xmb(cats, state, animated = false) } }
        compose.waitForIdle()
        fun wheel(dy: Float) {
            compose.onRoot().performMouseInput { moveTo(Offset(600f, 400f)); scroll(dy) }
            compose.waitForIdle()
        }
        wheel(1f)
        assertEquals(1, state.itemOf(cats[0]))
        wheel(1f); wheel(1f)
        assertEquals(3, state.itemOf(cats[0]))
        wheel(-1f)
        assertEquals(2, state.itemOf(cats[0]))
        repeat(20) { wheel(1f) }
        assertEquals(11, state.itemOf(cats[0]))   // 最後で止まる
    }
}
