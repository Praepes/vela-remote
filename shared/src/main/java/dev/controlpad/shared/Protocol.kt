package dev.controlpad.shared

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Kind {
    const val HELLO = 1; const val AUTH = 2; const val AUTH_OK = 3; const val ERROR = 4
    const val PING = 5; const val PONG = 6; const val DISPLAY = 7; const val CURSOR = 8
    const val MOVE = 10; const val TAP = 11; const val DRAG_BEGIN = 12; const val DRAG_MOVE = 13
    const val END = 14; const val CANCEL = 15; const val SCROLL_BEGIN = 16; const val SCROLL = 17
    const val PINCH_BEGIN = 18; const val PINCH = 19; const val KEY = 20; const val CENTER = 21; const val MODE = 22
    const val PREVIEW = 30; const val VIDEO_AUTH = 31; const val VIDEO_FORMAT = 32; const val VIDEO_FRAME = 33
    const val STATUS = 34; const val STOP = 35; const val HEARTBEAT = 36; const val REVOKE = 37
    const val PAIR_ONE = 40; const val PAIR_TWO = 41; const val PAIR_CONFIRM = 42
    const val CAPABILITIES = 43
    const val VERSION = 1; const val CONTROL_PORT = 27183; const val VIDEO_PORT = 27184
    const val SERVICE_TYPE = "_controlpad._tcp."
}

object InputMode { const val MOUSE=0; const val TOUCH=1; const val DPAD=2 }
object Capability {
    const val NATIVE_MOUSE=1; const val TOUCH=2; const val PREVIEW=4
    const val ACCESSIBILITY=8; const val BLUETOOTH=16
}

data class Packet(
    val kind: Int = 0, val sequence: Long = 0, val gesture: Long = 0, val display: Int = 0,
    val x: Float = 0f, val y: Float = 0f, val code: Int = 0, val flag: Boolean = false,
    val text: String = "", val blob: ByteArray = byteArrayOf(), val time: Long = 0
)

/** 与 control.proto 对应的轻量 protobuf 编解码，避免 Root 进程依赖反射运行库。 */
object Wire {
    const val MAX_SIZE = 4 * 1024 * 1024
    private fun varint(out: OutputStream, value: Long) {
        var v = value
        while (v and -128L != 0L) { out.write((v.toInt() and 127) or 128); v = v ushr 7 }
        out.write(v.toInt())
    }
    private fun readVarint(input: InputStream): Long {
        var result = 0L
        for (shift in 0..63 step 7) {
            val b = input.read(); if (b < 0) throw EOFException()
            if (shift == 63 && b > 1) throw IOException("非法 varint")
            result = result or ((b and 127).toLong() shl shift)
            if (b and 128 == 0) return result
        }
        throw IOException("非法 varint")
    }
    fun encode(p: Packet): ByteArray {
        val out = ByteArrayOutputStream()
        fun number(field: Int, v: Long) { if (v != 0L) { varint(out, (field shl 3).toLong()); varint(out, v) } }
        fun float(field: Int, v: Float) { if (v != 0f) { require(v.isFinite()); varint(out, ((field shl 3) or 5).toLong()); out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()) } }
        fun bytes(field: Int, v: ByteArray) { if (v.isNotEmpty()) { varint(out, ((field shl 3) or 2).toLong()); varint(out, v.size.toLong()); out.write(v) } }
        number(1, p.kind.toLong()); number(2, p.sequence); number(3, p.gesture); number(4, p.display.toLong())
        float(5, p.x); float(6, p.y); number(7, p.code.toLong()); number(8, if(p.flag) 1 else 0)
        bytes(9, p.text.toByteArray(Charsets.UTF_8)); bytes(10, p.blob); number(11, p.time)
        return out.toByteArray().also { require(it.size <= MAX_SIZE) }
    }
    fun decode(bytes: ByteArray): Packet {
        require(bytes.size <= MAX_SIZE)
        val input = ByteArrayInputStream(bytes)
        var p = Packet()
        while (input.available() > 0) {
            val tag = readVarint(input).toInt(); val field = tag ushr 3; require(field > 0)
            val type = tag and 7
            var v = 0L; var b = byteArrayOf(); var f = 0f
            when(type) {
                0 -> v = readVarint(input)
                1 -> { val discard = ByteArray(8); DataInputStream(input).readFully(discard) }
                2 -> { val size = readVarint(input); require(size in 0..input.available().toLong()); b = ByteArray(size.toInt()); DataInputStream(input).readFully(b) }
                5 -> { b = ByteArray(4); DataInputStream(input).readFully(b); f = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).float }
                else -> throw IOException("不支持的 wire type")
            }
            if (field in 1..11) {
                val expected = when(field) { 5,6 -> 5; 9,10 -> 2; else -> 0 }
                require(type == expected) { "字段类型错误" }
            }
            p = when(field) {
                1 -> p.copy(kind=v.toInt()); 2 -> p.copy(sequence=v); 3 -> p.copy(gesture=v); 4 -> p.copy(display=v.toInt())
                5 -> p.copy(x=f); 6 -> p.copy(y=f); 7 -> p.copy(code=v.toInt()); 8 -> p.copy(flag=v != 0L)
                9 -> p.copy(text=b.toString(Charsets.UTF_8)); 10 -> p.copy(blob=b); 11 -> p.copy(time=v); else -> p
            }
        }
        require(p.x.isFinite() && p.y.isFinite()) { "坐标不是有限数值" }
        return p
    }
    fun read(input: InputStream): Packet {
        val data = DataInputStream(input); val size = data.readInt()
        require(size in 1..MAX_SIZE) { "消息长度错误：$size" }
        return decode(ByteArray(size).also { data.readFully(it) })
    }
    fun write(output: OutputStream, packet: Packet) {
        val b = encode(packet); synchronized(output) { val d = DataOutputStream(output); d.writeInt(b.size); d.write(b); d.flush() }
    }
}

/** 所有坐标均在受控端的当前逻辑显示坐标系内。 */
object Geometry {
    fun clamp(value: Float, extent: Int): Float = value.coerceIn(0f, (extent - 1).coerceAtLeast(0).toFloat())
    fun pinchRadius(cx: Float, cy: Float, requested: Float, width: Int, height: Int): Float =
        requested.coerceAtLeast(1f).coerceAtMost(minOf(cx, cy, width - 1f - cx, height - 1f - cy).coerceAtLeast(1f))
}
