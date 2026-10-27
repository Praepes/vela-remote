package dev.controlpad.receiver

import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.*

class CursorOverlay(private val context:Context) {
    private val wm=context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var added=false
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
        params.x=x.toInt(); params.y=y.toInt()
        if(!added) { wm.addView(view,params); added=true } else wm.updateViewLayout(view,params)
    }
    fun hide() { if(added) { wm.removeView(view); added=false } }
}
