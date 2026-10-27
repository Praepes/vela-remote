package dev.controlpad.controller

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.controlpad.shared.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.startsWith

@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private fun await(message:String,timeout:Long=15000,test:()->Boolean) { val end=SystemClock.uptimeMillis()+timeout; while(!test() && SystemClock.uptimeMillis()<end) SystemClock.sleep(50); assertTrue(message,test()) }
    private fun screenshot(name:String) {
        var bitmap:android.graphics.Bitmap?=null
        await("系统截图未就绪",5000) { if(bitmap==null) bitmap=inst.uiAutomation.takeScreenshot(); bitmap!=null }
        requireNotNull(bitmap).let { b->File(inst.targetContext.filesDir,name).outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; b.recycle() }
    }
    @Test fun actualControllerToRootReceiver() {
        val args=InstrumentationRegistry.getArguments()
        val config=JSONObject(String(android.util.Base64.decode(args.getString("config"),android.util.Base64.DEFAULT)))
        val activity=inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        onView(withText("连接")).perform(click())
        onView(withText("输入设备地址")).perform(click())
        onView(withHint(startsWith("设备地址"))).perform(replaceText(config.getString("host")),closeSoftKeyboard())
        screenshot("pairing-six-digits.png")
        val wrong=if(config.getString("token")=="000000") "111111" else "000000"
        onView(withContentDescription("六位配对码")).perform(replaceText(wrong),closeSoftKeyboard())
        onView(withText("配对连接")).perform(click())
        await("错误配对码未返回可重试状态",30000) { inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("配对码不正确")?.isNotEmpty()==true }
        assertFalse(activity.client.connected)
        onView(withText("配对连接")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isEnabled()))
        onView(withContentDescription("六位配对码")).perform(replaceText(config.getString("token")),closeSoftKeyboard())
        onView(withText("配对连接")).perform(click())
        await("加密配对与显示配置未完成",30000) { activity.client.connected && activity.client.displayVersion>0 }
        // 该用例验证绝对触摸注入；原生 HID 鼠标需要单独在系统鼠标模式验证。
        onView(withText("触摸")).perform(click())
        assertEquals("应在后台固定完整证书",64,activity.client.device!!.pin.length)
        assertEquals("配对后应保存随机长期凭据",48,activity.client.device!!.token.length)
        inst.runOnMainSync { activity.pad.engine.speed=1f; activity.pad.engine.acceleration=0f }
        var down=0L
        fun event(action:Int,points:List<Pair<Float,Float>>) {
            inst.runOnMainSync {
                val now=SystemClock.uptimeMillis(); if(action==MotionEvent.ACTION_DOWN) down=now
                val properties=Array(points.size) { i->MotionEvent.PointerProperties().apply { id=i; toolType=MotionEvent.TOOL_TYPE_FINGER } }
                val coordinates=Array(points.size) { i->MotionEvent.PointerCoords().apply { x=points[i].first; y=points[i].second; pressure=1f; size=0.1f } }
                val e=MotionEvent.obtain(down,now,action,points.size,properties,coordinates,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                activity.pad.dispatchTouchEvent(e); e.recycle()
            }; SystemClock.sleep(25)
        }
        fun single(action:Int,x:Float=100f,y:Float=100f)=event(action,listOf(x to y))
        fun position(x:Float,y:Float) {
            activity.client.send(Packet(Kind.CENTER)); await("居中失败") { kotlin.math.abs(activity.lastCursorX-640)<2 && kotlin.math.abs(activity.lastCursorY-360)<2 }
            single(MotionEvent.ACTION_DOWN); single(MotionEvent.ACTION_MOVE,100+x-640,100+y-360); single(MotionEvent.ACTION_UP,100+x-640,100+y-360)
            await("相对移动未到达目标") { kotlin.math.abs(activity.lastCursorX-x)<2 && kotlin.math.abs(activity.lastCursorY-y)<2 }; SystemClock.sleep(350)
        }
        position(config.getInt("targetX").toFloat(),config.getInt("targetY").toFloat())
        single(MotionEvent.ACTION_DOWN); single(MotionEvent.ACTION_UP)
        SystemClock.sleep(100)
        single(MotionEvent.ACTION_DOWN); single(MotionEvent.ACTION_UP)
        SystemClock.sleep(500)
        // 点按后再次按住移动。
        single(MotionEvent.ACTION_DOWN); single(MotionEvent.ACTION_UP)
        single(MotionEvent.ACTION_DOWN); single(MotionEvent.ACTION_MOVE,170f,100f); single(MotionEvent.ACTION_UP,170f,100f)
        SystemClock.sleep(400)
        // 长按再移动。
        single(MotionEvent.ACTION_DOWN); SystemClock.sleep(1100); single(MotionEvent.ACTION_MOVE,180f,100f); single(MotionEvent.ACTION_UP,180f,100f)
        position(640f,180f)
        // 使用真实的双触点 MotionEvent 流，不用电脑鼠标代替。
        single(MotionEvent.ACTION_DOWN,80f,160f)
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(80f to 160f,200f to 160f))
        repeat(8) { i->event(MotionEvent.ACTION_MOVE,listOf((80-i*9f) to 160f,(200+i*9f) to 160f)) }
        event(MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(8f to 160f,272f to 160f)); single(MotionEvent.ACTION_UP,8f,160f)
        position(640f,500f)
        single(MotionEvent.ACTION_DOWN,80f,240f); event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(80f to 240f,200f to 240f))
        repeat(10) { i->event(MotionEvent.ACTION_MOVE,listOf(80f to (240-i*15f),200f to (240-i*15f))) }
        event(MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(80f to 105f,200f to 105f)); single(MotionEvent.ACTION_UP,80f,105f)
        activity.client.send(Packet(Kind.KEY,code=android.view.KeyEvent.KEYCODE_DPAD_DOWN,flag=true)); activity.client.send(Packet(Kind.KEY,code=android.view.KeyEvent.KEYCODE_DPAD_DOWN,flag=false))
        // 有效视频会话绑定与编码输出验证。
        val frames=CountDownLatch(3); val formats=CountDownLatch(1)
        activity.client.preview(true) { p->if(p.kind==Kind.VIDEO_FORMAT) formats.countDown(); if(p.kind==Kind.VIDEO_FRAME && p.code and 2==0) frames.countDown() }
        assertTrue("没有预览格式（请启用模拟器调试软件编码）",formats.await(15,TimeUnit.SECONDS))
        // 触发画面变化，静态画面不保证连续产生编码帧。
        repeat(5) { activity.client.send(Packet(Kind.MOVE,x=5f)); SystemClock.sleep(150) }
        assertTrue("没有连续的视频帧",frames.await(15,TimeUnit.SECONDS))
        activity.client.preview(false) {}; activity.client.send(Packet(Kind.CANCEL))
        SystemClock.sleep(500)
        fun findButton(view:View,text:String):android.widget.Button? {
            if(view is android.widget.Button && view.text.toString()==text) return view
            if(view is android.view.ViewGroup) for(i in 0 until view.childCount) findButton(view.getChildAt(i),text)?.let { return it }
            return null
        }
        inst.runOnMainSync { requireNotNull(findButton(activity.window.decorView,"查看辅助画面")).performClick() }
        await("主控端未实际解码画面",20000) { activity.videoFrames>=3 }
        screenshot("preview.png")
        inst.runOnMainSync { requireNotNull(findButton(activity.window.decorView,"关闭辅助画面")).performClick() }
        SystemClock.sleep(700)
        // 同一会话再次打开，覆盖视频 Socket 的关闭与替换。
        inst.runOnMainSync { requireNotNull(findButton(activity.window.decorView,"查看辅助画面")).performClick() }
        await("第二次开启预览未恢复",15000) { activity.videoFrames>=3 }
        inst.runOnMainSync { activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await("横屏布局未切换") { activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        await("横屏预览未恢复",20000) { activity.videoFrames>=3 && activity.pad.width>activity.pad.height && activity.window.decorView.width>activity.window.decorView.height && activity.pad.height>=80*activity.resources.displayMetrics.density }
        SystemClock.sleep(1200)
        screenshot("preview-landscape.png")
        inst.runOnMainSync { requireNotNull(findButton(activity.window.decorView,"关闭辅助画面")).performClick() }
        File(inst.targetContext.filesDir,"integration-result.json").writeText(JSONObject().put("connected",true).put("gesturesSent",true).put("previewFrames",3).put("landscape",true).put("displayVersion",activity.client.displayVersion).toString())
        inst.runOnMainSync { activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        inst.runOnMainSync { activity.client.disconnect() }
    }
}
