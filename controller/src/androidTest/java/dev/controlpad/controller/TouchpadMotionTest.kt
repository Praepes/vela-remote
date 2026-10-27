package dev.controlpad.controller

import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.controlpad.shared.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TouchpadMotionTest {
    private fun checkMotion(acceleration:Float,batched:Boolean):Float {
        val inst=InstrumentationRegistry.getInstrumentation()
        var distance=0f
        inst.runOnMainSync {
            val context=inst.targetContext
            val density=context.resources.displayMetrics.density
            val pad=TouchpadView(context,Ui(context)) { if(it.kind==Kind.MOVE) distance+=it.x }
            FrameLayout(context).addView(pad)
            pad.remoteEnabled=true; pad.haptics=false; pad.engine.speed=1f; pad.engine.acceleration=acceleration
            fun touch(time:Long,action:Int,x:Float) {
                val event=MotionEvent.obtain(0,time,action,x*density,0f,0)
                try { pad.onTouchEvent(event) } finally { event.recycle() }
            }
            touch(0,MotionEvent.ACTION_DOWN,0f)
            if(batched) {
                val event=MotionEvent.obtain(0,8,MotionEvent.ACTION_MOVE,8f*density,0f,0)
                event.addBatch(24,16f*density,0f,1f,1f,0)
                try { pad.onTouchEvent(event) } finally { event.recycle() }
                touch(25,MotionEvent.ACTION_UP,16f)
            } else {
                touch(10,MotionEvent.ACTION_MOVE,10f)
                touch(20,MotionEvent.ACTION_UP,20f)
            }
            pad.cancel()
        }
        return distance
    }
    @Test fun densityDoesNotAmplifyMovementAndLiftKeepsTheLastDelta() {
        assertEquals(20f,checkMotion(0f,false),0.001f)
    }
    @Test fun batchedSamplesKeepTheirOwnVelocity() {
        assertEquals(24f,checkMotion(0.5f,true),0.001f)
    }
}
