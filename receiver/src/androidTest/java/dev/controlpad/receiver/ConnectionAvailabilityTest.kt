package dev.controlpad.receiver

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionAvailabilityTest {
    @Test fun stoppedServiceHidesQrAndRestartRestoresIt() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val activity=inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val service=Intent(activity,ReceiverService::class.java)
        fun await(message:String,test:()->Boolean) {
            val deadline=SystemClock.uptimeMillis()+20000
            while(!test() && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(50)
            assertTrue(message,test())
        }
        fun qr(view:View):ImageView? {
            if(view is ImageView && view.contentDescription=="主控端扫描此二维码配对") return view
            if(view is android.view.ViewGroup) for(i in 0 until view.childCount) qr(view.getChildAt(i))?.let { return it }
            return null
        }
        try {
            inst.runOnMainSync { activity.stopService(service) }
            await("停止后仍宣称可连接") { ReceiverState.service==null && !ReceiverState.connectable }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val image=requireNotNull(qr(activity.window.decorView))
                assertEquals(View.GONE,image.visibility)
                assertNull("服务停止后仍显示旧二维码",image.drawable)
            }
            inst.runOnMainSync { if(android.os.Build.VERSION.SDK_INT>=26) activity.startForegroundService(service) else activity.startService(service) }
            await("重启后输入与网络服务未就绪") { ReceiverState.connectable }
            await("服务就绪后界面未显示二维码") {
                var shown=false
                inst.runOnMainSync { val image=requireNotNull(qr(activity.window.decorView)); shown=image.visibility==View.VISIBLE && image.drawable!=null }
                shown
            }
            inst.runOnMainSync {
                val image=requireNotNull(qr(activity.window.decorView))
                assertEquals(View.VISIBLE,image.visibility)
                assertNotNull(image.drawable)
            }
        } finally {
            inst.runOnMainSync {
                if(ReceiverState.service==null) {
                    if(android.os.Build.VERSION.SDK_INT>=26) activity.startForegroundService(service) else activity.startService(service)
                }
                activity.finish()
            }
        }
    }
}
