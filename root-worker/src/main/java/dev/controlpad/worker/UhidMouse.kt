package dev.controlpad.worker

import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.view.InputDevice
import dev.controlpad.shared.HidReports
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** UHID_CREATE2 / UHID_INPUT2，使用 packed UAPI；不需要 NDK 或修改 SELinux。 */
class UhidMouse:AutoCloseable {
    private val fd:FileDescriptor=Os.open("/dev/uhid",OsConstants.O_RDWR or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK,0)
    private val closed=AtomicBoolean(false)
    private val ioLock=Any()
    init {
        try {
            val create=ByteBuffer.allocate(280+HidReports.descriptor.size).order(ByteOrder.nativeOrder())
            create.putInt(11); create.put("Vela Remote Mouse".toByteArray()); create.position(260)
            create.putShort(HidReports.descriptor.size.toShort()); create.putShort(0x06) // BUS_VIRTUAL
            create.putInt(0x1209); create.putInt(0x0001); create.putInt(1); create.putInt(0); create.put(HidReports.descriptor)
            write(create.array())
            thread(name="uhid-events",isDaemon=true) { readEvents() }
            val deadline=android.os.SystemClock.uptimeMillis()+1800
            while(android.os.SystemClock.uptimeMillis()<deadline && !registered()) android.os.SystemClock.sleep(30)
            check(registered()) { "内核未注册 Vela HID 鼠标" }
        } catch(e:Throwable) { close(); throw e }
    }
    private fun registered()=InputDevice.getDeviceIds().any { id->InputDevice.getDevice(id)?.let { it.name=="Vela Remote Mouse" && it.supportsSource(InputDevice.SOURCE_MOUSE) } ?: false }
    private fun write(data:ByteArray) { synchronized(ioLock) { check(!closed.get()); check(Os.write(fd,data,0,data.size)==data.size) { "UHID 写入不完整" } } }
    fun report(bytes:ByteArray) {
        val input=ByteBuffer.allocate(7+bytes.size).order(ByteOrder.nativeOrder())
        input.putInt(12); input.putShort((bytes.size+1).toShort()); input.put(1); input.put(bytes); write(input.array())
    }
    private fun readEvents() {
        val data=ByteArray(4376)
        while(!closed.get()) try {
            val size=Os.read(fd,data,0,data.size)
            if(size>=8) {
                val buffer=ByteBuffer.wrap(data).order(ByteOrder.nativeOrder()); val type=buffer.int
                if(type==9 || type==13) { // 此设备无 feature/output 状态，明确回复 EIO，避免内核等待。
                    val reply=ByteBuffer.allocate(if(type==9) 12 else 10).order(ByteOrder.nativeOrder())
                    reply.putInt(if(type==9) 10 else 14); reply.putInt(buffer.int); reply.putShort(5)
                    if(type==9) reply.putShort(0); write(reply.array())
                }
            } else android.os.SystemClock.sleep(20)
        } catch(e:ErrnoException) { if(e.errno==OsConstants.EAGAIN) android.os.SystemClock.sleep(15) else break }
        catch(_:Exception) { break }
    }
    override fun close() { synchronized(ioLock) {
        if(closed.compareAndSet(false,true)) { try { val destroy=ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(1).array(); Os.write(fd,destroy,0,4) } catch(_:Exception) {}; try { Os.close(fd) } catch(_:Exception) {} }
    } }
}
