package dev.controlpad.shared

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class ProtocolTest {
    @Test fun protobufGoldenWire() {
        assertArrayEquals(byteArrayOf(8,10,45,0,0,-128,63),Wire.encode(Packet(Kind.MOVE,x=1f)))
    }
    @Test fun roundtripUnicodeAndBinary() {
        val p=Packet(Kind.VIDEO_FRAME,sequence=Long.MAX_VALUE,gesture=73,display=9,x=-34.25f,y=1080f,code=2,flag=true,text="安卓八 · 已连接",blob=ByteArray(5000){it.toByte()},time=123456789)
        val actual=Wire.decode(Wire.encode(p)); assertArrayEquals(Wire.encode(p),Wire.encode(actual)); assertEquals(p.text,actual.text); assertEquals(p.sequence,actual.sequence); assertArrayEquals(p.blob,actual.blob)
    }
    @Test fun framedMultiplePackets() {
        val out=ByteArrayOutputStream(); Wire.write(out,Packet(Kind.TAP)); Wire.write(out,Packet(Kind.CANCEL))
        val input=ByteArrayInputStream(out.toByteArray()); assertEquals(Kind.TAP,Wire.read(input).kind); assertEquals(Kind.CANCEL,Wire.read(input).kind)
    }
    @Test fun unknownFieldIsSkipped() { assertEquals(Kind.TAP,Wire.decode(byteArrayOf(8,11,96,1)).kind) }
    @Test fun malformedPacketsRejected() {
        listOf(byteArrayOf(8,-128),byteArrayOf(74,127,0),byteArrayOf(13,0,0,0,0),byteArrayOf(0)).forEach { b->try { Wire.decode(b); fail("应拒绝非法消息") } catch(_:Exception) {} }
    }
    @Test fun excessiveLengthRejectedBeforeAllocation() {
        val out=ByteArrayOutputStream(); DataOutputStream(out).writeInt(Int.MAX_VALUE)
        try { Wire.read(ByteArrayInputStream(out.toByteArray())); fail() } catch(_:IllegalArgumentException) {}
    }
    @Test fun nonFiniteCoordinateRejected() { try { Wire.encode(Packet(Kind.MOVE,x=Float.NaN)); fail() } catch(_:IllegalArgumentException) {} }
    @Test fun coordinatesAndPinchStayInsideDisplay() {
        assertEquals(0f,Geometry.clamp(-1f,1280)); assertEquals(719f,Geometry.clamp(900f,720))
        assertEquals(9f,Geometry.pinchRadius(1270f,300f,100f,1280,720))
    }
}
