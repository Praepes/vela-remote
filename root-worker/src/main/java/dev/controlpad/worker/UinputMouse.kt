package dev.controlpad.worker

import android.os.SystemClock
import android.view.InputDevice
import java.io.File

/** Root-only system mouse for older kernels without UHID_CREATE2. */
class UinputMouse:AutoCloseable {
    companion object {
        init { System.load(File(requireNotNull(System.getProperty("controlpad.nativeLibraryDir")),"libvela_input.so").absolutePath) }
    }
    private external fun openNative():Int
    private external fun reportNative(fd:Int,buttons:Int,changed:Int,x:Int,y:Int,wheel:Int)
    private external fun closeNative(fd:Int)
    private var fd=openNative()
    private var buttons=0
    init {
        try {
            val deadline=SystemClock.uptimeMillis()+1800
            while(SystemClock.uptimeMillis()<deadline && !registered()) SystemClock.sleep(20)
            check(registered()) { "Android 未注册 uinput 系统鼠标" }
        } catch(e:Throwable) { close(); throw e }
    }
    private fun registered()=InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.let { it.name=="Vela Remote Mouse" && it.supportsSource(InputDevice.SOURCE_MOUSE) } ?: false
    }
    @Synchronized fun report(bytes:ByteArray) {
        check(fd>=0)
        val next=bytes[0].toInt() and 7
        reportNative(fd,next,buttons xor next,bytes[1].toInt(),bytes[2].toInt(),bytes[3].toInt())
        buttons=next
    }
    @Synchronized override fun close() { if(fd>=0) { val old=fd; fd=-1; closeNative(old) } }
}
