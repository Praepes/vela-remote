package dev.controlpad.receiver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.media.AudioManager
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import dev.controlpad.shared.*
import java.util.ArrayDeque
import kotlin.math.*

/** API 26+ 连续手势；API 24/25 记录有限路径，在抬手后提交完整手势。 */
class AccessibilityInput(private val service:RemoteAccessibilityService,private val emit:(Packet)->Unit) {
    private val main=Handler(Looper.getMainLooper())
    private val queue=ArrayDeque<Packet>(); private var scheduled=false
    @Volatile private var closed=false
    val ready get()=!closed && RemoteAccessibilityService.instance===service
    private var width=1280; private var height=720; private var display=1
    private var x=640f; private var y=360f; private var touchY=y; private var radius=60f
    private var mode=0; private var gesture=0L; private var started=0L
    private var paths=listOf<Path>(); private var targets=listOf<Pair<Float,Float>>()
    private var sent=listOf<Pair<Float,Float>>(); private var strokes:List<GestureDescription.StrokeDescription>?=null
    private var inFlight=false; private var ending=false; private var dirty=false; private var points=0
    private val deferred=ArrayDeque<Packet>()
    private fun cursor()=emit(Packet(Kind.CURSOR,display=display,x=x,y=y))
    private var lastError=""; private var lastErrorTime=0L
    @Synchronized private fun error(text:String) { val now=SystemClock.uptimeMillis(); if(text==lastError && now-lastErrorTime<1500) return; lastError=text; lastErrorTime=now; emit(Packet(Kind.ERROR,text=text)) }
    fun send(p:Packet) {
        synchronized(queue) {
            if(closed) return
            val last=queue.peekLast()
            if(p.kind==Kind.MOVE && last?.kind==Kind.MOVE && last.display==p.display) { queue.removeLast(); queue.addLast(p.copy(x=last.x+p.x,y=last.y+p.y)) }
            else if(queue.size<128) queue.addLast(p)
            else { queue.clear(); queue.addLast(Packet(Kind.CANCEL)); error("无障碍输入积压，已取消当前手势"); return }
            if(!scheduled) { scheduled=true; main.post(drain) }
        }
    }
    private val drain=object:Runnable { override fun run() {
        repeat(16) { val p=synchronized(queue) { queue.pollFirst() } ?: return@repeat; if(!closed) try { handle(p) } catch(e:Exception) { cancel(); error("无障碍输入失败：${e.message}") } }
        synchronized(queue) { if(queue.isEmpty()) scheduled=false else main.post(this) }
    } }
    private fun handle(p:Packet) {
        if(p.kind==Kind.CANCEL || p.kind==Kind.MODE) { deferred.clear(); cancel(); return }
        if(p.kind==Kind.DISPLAY) { deferred.clear(); cancel(); width=p.x.toInt().coerceIn(1,16384); height=p.y.toInt().coerceIn(1,16384); display=p.display; x=Geometry.clamp(x,width); y=Geometry.clamp(y,height); cursor(); return }
        if(p.kind in Kind.MOVE..Kind.PINCH && p.display!=display) { cancel(); return }
        if(ending || (mode==0 && inFlight)) { if(deferred.size<64) deferred.addLast(p) else { deferred.clear(); cancel(); error("无障碍手势忙碌，请稍后操作") }; return }
        if(p.kind in listOf(Kind.DRAG_MOVE,Kind.SCROLL,Kind.PINCH,Kind.END) && p.gesture!=gesture) return
        when(p.kind) {
            Kind.MOVE -> { x=Geometry.clamp(x+p.x,width); y=Geometry.clamp(y+p.y,height); cursor() }
            Kind.TAP -> {
                if(mode!=0) { deferred.addLast(p); cancel(); return }
                val tap=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x,y) },0,40)).build()
                inFlight=true
                if(!service.dispatchGesture(tap,callback,main)) { inFlight=false; error("系统拒绝无障碍点击") }
            }
            Kind.DRAG_BEGIN -> begin(1,p.gesture)
            Kind.SCROLL_BEGIN -> begin(2,p.gesture,p.flag)
            Kind.PINCH_BEGIN -> begin(3,p.gesture)
            Kind.DRAG_MOVE -> if(mode==1) { x=Geometry.clamp(x+p.x,width); y=Geometry.clamp(y+p.y,height); update(listOf(x to y)); cursor() }
            Kind.SCROLL -> if(mode==2) {
                val delta=p.y.coerceIn(-4096f,4096f)
                val room=(if(delta>0) height-2f-touchY else touchY-1f).coerceAtLeast(0f)
                val step=sign(delta)*min(abs(delta),room)
                touchY=Geometry.clamp(touchY+step,height); update(listOf(x to touchY))
                if(height>=5 && abs(delta-step)>0.01f) {
                    // 边缘结束当前触摸段，在屏幕中部重新落指，保留未消费的滚动量。
                    deferred.addFirst(p.copy(y=delta-step)); deferred.addFirst(Packet(Kind.SCROLL_BEGIN,gesture=gesture,display=display,flag=true))
                    ending=true; pump()
                }
            }
            Kind.PINCH -> if(mode==3) { radius=Geometry.pinchRadius(x,y,radius*p.x.coerceIn(0.25f,4f),width,height); update(listOf(x-radius to y,x+radius to y)) }
            Kind.END -> if(mode!=0) { ending=true; pump() }
            Kind.CENTER -> { x=width/2f; y=height/2f; cursor() }
            Kind.KEY -> if(p.flag) key(p.code)
        }
    }
    private fun begin(type:Int,id:Long,centerScroll:Boolean=false) {
        if(mode!=0) { error("上一段无障碍手势尚未结束"); cancel(); return }
        mode=type; gesture=id; started=SystemClock.uptimeMillis(); ending=false; dirty=true; points=0
        if(type==3) { x=x.coerceIn(1f,(width-2).coerceAtLeast(1).toFloat()); y=y.coerceIn(1f,(height-2).coerceAtLeast(1).toFloat()); cursor() }
        touchY=if(type==2 && centerScroll) height/2f else y; radius=Geometry.pinchRadius(x,y,min(width,height)*0.12f,width,height)
        targets=if(type==3) listOf(x-radius to y,x+radius to y) else listOf(x to if(type==2) touchY else y)
        sent=targets; strokes=null; paths=targets.map { (a,b)->Path().apply { moveTo(a,b) } }
        if(Build.VERSION.SDK_INT>=26) pump()
    }
    private fun update(next:List<Pair<Float,Float>>) {
        targets=next; dirty=true
        if(++points<=1024) next.forEachIndexed { i,(a,b)->paths[i].lineTo(a,b) }
        else { ending=true; error("手势过长，已结束本段，请重新落指") }
        if(Build.VERSION.SDK_INT>=26 && !inFlight) { main.removeCallbacks(pumpTask); main.postDelayed(pumpTask,24) }
    }
    private val pumpTask=Runnable { pump() }
    private fun pump() {
        if(inFlight || mode==0 || (!dirty && !ending)) return
        if(Build.VERSION.SDK_INT<26 && !ending) return
        val finish=ending; val current=targets.toList()
        val next=if(Build.VERSION.SDK_INT>=26) current.mapIndexed { i,(a,b)->
            val path=Path().apply { moveTo(sent[i].first,sent[i].second); lineTo(a,b) }
            strokes?.get(i)?.continueStroke(path,0,24,!finish) ?: GestureDescription.StrokeDescription(path,0,24,!finish)
        } else paths.map { GestureDescription.StrokeDescription(it,0,(SystemClock.uptimeMillis()-started).coerceIn(40,GestureDescription.getMaxGestureDuration())) }
        val description=GestureDescription.Builder().apply { next.forEach { addStroke(it) } }.build()
        dirty=false; inFlight=true
        val cb=object:AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g:GestureDescription?) { inFlight=false; sent=current; strokes=next; if(finish) reset() else pump(); replay() }
            override fun onCancelled(g:GestureDescription?) { inFlight=false; interrupted(); if(!closed) error("无障碍手势被系统或本地触摸中断") }
        }
        if(!service.dispatchGesture(description,cb,main)) { inFlight=false; reset(); error("系统拒绝无障碍手势"); replay() }
    }
    private val callback=object:AccessibilityService.GestureResultCallback() {
        override fun onCompleted(g:GestureDescription?) { inFlight=false; replay() }
        override fun onCancelled(g:GestureDescription?) { inFlight=false; interrupted() }
    }
    private fun reset() { mode=0; gesture=0; ending=false; dirty=false; strokes=null; paths=emptyList(); main.removeCallbacks(pumpTask) }
    private fun interrupted() { deferred.clear(); synchronized(queue) { queue.removeAll { it.kind in Kind.MOVE..Kind.MODE } }; reset() }
    private fun replay() { if(closed) { deferred.clear(); return }; while(!inFlight && !ending && deferred.isNotEmpty()) handle(deferred.removeFirst()) }
    fun cancel() {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { cancel() }; return }
        main.removeCallbacks(pumpTask)
        if(mode!=0 && Build.VERSION.SDK_INT>=26) { ending=true; dirty=true; pump() } else reset()
    }
    fun close() { synchronized(queue) { closed=true; queue.clear() }; main.post { deferred.clear(); cancel() } }
    private fun key(code:Int) {
        when(code) {
            KeyEvent.KEYCODE_BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            KeyEvent.KEYCODE_HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            KeyEvent.KEYCODE_VOLUME_UP,KeyEvent.KEYCODE_VOLUME_DOWN -> (service.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager).adjustVolume(if(code==KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,AudioManager.FLAG_SHOW_UI)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> (service.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager).run { dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code)); dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code)) }
            else -> {
                val root=service.rootInActiveWindow ?: return error("当前页面没有可访问的焦点节点")
                val focused=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: root
                if(code==KeyEvent.KEYCODE_DPAD_CENTER) { if(!focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)) error("目标控件不支持无障碍点击") }
                else { val direction=when(code) { 19->View.FOCUS_UP; 20->View.FOCUS_DOWN; 21->View.FOCUS_LEFT; 22->View.FOCUS_RIGHT; else->return }
                    val next=focused.focusSearch(direction); if(next==null || !(next.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))) error("目标页面不支持该方向的无障碍导航") }
            }
        }
    }
}
