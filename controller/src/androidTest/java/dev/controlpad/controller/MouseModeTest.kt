package dev.controlpad.controller

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.controlpad.shared.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MouseModeTest {
    @Test fun systemMouseCanSwitchToTouchAndBackWithoutBouncing() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val activity=inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun await(message:String,test:()->Boolean) {
            val end=SystemClock.uptimeMillis()+15000
            while(!test() && SystemClock.uptimeMillis()<end) SystemClock.sleep(20)
            assertTrue(message,test())
        }
        fun button(view:View,label:String):Button? {
            if(view is Button && view.text.toString()==label) return view
            if(view is android.view.ViewGroup) for(i in 0 until view.childCount) button(view.getChildAt(i),label)?.let { return it }
            return null
        }
        fun click(label:String) { inst.runOnMainSync { val view=requireNotNull(button(activity.window.decorView,label)); assertTrue(view.isEnabled); view.performClick() } }
        try {
            inst.runOnMainSync { activity.client.connect(activity.client.saved().first()) }
            await("未进入系统鼠标模式") { activity.client.connected && activity.client.nativeMouse }
            repeat(2) {
                click("触摸")
                await("触摸模式未生效") { !activity.client.nativeMouse }
                assertTrue("触摸模式丢失系统鼠标能力",activity.client.capabilities and Capability.NATIVE_MOUSE!=0)
                click("鼠标")
                await("鼠标模式未生效") { activity.client.nativeMouse }
                SystemClock.sleep(500)
                inst.runOnMainSync { assertTrue("鼠标模式跳回触摸",requireNotNull(button(activity.window.decorView,"鼠标")).isSelected) }
                activity.client.send(Packet(Kind.MOVE,x=12f,y=4f))
                SystemClock.sleep(60)
                activity.client.send(Packet(Kind.MOVE,x=-12f,y=-4f))
                SystemClock.sleep(60)
            }
            assertTrue(activity.client.connected)
        } finally { inst.runOnMainSync { activity.client.disconnect(); activity.finish() } }
    }
}
