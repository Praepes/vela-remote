package dev.controlpad.worker

import android.net.LocalServerSocket
import android.os.Looper
import android.os.SystemClock
import dev.controlpad.shared.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** 通过 CLASSPATH=<receiver.apk> app_process 启动，绝不监听 TCP。 */
object RootMain {
    @JvmStatic fun main(args: Array<String>) {
        if(args.size!=7 || android.os.Process.myUid()!=0) return
        System.setProperty("controlpad.nativeLibraryDir",args[6])
        Looper.prepareMainLooper()
        val server=LocalServerSocket(args[0]); val peer=server.accept(); server.close()
        if(peer.peerCredentials.uid!=args[5].toInt()) { peer.close(); return }
        peer.soTimeout=4000
        val input=peer.inputStream; val output=peer.outputStream
        val auth=Wire.read(input)
        if(auth.kind!=Kind.AUTH || !Security.equalsSecret(auth.text,args[1])) { peer.close(); return }
        val last=AtomicLong(SystemClock.uptimeMillis())
        fun send(p:Packet) { Wire.write(output,p) }
        val injector=Injector(args[3].toInt(),args[4].toInt(),::send)
        val encoder=ScreenEncoder(args[2]=="true",::send)
        send(Packet(Kind.AUTH_OK,text=injector.nativeReason,code=if(injector.nativeSupported) Capability.NATIVE_MOUSE else 0,flag=injector.nativeMouse))
        val running=java.util.concurrent.atomic.AtomicBoolean(true)
        thread(name="root-watchdog",isDaemon=true) {
            while(running.get()) {
                SystemClock.sleep(500)
                if(SystemClock.uptimeMillis()-last.get()>3500) {
                    injector.close(); encoder.stop(); try { peer.close() } catch(_:Exception) {}; break
                }
            }
        }
        try {
            while(true) {
                val p=Wire.read(input); last.set(SystemClock.uptimeMillis())
                when(p.kind) {
                    Kind.HEARTBEAT -> {}
                    Kind.STOP -> break
                    Kind.PREVIEW -> if(p.flag) encoder.start(p.code!=0) else encoder.stop()
                    else -> injector.handle(p)
                }
            }
        } catch(e:Exception) { try { send(Packet(Kind.ERROR,text="Root 服务：${e.message}")) } catch(_:Exception) {} }
        finally { running.set(false); injector.close(); encoder.stop(); peer.close() }
    }
}
