package dev.controlpad.receiver

import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CursorOverlayIdleTest {
    private lateinit var overlay:CursorOverlay
    @Test fun hidesThirtySecondsAfterLastInputAndWakesOnlyOnInput() {
        val inst=InstrumentationRegistry.getInstrumentation()
        assertTrue("需要兼容光标悬浮窗权限",Settings.canDrawOverlays(inst.targetContext))
        fun visible():Boolean { var value=false; inst.runOnMainSync { value=overlay.isVisible }; return value }
        try {
            inst.runOnMainSync { overlay=CursorOverlay(inst.targetContext); overlay.activity(); overlay.show(100f,100f) }
            assertTrue(visible())
            SystemClock.sleep(15000)
            inst.runOnMainSync { overlay.activity(); overlay.show(110f,100f) }
            val lastInput=SystemClock.uptimeMillis()
            SystemClock.sleep(15500)
            assertTrue("新操作没有重置隐藏计时",visible())
            val deadline=lastInput+32500
            while(visible() && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(100)
            assertFalse("无操作 30 秒后光标仍显示",visible())
            assertTrue("光标过早隐藏",SystemClock.uptimeMillis()-lastInput>=29900)
            inst.runOnMainSync {
                overlay.show(120f,100f)
                assertFalse("后台位置反馈不应唤醒闲置光标",overlay.isVisible)
                overlay.activity(); overlay.show(120f,100f)
                assertTrue("新操作未唤醒光标",overlay.isVisible)
            }
        } finally { inst.runOnMainSync { if(::overlay.isInitialized) overlay.hide() } }
    }
}
