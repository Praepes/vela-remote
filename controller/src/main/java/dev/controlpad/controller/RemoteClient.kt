package dev.controlpad.controller

import android.content.Context
import android.os.SystemClock
import dev.controlpad.shared.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.*
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

data class Device(val host:String,val port:Int=Kind.CONTROL_PORT,val pin:String,val token:String,val name:String="安卓设备",val pairing:Boolean=false)

class RemoteClient(private val context:Context,private val notify:(Packet)->Unit) {
    private class AuthenticationFailure(message:String):Exception(message)
    private val prefs=context.getSharedPreferences("devices",Context.MODE_PRIVATE)
    private val id=prefs.getString("controller_id",null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("controller_id",it).apply() }
    private val queue=ArrayDeque<Packet>(); private val lock=Object()
    private val timer=Executors.newSingleThreadScheduledExecutor()
    @Volatile private var control:SSLSocket?=null
    @Volatile private var media:SSLSocket?=null
    @Volatile private var mediaEpoch=0
    @Volatile private var intentional=true
    @Volatile private var closed=false
    @Volatile private var bluetooth:BluetoothRemote?=null
    @Volatile var capabilities=Capability.TOUCH or Capability.PREVIEW; private set
    @Volatile var nativeMouse=false; private set
    val isBluetooth get()=bluetooth!=null
    val supportsPreview get()=capabilities and Capability.PREVIEW!=0
    val supportsTouch get()=capabilities and Capability.TOUCH!=0
    @Volatile private var epoch=0
    @Volatile var connected=false; private set
    @Volatile var displayVersion=0; private set
    @Volatile var device:Device?=null; private set
    @Volatile private var session=""
    @Volatile private var lastReceived=0L
    private var attempt=0
    init {
        timer.scheduleAtFixedRate({
            if(connected && bluetooth==null) {
                if(SystemClock.elapsedRealtime()-lastReceived>3500) lost(epoch,"连接超时，正在重新连接")
                else send(Packet(Kind.PING,time=SystemClock.elapsedRealtime()))
            }
        },750,750,TimeUnit.MILLISECONDS)
    }
    fun saved():List<Device> = try {
        val a=JSONArray(prefs.getString("saved","[]")); (0 until a.length()).map { i->val o=a.getJSONObject(i); Device(o.getString("host"),o.getInt("port"),o.getString("pin"),o.getString("token"),o.getString("name")) }
    } catch(_:Exception) { emptyList() }
    private fun save(d:Device) {
        val devices=(listOf(d)+saved().filter { it.pin!=d.pin }).take(10)
        val a=JSONArray(); devices.forEach { a.put(JSONObject().put("host",it.host).put("port",it.port).put("pin",it.pin).put("token",it.token).put("name",it.name)) }; prefs.edit().putString("saved",a.toString()).apply()
    }
    fun forget() { disconnect(); prefs.edit().remove("saved").apply(); device=null }
    fun connect(d:Device) {
        disconnect(); if(closed) return
        capabilities=Capability.TOUCH or Capability.PREVIEW; nativeMouse=false
        intentional=false; device=d; attempt=0; open(d,epoch)
    }
    private fun open(d:Device,generation:Int) {
        notify(Packet(Kind.STATUS,text="正在连接 ${d.host}"))
        thread(name="controller-connect") {
            var socket:SSLSocket?=null
            try {
                var fingerprint=d.pin
                val codePairing=d.pairing && d.pin.isEmpty()
                val ssl=if(codePairing) Security.codePairingContext { fingerprint=it } else Security.pinnedContext(d.pin) { fingerprint=it }
                socket=ssl.socketFactory.createSocket() as SSLSocket
                Security.harden(socket); socket.connect(InetSocketAddress(d.host,d.port),5000); socket.soTimeout=5000; socket.startHandshake()
                val hello=Wire.read(socket.inputStream); if(hello.kind!=Kind.HELLO || hello.code!=Kind.VERSION) throw AuthenticationFailure("协议版本不兼容")
                if(codePairing) {
                    if(!hello.flag) throw AuthenticationFailure("请先更新受控端，再使用六位码配对")
                    notify(Packet(Kind.STATUS,text="正在验证配对码…"))
                    socket.soTimeout=15000
                    try {
                        val exchange=CodePairing(id,d.token)
                        Wire.write(socket.outputStream,Packet(Kind.PAIR_ONE,code=Kind.VERSION,text=id,blob=exchange.roundOne()))
                        fun receive(kind:Int):Packet { val p=Wire.read(socket.inputStream); require(p.kind==kind) { p.text.ifEmpty { "配对失败，请重试" } }; return p }
                        exchange.acceptOne(receive(Kind.PAIR_ONE).blob)
                        Wire.write(socket.outputStream,Packet(Kind.PAIR_TWO,blob=exchange.roundTwo()))
                        exchange.acceptTwo(receive(Kind.PAIR_TWO).blob)
                        Wire.write(socket.outputStream,Packet(Kind.PAIR_CONFIRM,blob=exchange.confirm(fingerprint,"controller")))
                        exchange.verify(receive(Kind.PAIR_CONFIRM).blob,fingerprint,"receiver")
                    } catch(_:Exception) { throw AuthenticationFailure("配对码不正确、已更新或连接中断，请核对后重试") }
                } else Wire.write(socket.outputStream,Packet(Kind.AUTH,code=Kind.VERSION,text=id,blob=d.token.toByteArray(),flag=d.pairing))
                val auth=Wire.read(socket.inputStream); if(auth.kind!=Kind.AUTH_OK) throw AuthenticationFailure(auth.text.ifEmpty { "认证失败，请重新配对" })
                synchronized(this) {
                    if(generation!=epoch || intentional) { socket.close(); return@thread }
                    val stored=d.copy(pin=fingerprint,token=auth.blob.toString(Charsets.UTF_8),name=hello.text,pairing=false)
                    device=stored; save(stored); session=auth.text; control=socket; connected=true; attempt=0; lastReceived=SystemClock.elapsedRealtime()
                }
                val channel=socket; channel.soTimeout=4000
                notify(Packet(Kind.AUTH_OK,text=hello.text)); startWriter(channel,generation)
                while(connected && epoch==generation && control===channel) {
                    val p=Wire.read(channel.inputStream); lastReceived=SystemClock.elapsedRealtime()
                    if(p.kind==Kind.DISPLAY) { displayVersion=p.display; synchronized(lock) { queue.clear() } }
                    if(p.kind==Kind.CAPABILITIES) { capabilities=p.code; nativeMouse=p.flag }
                    notify(if(p.kind==Kind.PONG) p.copy(time=SystemClock.elapsedRealtime()-p.time) else p)
                }
            } catch(e:Exception) {
                try { socket?.close() } catch(_:Exception) {}
                val message=e.cause?.message ?: e.message ?: "连接失败"
                android.util.Log.w("ControlPad","连接结束：$message")
                if(d.pairing || e is AuthenticationFailure || e is javax.net.ssl.SSLHandshakeException) { if(epoch==generation) { intentional=true; notify(Packet(Kind.ERROR,text=message)); lost(generation,message) } }
                else lost(generation,message)
            }
        }
    }
    private fun startWriter(socket:SSLSocket,generation:Int) {
        thread(name="controller-writer") {
            var sequence=0L
            try {
                while(connected && epoch==generation) {
                    val p=synchronized(lock) { while(queue.isEmpty() && connected && epoch==generation) lock.wait(500); if(queue.isEmpty()) null else queue.removeFirst() } ?: continue
                    if(epoch!=generation) break
                    Wire.write(socket.outputStream,p.copy(sequence=++sequence))
                }
            } catch(e:Exception) { lost(generation,e.message ?: "发送失败") }
        }
    }
    fun send(packet:Packet) {
        if(!connected) return
        bluetooth?.let { it.send(packet); return }
        val p=packet.copy(display=if(packet.display==0) displayVersion else packet.display)
        synchronized(lock) {
            val last=queue.peekLast()
            if(p.kind==Kind.MOVE && last?.kind==Kind.MOVE && last.display==p.display && last.gesture==p.gesture) { queue.removeLast(); queue.addLast(p.copy(x=p.x+last.x,y=p.y+last.y)) }
            else if(queue.size<128) queue.addLast(p)
            else { thread { lost(epoch,"输入队列积压，已断开以释放按压") }; return }
            lock.notifyAll()
        }
    }
    fun preview(on:Boolean,frame:(Packet)->Unit) {
        if(bluetooth!=null) { if(on) notify(Packet(Kind.ERROR,code=Kind.PREVIEW,text="蓝牙连接只提供键鼠操作；辅助画面请使用局域网连接")); return }
        if(!on) { closeMedia(); send(Packet(Kind.PREVIEW,flag=false)); return }
        val d=device ?: return
        closeMedia(); val generation=epoch; val videoGeneration=mediaEpoch
        thread(name="video-reader") {
            var socket:SSLSocket?=null
            try {
                socket=Security.pinnedContext(d.pin).socketFactory.createSocket() as SSLSocket
                Security.harden(socket); socket.connect(InetSocketAddress(d.host,d.port+1),5000); socket.soTimeout=5000; socket.startHandshake()
                Wire.write(socket.outputStream,Packet(Kind.VIDEO_AUTH,text=session))
                val auth=Wire.read(socket.inputStream); require(auth.kind==Kind.AUTH_OK) { auth.text }
                synchronized(this) {
                    if(epoch!=generation || mediaEpoch!=videoGeneration || !connected) { socket.close(); return@thread }
                    media=socket; socket.soTimeout=0; send(Packet(Kind.PREVIEW,flag=true))
                }
                while(media===socket && connected && epoch==generation) {
                    val packet=Wire.read(socket.inputStream)
                    if(media===socket && mediaEpoch==videoGeneration && epoch==generation) frame(packet)
                }
            } catch(e:Exception) {
                if(epoch==generation && mediaEpoch==videoGeneration && connected) notify(Packet(Kind.ERROR,code=Kind.PREVIEW,text="预览连接：${e.message}"))
            } finally { if(media===socket) { media=null; send(Packet(Kind.PREVIEW,flag=false)) }; try { socket?.close() } catch(_:Exception) {} }
        }
    }
    @Synchronized private fun closeMedia() { mediaEpoch++; val old=media; media=null; try { old?.close() } catch(_:Exception) {} }
    @Synchronized private fun lost(generation:Int,message:String) {
        if(epoch!=generation) return
        epoch++; connected=false; displayVersion=0; nativeMouse=false; val bt=bluetooth; bluetooth=null; bt?.close(); closeMedia(); try { control?.close() } catch(_:Exception) {}; control=null
        synchronized(lock) { queue.clear(); lock.notifyAll() }
        notify(Packet(Kind.STOP,text=message))
        val d=device
        if(!intentional && !closed && d!=null && !d.pairing) {
            val next=epoch; val delay=(1L shl attempt.coerceAtMost(3)); attempt++
            timer.schedule({ if(epoch==next && !intentional && !closed) open(d,next) },delay,TimeUnit.SECONDS)
        }
    }
    fun disconnect() { intentional=true; lost(epoch,"已断开连接") }
    fun connectBluetooth(host:android.bluetooth.BluetoothDevice?) {
        disconnect(); if(closed) return
        intentional=true; device=null; val generation=epoch
        capabilities=Capability.NATIVE_MOUSE or Capability.BLUETOOTH; nativeMouse=true
        val bt=BluetoothRemote(context,host) { p->
            synchronized(this) {
                if(epoch!=generation) return@BluetoothRemote
                if(p.kind==Kind.AUTH_OK) { connected=true; displayVersion=1 }
                if(p.kind==Kind.STOP) { lost(generation,p.text); return@BluetoothRemote }
                notify(p)
            }
        }
        bluetooth=bt; notify(Packet(Kind.STATUS,text="正在连接蓝牙 HID…"))
        try { bt.start() } catch(e:Exception) { lost(generation,e.message ?: "无法开启蓝牙鼠标") }
    }
    fun close() { closed=true; disconnect(); timer.shutdownNow() }
}
