package dev.controlpad.receiver

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import dev.controlpad.shared.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RootBridge(private val context:Context,private val onPacket:(Packet)->Unit,private val onFailure:(String)->Unit) {
    @Volatile private var socket:LocalSocket?=null
    @Volatile private var process:Process?=null
    @Volatile var ready=false; private set
    @Volatile private var closed=false
    private val timer=Executors.newSingleThreadScheduledExecutor()
    fun start(width:Int,height:Int,software:Boolean) {
        val name="controlpad_${Security.randomToken().take(20)}"; val token=Security.randomToken()
        thread(name="root-start") {
            try {
                val apk=context.applicationInfo.sourceDir
                val nativeDir=context.applicationInfo.nativeLibraryDir
                require(!apk.contains('\'') && !nativeDir.contains('\''))
                val command="CLASSPATH='$apk' /system/bin/app_process / dev.controlpad.worker.RootMain $name $token $software $width $height ${android.os.Process.myUid()} '$nativeDir'"
                process=ProcessBuilder("su","-c",command).redirectErrorStream(true).start()
                val errors=StringBuilder()
                thread(name="root-log",isDaemon=true) { try { process?.inputStream?.bufferedReader()?.forEachLine { synchronized(errors) { if(errors.length<2048) errors.appendLine(it) } } } catch(_:Exception) {} }
                var candidate:LocalSocket?=null
                val until=SystemClock.uptimeMillis()+60000
                while(!closed && SystemClock.uptimeMillis()<until) {
                    val s=LocalSocket()
                    try { s.connect(LocalSocketAddress(name)); candidate=s; break } catch(_:Exception) { s.close() }
                    val p=process
                    if(p!=null) try { p.exitValue(); throw IllegalStateException("Root 授权被拒绝或 su 不可用：${synchronized(errors){errors.toString().take(200)}}") } catch(_:IllegalThreadStateException) {}
                    SystemClock.sleep(150)
                }
                if(closed) { candidate?.close(); return@thread }
                val s=candidate ?: throw IllegalStateException("Root 授权等待超时，请在 Magisk 中授权受控应用")
                require(s.peerCredentials.uid==0) { "本地工作进程不是 Root 身份" }
                s.soTimeout=5000; socket=s
                Wire.write(s.outputStream,Packet(Kind.AUTH,text=token))
                val reply=Wire.read(s.inputStream)
                require(reply.kind==Kind.AUTH_OK) { reply.text }; ready=true
                onPacket(reply)
                timer.scheduleAtFixedRate({ try { send(Packet(Kind.HEARTBEAT)) } catch(_:Exception) {} },0,1,TimeUnit.SECONDS)
                s.soTimeout=0
                while(!closed) onPacket(Wire.read(s.inputStream))
            } catch(e:Throwable) { if(!closed) onFailure(e.cause?.message ?: e.message ?: "Root 工作进程异常") }
            finally { ready=false; close() }
        }
    }
    @Synchronized fun send(p:Packet) { val s=socket ?: return; Wire.write(s.outputStream,p) }
    fun close() {
        closed=true; ready=false; timer.shutdownNow()
        try { socket?.close() } catch(_:Exception) {}; socket=null
        process?.destroy(); process=null
    }
}
