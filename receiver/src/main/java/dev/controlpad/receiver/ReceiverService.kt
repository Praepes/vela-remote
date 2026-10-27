package dev.controlpad.receiver

import android.app.*
import android.content.*
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.net.nsd.*
import android.os.*
import android.provider.Settings
import android.view.WindowManager
import dev.controlpad.shared.*
import java.net.*
import java.security.MessageDigest
import java.util.concurrent.*
import javax.net.ssl.*
import kotlin.concurrent.thread

class ReceiverService:Service() {
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var root:RootBridge
    private lateinit var overlay:CursorOverlay
    private var accessibility:AccessibilityInput?=null
    @Volatile private var nativeMouse=false
    @Volatile private var nativeCapable=false
    private val usingAccessibility get()=accessibility!=null
    private fun inputReady()=accessibility?.ready ?: (::root.isInitialized && root.ready)
    private fun capabilityPacket()=Packet(Kind.CAPABILITIES,code=Capability.TOUCH or Capability.PREVIEW or (if(usingAccessibility) Capability.ACCESSIBILITY else 0) or (if(nativeCapable) Capability.NATIVE_MOUSE else 0),flag=nativeMouse,text=ReceiverState.input)
    private fun updateCursor() {
        if(!::overlay.isInitialized) return
        if(client!=null && cursorVisible && !nativeMouse && Settings.canDrawOverlays(this)) overlay.show(cursorX,cursorY) else overlay.hide()
    }
    private lateinit var contextTls:SSLContext
    private val prefs by lazy { getSharedPreferences("receiver",MODE_PRIVATE) }
    @Volatile private var running=false
    @Volatile private var client:SSLSocket?=null
    @Volatile private var media:SSLSocket?=null
    @Volatile private var session=""
    @Volatile private var lastPing=0L
    @Volatile private var preview=false
    @Volatile private var low=false
    private var lastSequence=0L
    @Volatile private var width=1280; @Volatile private var height=720; @Volatile private var displayVersion=1
    private var rotation=-1
    private var cursorX=640f; private var cursorY=360f; private var cursorVisible=true
    private var videoFormat:Packet?=null
    private val video=ArrayBlockingQueue<Packet>(8)
    private val outgoing=ArrayBlockingQueue<Packet>(64)
    private val writingSince=java.util.concurrent.atomic.AtomicLong(0)
    private var controlServer:SSLServerSocket?=null; private var mediaServer:SSLServerSocket?=null
    private var nsdListener:NsdManager.RegistrationListener?=null
    private val timer=Executors.newSingleThreadScheduledExecutor()
    private val handshakeSlots=Semaphore(3)
    private val failed=java.util.ArrayDeque<Long>()
    private val codePairBusy=java.util.concurrent.atomic.AtomicBoolean(false)
    private var projection:ProjectionCapture?=null
    private var projectionGrant:Pair<Int,Intent>?=null
    override fun onBind(intent:Intent?)=null
    override fun onCreate() {
        super.onCreate(); ReceiverState.service=this; ReceiverState.status="正在启动"; ReceiverState.root="等待授权"; ReceiverState.input="等待输入服务"; ReceiverState.pairing()
        val notifications=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=26) notifications.createNotificationChannel(NotificationChannel("remote","遥控服务",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,ReceiverService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION") val notification=(if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,"remote") else Notification.Builder(this))
            .setContentTitle("轻控受控端").setContentText("局域网遥控服务运行中 · 点按查看状态").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel,"停止服务",stop).build()
        startForeground(10,notification)
        overlay=CursorOverlay(this); updateDisplay()
        running=true
        if(prefs.getString("input_backend","auto")=="accessibility") {
            if(!startAccessibility()) return
        } else {
            ReceiverState.input="正在请求 Root，优先创建系统鼠标"
            root=RootBridge(this,{p->onRootPacket(p)},{message->handler.post {
                if(running) { if(RemoteAccessibilityService.instance!=null) { disconnect(); startAccessibility() } else fail("$message；无 Root 设备可选择无障碍兼容模式") }
            }})
            root.start(width,height,BuildConfig.DEBUG && prefs.getBoolean("software_preview",false))
        }
        thread(name="tls-setup") {
            try {
                val identity=Security.identity()
                synchronized(this) {
                    if(!running) return@thread
                    contextTls=identity.first; ReceiverState.fingerprint=identity.second
                    listen(false); listen(true); registerDiscovery()
                    timer.scheduleAtFixedRate({
                        if(client!=null && SystemClock.uptimeMillis()-lastPing>3000) disconnect()
                        val writing=writingSince.get()
                        if(writing!=0L && SystemClock.uptimeMillis()-writing>1500) disconnect()
                        handler.post { updateDisplay() }
                    },1,1,TimeUnit.SECONDS)
                    ReceiverState.status=if(inputReady()) "服务正常，等待连接" else "等待输入服务就绪"; ReceiverState.changed()
                }
            } catch(e:Exception) { handler.post { fail("网络启动失败：${e.message}") } }
        }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP") { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }
    @Synchronized private fun fail(message:String) { running=false; ReceiverState.status=message; ReceiverState.root="未就绪"; ReceiverState.changed(); stopSelf() }
    @Suppress("DEPRECATION") private fun updateDisplay() {
        val display=(getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
        val point=Point(); display.getRealSize(point)
        if(width!=point.x || height!=point.y || rotation!=display.rotation) {
            width=point.x; height=point.y; rotation=display.rotation; displayVersion++
            cursorX=Geometry.clamp(cursorX,width); cursorY=Geometry.clamp(cursorY,height)
            if(inputReady()) { sendRoot(displayPacket()); sendClient(displayPacket()) }
            if(preview) stopPreview("显示尺寸已改变，请重新开启预览")
        }
    }
    private fun displayPacket()=Packet(Kind.DISPLAY,display=displayVersion,x=width.toFloat(),y=height.toFloat())
    private fun sendRoot(p:Packet) { try { val compatible=accessibility; if(compatible!=null) compatible.send(p) else if(::root.isInitialized) root.send(p) } catch(e:Exception) { handler.post { fail("输入通道断开：${e.message}") } } }
    private fun startAccessibility():Boolean {
        val service=RemoteAccessibilityService.instance
        if(service==null) { fail("请在系统设置中开启“轻控 · 无障碍遥控”，然后启动服务"); return false }
        if(!Settings.canDrawOverlays(this)) { fail("无障碍兼容模式需要兼容光标权限，请先授权悬浮窗"); return false }
        nativeMouse=false; nativeCapable=false; accessibility=AccessibilityInput(service,::onRootPacket)
        ReceiverState.root="未使用（无障碍模式）"
        onRootPacket(Packet(Kind.AUTH_OK,code=Capability.ACCESSIBILITY,text=if(Build.VERSION.SDK_INT>=26) "无障碍 · 连续触摸手势" else "无障碍 · Android 7 手势抬手后执行"))
        return true
    }
    fun cancelAccessibilityInput() { accessibility?.cancel() }
    fun accessibilityLost() { handler.post { if(usingAccessibility && running) fail("无障碍服务已关闭，请重新授权后启动遥控服务") } }
    private fun sendClient(p:Packet) {
        if(client==null) return
        if(p.kind==Kind.CURSOR) outgoing.firstOrNull { it.kind==Kind.CURSOR }?.let { outgoing.remove(it) }
        if(!outgoing.offer(p)) thread { disconnect() }
    }
    private fun startWriter(socket:SSLSocket) {
        thread(name="receiver-writer") {
            try {
                while(running && client===socket) {
                    val p=outgoing.poll(300,TimeUnit.MILLISECONDS) ?: continue
                    if(client!==socket) { outgoing.offer(p); break }
                    writingSince.set(SystemClock.uptimeMillis())
                    Wire.write(socket.outputStream,p)
                    writingSince.set(0)
                }
            } catch(_:Exception) { if(client===socket) disconnect() }
            finally { writingSince.set(0) }
        }
    }
    private fun onRootPacket(p:Packet) {
        if(!running) return
        when(p.kind) {
            Kind.AUTH_OK -> {
                prefs.edit().putBoolean("initialized",true).apply()
                nativeMouse=p.flag; nativeCapable=p.code and Capability.NATIVE_MOUSE!=0; ReceiverState.input=p.text
                if(!usingAccessibility) ReceiverState.root="已授权 · UID 0"
                if(!nativeMouse && !Settings.canDrawOverlays(this)) { handler.post { fail("系统鼠标不可用，需要授权兼容光标：${p.text}") }; return }
                ReceiverState.status="服务正常，等待连接"
                sendRoot(displayPacket()); handler.post { updateCursor() }; ReceiverState.changed()
            }
            Kind.CAPABILITIES -> { nativeMouse=p.flag; nativeCapable=p.code and Capability.NATIVE_MOUSE!=0; ReceiverState.input=p.text; handler.post {
                if(!nativeMouse && cursorVisible && !Settings.canDrawOverlays(this)) { disconnect(); ReceiverState.status="触摸模式需要兼容光标权限，请在受控端授权"; ReceiverState.changed() } else updateCursor()
            }; sendClient(capabilityPacket()); ReceiverState.changed() }
            Kind.CURSOR -> { cursorX=p.x; cursorY=p.y; handler.post { updateCursor() }; sendClient(p) }
            Kind.VIDEO_FORMAT -> { videoFormat=p; video.clear(); enqueueVideo(p); ReceiverState.preview="预览运行中 · ${p.x.toInt()}×${p.y.toInt()} · ${p.text}"; ReceiverState.changed(); sendClient(Packet(Kind.STATUS,text=ReceiverState.preview)) }
            Kind.VIDEO_FRAME -> if(preview) enqueueVideo(p)
            Kind.ERROR -> { if(p.code==Kind.PREVIEW) { stopPreview(p.text) } else sendClient(p) }
        }
    }
    private fun enqueueVideo(p:Packet) {
        if(!video.offer(p)) {
            if(!low && !usingAccessibility) { low=true; video.clear(); sendClient(Packet(Kind.STATUS,text="预览积压，切换低负载档")); thread { sendRoot(Packet(Kind.PREVIEW,flag=true,code=1)) } }
            else stopPreview("网络或编码负载过高，预览已停止")
        }
    }
    private fun listen(isMedia:Boolean) {
        val server=contextTls.serverSocketFactory.createServerSocket(if(isMedia) Kind.VIDEO_PORT else Kind.CONTROL_PORT) as SSLServerSocket
        server.enabledProtocols=server.supportedProtocols.filter { it=="TLSv1.2" || it=="TLSv1.3" }.toTypedArray()
        if(isMedia) mediaServer=server else controlServer=server
        thread(name=if(isMedia) "video-listener" else "control-listener") {
            while(running) try {
                val socket=server.accept() as SSLSocket
                if(!handshakeSlots.tryAcquire()) { socket.close(); continue }
                thread(name="tls-handshake") {
                    try { socket.soTimeout=5000; Security.harden(socket); socket.startHandshake(); if(isMedia) serveVideo(socket) else serveController(socket) }
                    catch(e:Exception) { android.util.Log.w("ControlPad","TLS 会话结束：${e.message}"); try { socket.close() } catch(_:Exception) {} }
                    finally { handshakeSlots.release() }
                }
            } catch(_:Exception) { if(!running) break }
        }
    }
    private fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
    @Synchronized private fun authenticate(p:Packet):String? {
        if(p.kind!=Kind.AUTH || p.code!=Kind.VERSION || !p.text.matches(Regex("[a-f0-9-]{16,64}"))) return null
        val token=p.blob.toString(Charsets.UTF_8)
        val stored=prefs.getString("client_${p.text}",null)
        if(!p.flag) return if(stored!=null && Security.equalsSecret(stored,hash(token))) token else null
        val now=SystemClock.uptimeMillis(); while(failed.isNotEmpty() && now-failed.peekFirst()>60000) failed.removeFirst()
        if(failed.size>=5) return null
        if(!Security.equalsSecret(token,ReceiverState.qrToken)) { failed.add(now); return null }
        return savePair(p.text)
    }
    private fun savePair(identity:String):String {
        val token=Security.randomToken(); prefs.edit().putString("client_$identity",hash(token)).apply(); ReceiverState.pairing(); return token
    }
    private fun pairWithCode(socket:SSLSocket,p:Packet):String {
        require(p.code==Kind.VERSION && p.text.matches(Regex("[a-f0-9-]{16,64}"))) { "无效的配对请求" }
        require(codePairBusy.compareAndSet(false,true)) { "其他设备正在配对，请稍后重试" }
        try {
            val password=synchronized(this) {
                require(inputReady() && client==null) { "设备尚未就绪或已有主控连接" }
                val now=SystemClock.uptimeMillis(); while(failed.isNotEmpty() && now-failed.peekFirst()>60000) failed.removeFirst()
                require(failed.size<5) { "尝试过于频繁，请一分钟后重试" }; failed.add(now)
                ReceiverState.code
            }
            socket.soTimeout=15000
            val exchange=CodePairing("receiver-${Security.randomToken()}",password)
            Wire.write(socket.outputStream,Packet(Kind.PAIR_ONE,blob=exchange.roundOne()))
            exchange.acceptOne(p.blob,p.text)
            val two=exchange.roundTwo()
            fun receive(kind:Int):Packet=Wire.read(socket.inputStream).also { require(it.kind==kind) { "配对步骤无效" } }
            exchange.acceptTwo(receive(Kind.PAIR_TWO).blob)
            Wire.write(socket.outputStream,Packet(Kind.PAIR_TWO,blob=two))
            exchange.verify(receive(Kind.PAIR_CONFIRM).blob,ReceiverState.fingerprint,"controller")
            Wire.write(socket.outputStream,Packet(Kind.PAIR_CONFIRM,blob=exchange.confirm(ReceiverState.fingerprint,"receiver")))
            return password
        } catch(e:Exception) { throw IllegalArgumentException("配对未完成，请核对六位配对码后重试",e) }
        finally { codePairBusy.set(false) }
    }
    private fun serveController(socket:SSLSocket) {
        try {
            Wire.write(socket.outputStream,Packet(Kind.HELLO,code=Kind.VERSION,text=Build.MODEL,flag=true))
            val p=Wire.read(socket.inputStream)
            val verifiedCode=if(p.kind==Kind.PAIR_ONE) pairWithCode(socket,p) else null
            synchronized(this) {
                require(inputReady()) { "输入服务尚未就绪" }
                require(client==null) { "已有主控连接，请先断开" }
                val token=if(verifiedCode!=null) {
                    require(Security.equalsSecret(verifiedCode,ReceiverState.code)) { "配对码已更新，请重新输入" }
                    savePair(p.text)
                } else authenticate(p) ?: throw IllegalArgumentException("配对失败：凭据错误、失效或请求过于频繁")
                client=socket; session=Security.randomToken(); lastPing=SystemClock.uptimeMillis(); lastSequence=0
                outgoing.clear()
                Wire.write(socket.outputStream,Packet(Kind.AUTH_OK,text=session,blob=token.toByteArray(),code=Kind.VERSION))
                Wire.write(socket.outputStream,displayPacket()); Wire.write(socket.outputStream,capabilityPacket()); startWriter(socket); ReceiverState.client="已连接：${socket.inetAddress.hostAddress}"; ReceiverState.status="服务正常 · 正在受控"; ReceiverState.changed()
            }
            socket.soTimeout=3500
            handler.post { cursorVisible=true; updateCursor() }
            while(running && client===socket) {
                val packet=Wire.read(socket.inputStream)
                require(packet.sequence>lastSequence) { "输入序号过期" }; lastSequence=packet.sequence
                lastPing=SystemClock.uptimeMillis()
                when(packet.kind) {
                    Kind.PING -> sendClient(Packet(Kind.PONG,time=packet.time))
                    Kind.PREVIEW -> if(packet.flag) { require(media!=null) { "视频连接尚未就绪" }; startPreview() } else stopPreview()
                    Kind.MODE -> { cursorVisible=packet.flag; sendRoot(packet); handler.post { updateCursor() } }
                    Kind.MOVE,Kind.TAP,Kind.DRAG_BEGIN,Kind.DRAG_MOVE,Kind.END,Kind.CANCEL,Kind.SCROLL_BEGIN,Kind.SCROLL,Kind.PINCH_BEGIN,Kind.PINCH,Kind.KEY,Kind.CENTER -> sendRoot(packet)
                    else -> throw IllegalArgumentException("不支持的控制消息")
                }
            }
        } catch(e:Exception) { try { Wire.write(socket.outputStream,Packet(Kind.ERROR,text=e.message ?: "连接中断")) } catch(_:Exception) {} }
        finally { if(client===socket) disconnect(); try { socket.close() } catch(_:Exception) {} }
    }
    private fun serveVideo(socket:SSLSocket) {
        try {
            val auth=Wire.read(socket.inputStream)
            synchronized(this) {
                require(client!=null && session.isNotEmpty() && auth.kind==Kind.VIDEO_AUTH && Security.equalsSecret(auth.text,session)) { "视频会话认证失败" }
                val previous=media; media=socket; try { previous?.close() } catch(_:Exception) {}
            }
            Wire.write(socket.outputStream,Packet(Kind.AUTH_OK)); socket.soTimeout=0
            while(running && media===socket) {
                val p=video.poll(1,TimeUnit.SECONDS) ?: continue
                if(media!==socket) { video.offer(p); break }
                Wire.write(socket.outputStream,p)
            }
        } finally { if(media===socket) { media=null; stopPreview() }; try { socket.close() } catch(_:Exception) {} }
    }
    private fun startPreview() {
        if(usingAccessibility && projectionGrant==null) { stopPreview("无 Root 模式请先在受控端点击“备用画面采集授权”"); return }
        preview=true; low=false; video.clear(); ReceiverState.preview="正在启动预览"; ReceiverState.changed()
        val grant=projectionGrant
        if(grant!=null) startProjectionEncoder(grant.first,grant.second) else sendRoot(Packet(Kind.PREVIEW,flag=true))
    }
    fun stopPreview(message:String="画面预览已关闭") {
        preview=false; video.clear(); videoFormat=null
        val previous=media; media=null; try { previous?.close() } catch(_:Exception) {}
        if(::root.isInitialized && !usingAccessibility) root.send(Packet(Kind.PREVIEW,flag=false))
        projection?.close(); projection=null
        ReceiverState.preview=message; ReceiverState.changed()
        // 主动关闭已由主控更新界面，不回送会终止下一次预览的迟到通知。
        if(message!="画面预览已关闭") sendClient(Packet(Kind.STATUS,code=Kind.PREVIEW,flag=false,text=message))
    }
    fun startProjection(result:Int,data:Intent) {
        projectionGrant=result to data
        if(client==null || media==null) { ReceiverState.preview="备用采集已授权，请在主控端开启辅助画面"; ReceiverState.changed(); return }
        startProjectionEncoder(result,data)
    }
    private fun startProjectionEncoder(result:Int,data:Intent) {
        if(::root.isInitialized && !usingAccessibility) root.send(Packet(Kind.PREVIEW,flag=false)); projection?.close(); preview=true
        projection=ProjectionCapture(this,BuildConfig.DEBUG && prefs.getBoolean("software_preview",false),::onRootPacket)
        projection?.start(result,data)
    }
    @Synchronized private fun disconnect() {
        val old=client; client=null; session=""
        outgoing.clear(); writingSince.set(0)
        try { old?.close() } catch(_:Exception) {}; try { media?.close() } catch(_:Exception) {}; media=null
        stopPreview(); sendRoot(Packet(Kind.CANCEL)); sendRoot(Packet(Kind.MODE,code=InputMode.DPAD,flag=false))
        handler.post { if(::overlay.isInitialized) overlay.hide() }
        ReceiverState.client="未连接"; ReceiverState.changed()
    }
    fun revoke() { val edit=prefs.edit(); prefs.all.keys.filter { it.startsWith("client_") }.forEach { edit.remove(it) }; edit.apply(); disconnect(); ReceiverState.pairing() }
    private fun registerDiscovery() {
        val nsd=getSystemService(NSD_SERVICE) as NsdManager
        val listener=object:NsdManager.RegistrationListener {
            override fun onServiceRegistered(info:NsdServiceInfo) {}
            override fun onRegistrationFailed(info:NsdServiceInfo,error:Int) { ReceiverState.status="手动连接可用，自动发现注册失败：$error"; ReceiverState.changed() }
            override fun onServiceUnregistered(info:NsdServiceInfo) {}
            override fun onUnregistrationFailed(info:NsdServiceInfo,error:Int) {}
        }
        nsdListener=listener
        nsd.registerService(NsdServiceInfo().apply { serviceName="轻控-${Build.MODEL}"; serviceType=Kind.SERVICE_TYPE; port=Kind.CONTROL_PORT; setAttribute("version","1") },NsdManager.PROTOCOL_DNS_SD,listener)
    }
    @Synchronized override fun onDestroy() {
        running=false; disconnect(); timer.shutdownNow()
        if(::root.isInitialized) root.close()
        accessibility?.close(); accessibility=null
        try { controlServer?.close() } catch(_:Exception) {}; try { mediaServer?.close() } catch(_:Exception) {}
        nsdListener?.let { try { (getSystemService(NSD_SERVICE) as NsdManager).unregisterService(it) } catch(_:Exception) {} }
        if(::overlay.isInitialized) overlay.hide()
        ReceiverState.service=null; if(ReceiverState.status.startsWith("服务正常")) ReceiverState.status="服务已停止"; ReceiverState.changed()
        super.onDestroy()
    }
}
