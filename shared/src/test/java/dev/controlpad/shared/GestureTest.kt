package dev.controlpad.shared

import org.junit.Assert.*
import org.junit.Test

class GestureTest {
    private val out=mutableListOf<Packet>()
    private val g=GestureEngine(8f,500,300,500,out::add)
    private fun f(x:Float,y:Float,id:Int=0)=Finger(id,x,y)
    @Test fun movementNeverClicksAndCanReposition() {
        g.down(0,f(10f,10f)); g.fingers(30,listOf(f(30f,10f))); g.up(50)
        g.down(80,f(0f,0f)); g.fingers(90,listOf(f(20f,0f))); g.up(100)
        assertEquals(listOf(Kind.MOVE,Kind.MOVE),out.map{it.kind})
    }
    @Test fun doubleTapSendsTwoClicks() { g.down(0,f(1f,1f)); g.up(50); g.down(100,f(1f,1f)); g.up(150); assertEquals(listOf(Kind.TAP,Kind.TAP),out.map{it.kind}) }
    @Test fun tapThenDragOrdersDownMoveUp() {
        g.down(0,f(1f,1f)); g.up(50); g.down(100,f(1f,1f)); g.fingers(150,listOf(f(40f,1f))); g.up(200)
        assertEquals(listOf(Kind.TAP,Kind.DRAG_BEGIN,Kind.DRAG_MOVE,Kind.END),out.map{it.kind}); assertEquals(out[1].gesture,out.last().gesture)
    }
    @Test fun longHoldStartsDragWithoutClick() { g.down(0,f(1f,1f)); g.longPress(499); assertTrue(out.isEmpty()); g.longPress(500); g.fingers(700,listOf(f(30f,1f))); g.up(800); assertEquals(listOf(Kind.DRAG_BEGIN,Kind.DRAG_MOVE,Kind.END),out.map{it.kind}) }
    @Test fun twoFingerTranslationLocksScroll() {
        g.down(0,f(0f,0f)); g.fingers(10,listOf(f(0f,0f),f(100f,0f,1))); g.fingers(20,listOf(f(0f,20f),f(100f,20f,1)))
        g.fingers(30,listOf(f(-100f,40f),f(200f,40f,1))); g.fingers(40,listOf(f(0f,40f))); g.fingers(50,listOf(f(200f,40f))); g.up(60)
        assertEquals(listOf(Kind.SCROLL_BEGIN,Kind.SCROLL,Kind.SCROLL,Kind.END),out.map{it.kind})
    }
    @Test fun separationLocksPinch() {
        g.down(0,f(0f,0f)); g.fingers(10,listOf(f(0f,0f),f(100f,0f,1))); g.fingers(20,listOf(f(-20f,0f),f(120f,0f,1))); g.up(30)
        assertEquals(listOf(Kind.PINCH_BEGIN,Kind.PINCH,Kind.END),out.map{it.kind}); assertEquals(1.4f,out[1].x,0.01f)
    }
    @Test fun interruptedDragCancelsAndDoesNotResume() { g.down(0,f(0f,0f)); g.longPress(500); g.cancel(); g.up(700); assertEquals(listOf(Kind.DRAG_BEGIN,Kind.CANCEL),out.map{it.kind}) }
    @Test fun reverseScrollChangesSign() { g.naturalScroll=false; g.down(0,f(0f,0f)); g.fingers(10,listOf(f(0f,0f),f(100f,0f,1))); g.fingers(20,listOf(f(0f,20f),f(100f,20f,1))); assertEquals(-20f,out.last().y,0.01f) }
}
