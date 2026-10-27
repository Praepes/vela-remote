package dev.controlpad.receiver

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*

class CursorOverlay(private val context:Context) {
    private val idleTimeoutMs=30_000L
    private val handler=Handler(Looper.getMainLooper())
    private var lastActivity=0L
    private var hideScheduled=false
    private val wm=context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var added=false
    internal val isVisible get()=added
    private val idleHide=object:Runnable {
        override fun run() {
            hideScheduled=false
            val remaining=idleTimeoutMs-(SystemClock.uptimeMillis()-lastActivity)
            if(added && remaining>0) scheduleHide(remaining) else hide()
        }
    }
    private val view=object:View(context) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas) {
            val p=Path().apply { moveTo(3f,2f); lineTo(3f,29f); lineTo(10f,23f); lineTo(16f,35f); lineTo(22f,32f); lineTo(16f,21f); lineTo(26f,20f); close() }
            paint.color=Color.WHITE; paint.style=Paint.Style.STROKE; paint.strokeWidth=4f; c.drawPath(p,paint)
            paint.color=Color.rgb(36,52,76); paint.style=Paint.Style.FILL; c.drawPath(p,paint)
        }
    }
    @Suppress("DEPRECATION") private val params=WindowManager.LayoutParams(40,44,
        if(Build.VERSION.SDK_INT>=26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.LEFT }
    fun show(x:Float,y:Float) {
        val remaining=idleTimeoutMs-(SystemClock.uptimeMillis()-lastActivity)
        if(remaining<=0) { hide(); return }
        val changed=params.x!=x.toInt() || params.y!=y.toInt()
        params.x=x.toInt(); params.y=y.toInt()
        if(!added) { wm.addView(view,params); added=true } else if(changed) wm.updateViewLayout(view,params)
        scheduleHide(remaining)
    }
    /** Called for user input, never for heartbeats or cursor/status feedback. */
    fun activity() { lastActivity=SystemClock.uptimeMillis() }
    private fun scheduleHide(delay:Long) { if(!hideScheduled) { hideScheduled=true; handler.postDelayed(idleHide,delay) } }
    fun hide() {
        handler.removeCallbacks(idleHide); hideScheduled=false
        if(added) { wm.removeView(view); added=false }
    }
}
