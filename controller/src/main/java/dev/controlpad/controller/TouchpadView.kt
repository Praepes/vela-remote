package dev.controlpad.controller

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import dev.controlpad.shared.*

class TouchpadView(context:Context,private val ui:Ui,private val emit:(Packet)->Unit):View(context) {
    private val handler=Handler(Looper.getMainLooper())
    private val config=ViewConfiguration.get(context)
    val engine=GestureEngine(config.scaledTouchSlop.toFloat(),ViewConfiguration.getLongPressTimeout().toLong(),ViewConfiguration.getDoubleTapTimeout().toLong(),ViewConfiguration.getLongPressTimeout().toLong()) { p->
        if(p.kind==Kind.TAP || p.kind==Kind.DRAG_BEGIN) if(haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        emit(p)
    }
    var haptics=true
    var touchGestures=true
    var remoteEnabled=false
        set(value) { field=value; invalidate() }
    private var pressed=false; private var fingerX=0f; private var fingerY=0f
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val longPress=Runnable { engine.longPress(android.os.SystemClock.uptimeMillis()); invalidate() }
    init { contentDescription="遥控触控板"; isClickable=true; isFocusable=true; background=ui.rounded(ui.surface,28,Color.parseColor(if(ui.dark) "#303C4D" else "#E6EBF2")) }
    override fun onDraw(c:Canvas) {
        super.onDraw(c)
        paint.color=ui.muted; paint.textSize=12*resources.displayMetrics.scaledDensity; paint.textAlign=Paint.Align.CENTER
        c.drawText(if(remoteEnabled) if(engine.state==GestureEngine.State.DRAG) "正在拖动" else "单指移动 · 轻点选择" else "连接设备后开始操作",width/2f,ui.dp(30).toFloat(),paint)
        paint.color=if(pressed) ui.blue else Color.parseColor(if(ui.dark) "#3E506B" else "#DCE4F3")
        paint.style=Paint.Style.STROKE; paint.strokeWidth=ui.dp(1).toFloat()
        c.drawCircle(width/2f,height/2f,ui.dp(26).toFloat(),paint)
        c.drawLine(width/2f-ui.dp(9),height/2f,width/2f+ui.dp(9),height/2f,paint)
        c.drawLine(width/2f,height/2f-ui.dp(9),width/2f,height/2f+ui.dp(9),paint)
        paint.style=Paint.Style.FILL; paint.color=ui.muted; paint.textSize=11*resources.displayMetrics.scaledDensity
        if(height>ui.dp(150)) c.drawText(if(touchGestures) "双指滚动  ·  捏合缩放" else "双指滚轮  ·  缩放请切触摸",width/2f,height-ui.dp(22).toFloat(),paint)
        if(pressed) { paint.color=0x183269F5; c.drawCircle(fingerX,fingerY,ui.dp(28).toFloat(),paint) }
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(!remoteEnabled) return true
        val fingers=(0 until e.pointerCount).filter { !(e.actionMasked==MotionEvent.ACTION_POINTER_UP && it==e.actionIndex) }.map { Finger(e.getPointerId(it),e.getX(it),e.getY(it)) }
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); pressed=true; engine.down(e.eventTime,fingers[0]); handler.postDelayed(longPress,ViewConfiguration.getLongPressTimeout().toLong()) }
            MotionEvent.ACTION_MOVE -> { engine.fingers(e.eventTime,fingers); if(engine.state !in listOf(GestureEngine.State.ONE,GestureEngine.State.SECOND)) handler.removeCallbacks(longPress) }
            MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_POINTER_UP -> { handler.removeCallbacks(longPress); engine.fingers(e.eventTime,fingers) }
            MotionEvent.ACTION_UP -> { handler.removeCallbacks(longPress); engine.up(e.eventTime); pressed=false; parent.requestDisallowInterceptTouchEvent(false); performClick() }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        fingerX=e.x; fingerY=e.y; invalidate(); return true
    }
    override fun performClick():Boolean { super.performClick(); return true }
    fun cancel() { handler.removeCallbacks(longPress); engine.cancel(); pressed=false; invalidate() }
    override fun onDetachedFromWindow() { cancel(); super.onDetachedFromWindow() }
}
