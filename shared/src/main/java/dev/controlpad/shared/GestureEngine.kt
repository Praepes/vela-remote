package dev.controlpad.shared

import kotlin.math.*

data class Finger(val id: Int, val x: Float, val y: Float)

/** 无 Android 依赖的触控状态机。时间由调用端传入，输出语义消息。 */
class GestureEngine(
    private val slop: Float, private val tapTimeout: Long, private val doubleTimeout: Long,
    private val longTimeout: Long, private val emit: (Packet) -> Unit
) {
    enum class State { IDLE, ONE, MOVE, SECOND, DRAG, TWO, SCROLL, PINCH, WAIT_UP }
    var state = State.IDLE; private set
    var speed = 1f; var acceleration = 0.25f; var scrollSpeed = 1f; var naturalScroll = true
    private var gesture = 0L; private var downTime = 0L; private var lastTap = -10000L
    private var start = Finger(0,0f,0f); private var prev = start
    private var centerX = 0f; private var centerY = 0f; private var startCenterY = 0f
    private var span = 1f; private var startSpan = 1f; private var firstId = -1
    private fun send(kind: Int, x: Float = 0f, y: Float = 0f) = emit(Packet(kind, gesture=gesture, x=x, y=y))
    fun down(time: Long, finger: Finger) {
        cancel(); gesture++; downTime=time; start=finger; prev=finger; firstId=finger.id
        state=if(time-lastTap in 0..doubleTimeout) State.SECOND else State.ONE
    }
    fun longPress(time: Long) {
        if ((state == State.ONE || state == State.SECOND) && time-downTime >= longTimeout) {
            send(Kind.DRAG_BEGIN); state=State.DRAG; lastTap=-10000
        }
    }
    fun fingers(time: Long, fingers: List<Finger>) {
        if(fingers.size >= 2) {
            val a=fingers[0]; val b=fingers[1]
            val cx=(a.x+b.x)/2; val cy=(a.y+b.y)/2; val distance=hypot(a.x-b.x,a.y-b.y).coerceAtLeast(1f)
            if(state !in listOf(State.TWO,State.SCROLL,State.PINCH,State.WAIT_UP)) {
                if(state == State.DRAG) send(Kind.CANCEL)
                state=State.TWO; centerX=cx; centerY=cy; startCenterY=cy; span=distance; startSpan=distance; lastTap=-10000
                return
            }
            if(state==State.TWO) {
                val scale=abs(distance-startSpan); val scroll=abs(cy-startCenterY)
                if(max(scale,scroll) <= slop) return
                if(scale > scroll) { state=State.PINCH; send(Kind.PINCH_BEGIN) }
                else { state=State.SCROLL; send(Kind.SCROLL_BEGIN) }
            }
            when(state) {
                State.SCROLL -> send(Kind.SCROLL, 0f, (cy-centerY)*scrollSpeed*(if(naturalScroll) 1 else -1))
                State.PINCH -> send(Kind.PINCH, distance/span)
                else -> {}
            }
            centerX=cx; centerY=cy; span=distance
            return
        }
        if(state in listOf(State.TWO,State.SCROLL,State.PINCH)) {
            send(Kind.END); state=State.WAIT_UP; return
        }
        val f=fingers.firstOrNull { it.id==firstId } ?: return
        val distance=hypot(f.x-start.x,f.y-start.y)
        if((state==State.ONE || state==State.SECOND) && distance > slop) {
            if(state==State.SECOND) { state=State.DRAG; send(Kind.DRAG_BEGIN); lastTap=-10000 }
            else state=State.MOVE
        }
        if(state==State.MOVE || state==State.DRAG) {
            val dx=f.x-prev.x; val dy=f.y-prev.y
            val gain=speed*(1f+acceleration*(hypot(dx,dy)/12f).coerceAtMost(2f))
            send(if(state==State.DRAG) Kind.DRAG_MOVE else Kind.MOVE, dx*gain,dy*gain)
        }
        prev=f
    }
    fun up(time: Long) {
        when(state) {
            State.ONE,State.SECOND -> if(time-downTime <= tapTimeout) { send(Kind.TAP); lastTap=time } else lastTap=-10000
            State.DRAG,State.SCROLL,State.PINCH -> { send(Kind.END); lastTap=-10000 }
            else -> {}
        }
        state=State.IDLE
    }
    fun cancel() { if(state!=State.IDLE) send(Kind.CANCEL); state=State.IDLE }
}
