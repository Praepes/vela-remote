package dev.controlpad.worker

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.controlpad.shared.*
import kotlin.math.*

class Injector(private var width:Int,private var height:Int,private val send:(Packet)->Unit) {
    private val clazz=Class.forName("android.hardware.input.InputManager")
    private val manager=clazz.getDeclaredMethod("getInstance").invoke(null)
    private val inject=clazz.getMethod("injectInputEvent",android.view.InputEvent::class.java,Int::class.javaPrimitiveType)
    private var x=width/2f; private var y=height/2f; private var display=1
    private var mode=0; private var downTime=0L; private var radius=60f
    private var touchX=x; private var touchY=y; private var gesture=0L
    private val keys=mutableMapOf<Int,Long>()
    private var mouseDevice:AutoCloseable?=null
    private var mouse:HidMouse?=null
    private var inputMode=InputMode.MOUSE
    var nativeReason=""; private set
    var nativeSupported=false; private set
    val nativeMouse get()=mouse!=null
    init { enableMouse() }
    private fun enableMouse() {
        if(mouse!=null) return
        try { val device=UhidMouse(); mouseDevice=device; mouse=HidMouse(device::report); nativeSupported=true; nativeReason="Root · 系统 HID 鼠标" }
        catch(uhidError:Throwable) {
            try { val device=UinputMouse(); mouseDevice=device; mouse=HidMouse(device::report); nativeSupported=true; nativeReason="Root · 系统鼠标（uinput 兼容）" }
            catch(e:Throwable) { nativeSupported=false; nativeReason="Root · 触摸兼容（UHID：${uhidError.cause?.message ?: uhidError.message}；uinput：${e.cause?.message ?: e.message}）" }
        }
    }
    private fun disableMouse() { try { mouse?.cancel() } catch(_:Exception) {}; mouse=null; mouseDevice?.close(); mouseDevice=null }
    private fun capabilities()=send(Packet(Kind.CAPABILITIES,code=Capability.TOUCH or Capability.PREVIEW or (if(nativeSupported) Capability.NATIVE_MOUSE else 0),flag=nativeMouse,text=when(inputMode) { InputMode.TOUCH->"Root · 触摸手势"; InputMode.DPAD->"Root · 方向导航"; else->nativeReason }))
    private fun injectEvent(e:android.view.InputEvent) {
        val ok=inject.invoke(manager,e,0) as Boolean
        if(!ok) throw IllegalStateException("系统拒绝输入注入")
    }
    private fun cursor()=send(Packet(Kind.CURSOR,display=display,x=x,y=y))
    private fun touch(action:Int,count:Int=1) {
        val now=SystemClock.uptimeMillis()
        if(action==MotionEvent.ACTION_DOWN) downTime=now
        val props=Array(count) { i -> MotionEvent.PointerProperties().apply { id=i; toolType=MotionEvent.TOOL_TYPE_FINGER } }
        val coords=Array(count) { i -> MotionEvent.PointerCoords().apply {
            if(count==1) { x=touchX; y=touchY }
            else { x=Geometry.clamp(this@Injector.x+(if(i==0) -radius else radius),width); y=this@Injector.y }
            pressure=if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) 0f else 1f; size=0.05f
        } }
        val event=MotionEvent.obtain(downTime,now,action,count,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
        try { injectEvent(event) } finally { event.recycle() }
    }
    @Synchronized fun cancel() {
        try { mouse?.cancel() } catch(_:Exception) {}
        if(mode!=0) try { touch(MotionEvent.ACTION_CANCEL,if(mode==3) 2 else 1) } catch(_:Exception) {}
        mode=0; gesture=0
        keys.keys.toList().forEach { key -> try { key(key,false) } catch(_:Exception) {} }
        keys.clear()
    }
    private fun key(code:Int,down:Boolean) {
        require(code in setOf(KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_HOME,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_VOLUME_UP,KeyEvent.KEYCODE_VOLUME_DOWN,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER)) { "不允许的按键" }
        val now=SystemClock.uptimeMillis(); val start=if(down) keys.getOrPut(code) { now } else keys.remove(code) ?: return
        injectEvent(KeyEvent(start,now,if(down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,code,0,0,-1,0,0,InputDevice.SOURCE_KEYBOARD))
    }
    @Synchronized fun handle(p:Packet) {
        if(p.kind==Kind.DISPLAY) {
            cancel(); width=p.x.toInt().coerceIn(1,16384); height=p.y.toInt().coerceIn(1,16384); display=p.display
            x=Geometry.clamp(x,width); y=Geometry.clamp(y,height); cursor(); return
        }
        if(p.kind in Kind.MOVE..Kind.PINCH && p.display!=display) { cancel(); return }
        if(p.kind in listOf(Kind.DRAG_MOVE,Kind.SCROLL,Kind.PINCH,Kind.END) && p.gesture!=gesture) return
        if(p.kind==Kind.MODE) {
            cancel(); inputMode=if(!p.flag) InputMode.DPAD else p.code
            if(inputMode==InputMode.MOUSE) enableMouse() else disableMouse()
            capabilities(); return
        }
        val native=mouse
        if(native!=null) {
            when(p.kind) {
                Kind.MOVE -> { native.move(p.x,p.y); return }
                Kind.TAP -> { cancel(); native.click(); return }
                Kind.DRAG_BEGIN -> { cancel(); gesture=p.gesture; native.down(); return }
                Kind.DRAG_MOVE -> { native.move(p.x,p.y); return }
                Kind.SCROLL_BEGIN -> { cancel(); gesture=p.gesture; return }
                Kind.SCROLL -> { native.scroll(p.y); return }
                Kind.END,Kind.CANCEL -> { cancel(); return }
                Kind.PINCH_BEGIN,Kind.PINCH -> { cancel(); send(Packet(Kind.ERROR,text="系统鼠标模式不提供双指缩放，请切换触摸模式")); return }
                Kind.CENTER -> { send(Packet(Kind.ERROR,text="系统鼠标位置由 Android 管理，请切换触摸模式使用光标居中")); return }
            }
        }
        when(p.kind) {
            Kind.MOVE -> { x=Geometry.clamp(x+p.x.coerceIn(-2048f,2048f),width); y=Geometry.clamp(y+p.y.coerceIn(-2048f,2048f),height); cursor() }
            Kind.TAP -> { cancel(); touchX=x; touchY=y; touch(MotionEvent.ACTION_DOWN); touch(MotionEvent.ACTION_UP) }
            Kind.DRAG_BEGIN -> { cancel(); gesture=p.gesture; mode=1; touchX=x; touchY=y; touch(MotionEvent.ACTION_DOWN) }
            Kind.DRAG_MOVE -> if(mode==1) {
                x=Geometry.clamp(x+p.x.coerceIn(-2048f,2048f),width); y=Geometry.clamp(y+p.y.coerceIn(-2048f,2048f),height)
                touchX=x; touchY=y; touch(MotionEvent.ACTION_MOVE); cursor()
            }
            Kind.SCROLL_BEGIN -> { cancel(); gesture=p.gesture; mode=2; touchX=x; touchY=y; touch(MotionEvent.ACTION_DOWN) }
            Kind.SCROLL -> if(mode==2) {
                var remain=p.y.coerceIn(-4096f,4096f)
                while(abs(remain)>0.01f) {
                    val edge=if(remain>0) height-2f else 1f
                    val room=if(remain>0) edge-touchY else touchY-edge
                    if(room<1f) {
                        touch(MotionEvent.ACTION_UP); touchY=height/2f; touch(MotionEvent.ACTION_DOWN)
                    }
                    val delta=sign(remain)*min(abs(remain),abs(edge-touchY))
                    touchY=Geometry.clamp(touchY+delta,height); touch(MotionEvent.ACTION_MOVE); remain-=delta
                    if(height<5) break
                }
            }
            Kind.PINCH_BEGIN -> {
                cancel(); gesture=p.gesture; mode=3
                x=x.coerceIn(2f,(width-3f).coerceAtLeast(2f)); y=y.coerceIn(2f,(height-3f).coerceAtLeast(2f)); cursor()
                radius=Geometry.pinchRadius(x,y,min(width,height)*0.12f,width,height)
                touchX=x-radius; touchY=y; touch(MotionEvent.ACTION_DOWN)
                touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2)
            }
            Kind.PINCH -> if(mode==3) { radius=Geometry.pinchRadius(x,y,radius*p.x.coerceIn(0.25f,4f),width,height); touch(MotionEvent.ACTION_MOVE,2) }
            Kind.END -> {
                if(mode==3) { touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2); touchX=x-radius; touchY=y }
                if(mode!=0) touch(MotionEvent.ACTION_UP)
                mode=0; gesture=0
            }
            Kind.CANCEL -> cancel()
            Kind.CENTER -> { cancel(); x=width/2f; y=height/2f; cursor() }
            Kind.KEY -> key(p.code,p.flag)
            Kind.MODE -> cancel()
        }
    }
    @Synchronized fun close() { cancel(); disableMouse() }
}
