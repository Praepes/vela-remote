package dev.controlpad.controller

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.net.nsd.*
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.zxing.integration.android.IntentIntegrator
import dev.controlpad.shared.*

class MainActivity:AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("settings",MODE_PRIVATE) }
    internal lateinit var client:RemoteClient
    private lateinit var ui:Ui
    internal lateinit var pad:TouchpadView
    private lateinit var deviceLabel:TextView; private lateinit var status:TextView
    private lateinit var previewButton:Button
    private lateinit var connectButton:Button
    private val remoteButtons=mutableListOf<View>()
    private val centerButtons=mutableListOf<View>()
    private var touchMode=false
    private var backendText=""
    private var latestRtt:Long?=null
    private var connectionSheet:com.google.android.material.bottomsheet.BottomSheetDialog?=null
    private var pairDialog:androidx.appcompat.app.AlertDialog?=null
    private var pairError:TextView?=null
    private var pairCode:EditText?=null
    private var pairAddress:EditText?=null
    private var preview=false; private var directional=false
    private val decoder=VideoDecoder { message->runOnUiThread { stopVideo("解码失败：$message") } }
    private var surface:SurfaceView?=null
    private var videoWidth=16; private var videoHeight=9
    private var statusText="选择设备，开始轻松操控"
    private var deviceName="尚未连接"
    internal var lastCursorX=0f
    internal var lastCursorY=0f
    internal var videoFrames:Long=0
        get()=decoder.frames
        private set
    private var discovery:NsdManager.DiscoveryListener?=null
    private var multicast:WifiManager.MulticastLock?=null
    private val found=linkedMapOf<String,Pair<String,Int>>()
    private val main=Handler(Looper.getMainLooper())
    private val held=mutableSetOf<Int>()
    override fun onCreate(state:Bundle?) {
        AppCompatDelegate.setDefaultNightMode(if(prefs.getBoolean("dark",false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(state)
        client=RemoteClient(this) { p->runOnUiThread { onPacket(p) } }
        buildUi(); startDiscovery()
    }
    private fun buildUi() {
        ui=Ui(this,prefs.getBoolean("dark",false)); window.navigationBarColor=ui.background; window.statusBarColor=ui.background
        remoteButtons.clear()
        centerButtons.clear()
        setContentView(R.layout.activity_main)
        window.insetsController?.setSystemBarsAppearance(if(ui.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        val root=findViewById<FrameLayout>(R.id.root); root.setBackgroundColor(ui.background)
        root.setOnApplyWindowInsetsListener { v,insets-> val bars=insets.getInsets(WindowInsets.Type.systemBars()); v.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets }
        if(resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE) { buildLandscape(root); root.requestApplyInsets(); return }
        val outer=ui.column().apply { setPadding(ui.dp(20),ui.dp(4),ui.dp(20),ui.dp(12)) }; root.addView(outer)
        val header=ui.row(); header.addView(ui.text("轻控",25f,true),LinearLayout.LayoutParams(0,ui.dp(48),1f))
        header.addView(settingsButton(),LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)))
        outer.addView(header); outer.addView(ui.space(10))
        val deviceRow=ui.row(); val deviceText=ui.column(); deviceLabel=singleLine(ui.text(deviceName,16f,true)); status=singleLine(ui.text(statusText,12f,color=ui.muted)); deviceText.addView(deviceLabel); deviceText.addView(status)
        deviceRow.addView(deviceText,LinearLayout.LayoutParams(0,-2,1f)); connectButton=ui.button(if(client.connected) "设备" else "连接",true) { devices() }; deviceRow.addView(connectButton,LinearLayout.LayoutParams(ui.dp(70),ui.dp(48)))
        outer.addView(ui.card(deviceRow).apply { setPadding(ui.dp(16),ui.dp(10),ui.dp(12),ui.dp(10)) }); outer.addView(ui.space(12))
        val work=ui.column()
        outer.addView(work,LinearLayout.LayoutParams(-1,0,1f))
        val controls=ui.column()
        if(preview) {
            work.addView(videoPanel(),LinearLayout.LayoutParams(-1,0,0.8f).apply { bottomMargin=ui.dp(10) })
        }
        work.addView(controls,LinearLayout.LayoutParams(-1,0,2f))
        controls.addView(modePicker()); controls.addView(ui.space(10)); pad=makePad()
        if(!directional) controls.addView(pad,LinearLayout.LayoutParams(-1,0,1f)) else controls.addView(dpad(),LinearLayout.LayoutParams(-1,0,1f))
        controls.addView(ui.space(10))
        val basic=ui.row()
        listOf("返回" to KeyEvent.KEYCODE_BACK,"主页" to KeyEvent.KEYCODE_HOME,"播放 / 暂停" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE).forEachIndexed { i,(text,key)->basic.addView(keyButton(text,key).apply { textSize=12f; topIcon=iconForKey(key)?.let { RemoteIcon(it,ui.ink,ui.dp(23)) } },LinearLayout.LayoutParams(0,ui.dp(68),1f).apply { if(i<2) rightMargin=ui.dp(8) }) }; controls.addView(basic)
        controls.addView(ui.space(7)); val bottom=ui.row()
        bottom.addView(keyButton("音量 −",KeyEvent.KEYCODE_VOLUME_DOWN),LinearLayout.LayoutParams(0,ui.dp(52),1f).apply { rightMargin=ui.dp(6) })
        bottom.addView(centerButton(),LinearLayout.LayoutParams(0,ui.dp(52),1f).apply { rightMargin=ui.dp(6) })
        bottom.addView(keyButton("音量 ＋",KeyEvent.KEYCODE_VOLUME_UP),LinearLayout.LayoutParams(0,ui.dp(52),1f)); controls.addView(bottom)
        outer.addView(ui.space(10)); previewButton=previewControl(); outer.addView(previewButton,LinearLayout.LayoutParams(-1,ui.dp(48)))
        refreshStatus(); root.requestApplyInsets()
    }
    private fun singleLine(text:TextView)=text.apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END }
    private fun settingsButton()=ui.button("") { settings() }.apply { contentDescription="设置"; foreground=RemoteIcon("settings",ui.ink,ui.dp(23)); foregroundGravity=Gravity.CENTER; setPadding(0,0,0,0) }
    private fun modePicker():View {
        val modes=ui.row().apply { background=ui.rounded(if(ui.dark) Color.parseColor("#17202E") else Color.parseColor("#E9EDF4"),18); setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(4)) }
        listOf("鼠标" to InputMode.MOUSE,"触摸" to InputMode.TOUCH,"方向键" to InputMode.DPAD).forEach { (label,mode)->val selected=currentMode()==mode; modes.addView(ui.button(label) { switchMode(mode) }.apply {
            isSelected=selected; setTextColor(if(selected) ui.ink else ui.muted); if(!selected) setBackgroundColor(Color.TRANSPARENT)
            isEnabled=when(mode) { InputMode.TOUCH->!client.isBluetooth; InputMode.MOUSE->!client.connected || client.capabilities and Capability.NATIVE_MOUSE!=0; else->true }; alpha=if(isEnabled) 1f else 0.4f
            if(mode==InputMode.MOUSE && !isEnabled) contentDescription="鼠标模式不可用：受控端未能创建系统鼠标，请使用触摸模式"
        },LinearLayout.LayoutParams(0,ui.dp(48),1f)) }
        return modes
    }
    private fun centerButton(label:String="光标居中")=ui.button(label) { cancelInputs(); client.send(Packet(Kind.CENTER)) }.apply { gravity=Gravity.CENTER; includeFontPadding=false; contentDescription="光标居中"; remoteButtons.add(this); centerButtons.add(this) }
    private fun previewControl()=ui.button(if(preview) "关闭辅助画面" else "查看辅助画面",preview) { if(preview) stopVideo() else if(client.connected) { cancelInputs(); preview=true; buildUi() } }.apply {
        setCompoundDrawablesRelativeWithIntrinsicBounds(RemoteIcon("preview",if(preview) Color.WHITE else ui.ink,ui.dp(20)),null,null,null); compoundDrawablePadding=ui.dp(8); isEnabled=client.connected && client.supportsPreview
    }
    private fun makePad()=TouchpadView(this,ui) { packet->
        if(!touchMode && client.nativeMouse && packet.kind in listOf(Kind.PINCH_BEGIN,Kind.PINCH)) {
            if(packet.kind==Kind.PINCH_BEGIN) { client.send(Packet(Kind.CANCEL)); toast("双指缩放请切换到触摸模式；蓝牙连接不支持触摸模式") }
        } else client.send(packet)
    }.apply {
        touchGestures=touchMode || !client.nativeMouse
        remoteEnabled=client.connected && client.displayVersion>0; haptics=prefs.getBoolean("haptics",true)
        engine.speed=prefs.getFloat("speed",1.5f); engine.acceleration=prefs.getFloat("acceleration",0f); engine.scrollSpeed=prefs.getFloat("scroll",2f); engine.naturalScroll=prefs.getBoolean("natural",true)
    }
    private fun videoPanel():FrameLayout {
        val holder=FrameLayout(this).apply { background=ui.rounded(android.graphics.Color.BLACK,18); clipToOutline=true }
        val v=SurfaceView(this); surface=v; holder.addView(v,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        v.holder.addCallback(object:SurfaceHolder.Callback {
            override fun surfaceCreated(h:SurfaceHolder) {
                decoder.surface(h.surface)
                if(preview && client.connected) client.preview(true) { p->try { decoder.packet(p); if(p.kind==Kind.VIDEO_FORMAT) runOnUiThread { videoWidth=p.x.toInt(); videoHeight=p.y.toInt(); fitVideo() } } catch(e:Exception) { runOnUiThread { stopVideo("解码失败：${e.message}") } } }
            }
            override fun surfaceChanged(h:SurfaceHolder,f:Int,w:Int,he:Int) { decoder.surface(h.surface) }
            override fun surfaceDestroyed(h:SurfaceHolder) { decoder.surface(null) }
        })
        holder.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_->fitVideo() }; return holder
    }
    private fun buildLandscape(root:FrameLayout) {
        val outer=ui.column().apply { setPadding(ui.dp(12),ui.dp(4),ui.dp(12),ui.dp(8)) }; root.addView(outer)
        val header=ui.row(); val info=ui.column(); deviceLabel=singleLine(ui.text(if(client.connected) deviceName else "轻控 · 选择设备",15f,true)); status=singleLine(ui.text(statusText,12f,color=ui.muted)); info.addView(deviceLabel); info.addView(status)
        header.addView(info,LinearLayout.LayoutParams(0,-2,1f))
        connectButton=ui.button(if(client.connected) "设备" else "连接",true) { devices() }; header.addView(connectButton,LinearLayout.LayoutParams(ui.dp(64),ui.dp(48)).apply { rightMargin=ui.dp(8) })
        previewButton=previewControl()
        header.addView(previewButton,LinearLayout.LayoutParams(ui.dp(152),ui.dp(48)).apply { rightMargin=ui.dp(8) }); header.addView(settingsButton(),LinearLayout.LayoutParams(ui.dp(48),ui.dp(48))); outer.addView(header); outer.addView(ui.space(8))
        val work=ui.row().apply { gravity=Gravity.TOP }; outer.addView(work,LinearLayout.LayoutParams(-1,0,1f))
        val touch=ui.column(); touch.addView(modePicker()); touch.addView(ui.space(8))
        pad=makePad(); touch.addView(if(directional) dpad() else pad,LinearLayout.LayoutParams(-1,0,1f))
        val keys=listOf("返回" to KeyEvent.KEYCODE_BACK,"主页" to KeyEvent.KEYCODE_HOME,"播放" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,"音量 −" to KeyEvent.KEYCODE_VOLUME_DOWN,"音量 ＋" to KeyEvent.KEYCODE_VOLUME_UP)
        if(preview) {
            val left=ui.column(); left.addView(videoPanel(),LinearLayout.LayoutParams(-1,0,1f)); left.addView(ui.space(6)); val toolbar=ui.row()
            keys.forEach { (label,key)->toolbar.addView(keyButton(label,key).apply { textSize=12f; setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,ui.dp(48),1f).apply { rightMargin=ui.dp(3) }) }
            toolbar.addView(centerButton("居中").apply { textSize=12f; setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,ui.dp(48),1f)); left.addView(toolbar)
            work.addView(left,LinearLayout.LayoutParams(0,-1,1.35f).apply { rightMargin=ui.dp(12) }); work.addView(touch,LinearLayout.LayoutParams(0,-1,1f))
        } else {
            work.addView(touch,LinearLayout.LayoutParams(0,-1,1f).apply { rightMargin=ui.dp(12) })
            val grid=android.widget.GridLayout(this).apply { rowCount=3; columnCount=2 }
            repeat(6) { i->val v=if(i<5) keyButton(keys[i].first,keys[i].second) else centerButton()
                grid.addView(v,android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(i/2,1f),android.widget.GridLayout.spec(i%2,1f)).apply { width=0; height=0; setMargins(ui.dp(3),ui.dp(3),ui.dp(3),ui.dp(3)) }) }
            work.addView(grid,LinearLayout.LayoutParams(ui.dp(216),-1))
        }
        refreshStatus()
    }
    private fun fitVideo() {
        val v=surface ?: return; val parent=v.parent as? FrameLayout ?: return
        if(parent.width<=0 || parent.height<=0) return
        val scale=minOf(parent.width.toFloat()/videoWidth,parent.height.toFloat()/videoHeight)
        val w=(videoWidth*scale).toInt(); val h=(videoHeight*scale).toInt()
        if(v.width!=w || v.height!=h) v.layoutParams=FrameLayout.LayoutParams(w,h,Gravity.CENTER)
    }
    private fun dpad():View {
        val grid=android.widget.GridLayout(this).apply { rowCount=3; columnCount=3; alignmentMode=android.widget.GridLayout.ALIGN_MARGINS; background=ui.rounded(if(ui.dark) Color.parseColor("#17202E") else Color.parseColor("#F0F3F8"),140); setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(8)) }
        val keys=mapOf(1 to ("上" to KeyEvent.KEYCODE_DPAD_UP),3 to ("左" to KeyEvent.KEYCODE_DPAD_LEFT),4 to ("确认" to KeyEvent.KEYCODE_DPAD_CENTER),5 to ("右" to KeyEvent.KEYCODE_DPAD_RIGHT),7 to ("下" to KeyEvent.KEYCODE_DPAD_DOWN))
        repeat(9) { i-> val item=keys[i]; val v=if(item!=null) keyButton(item.first,item.second,true).apply { text=""; contentDescription=item.first; setPadding(0,0,0,0); if(i==4) background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x32FFFFFF),ui.rounded(ui.blue,40),null); foreground=RemoteIcon(iconForKey(item.second)!!,if(i==4) Color.WHITE else ui.ink,ui.dp(25)); foregroundGravity=Gravity.CENTER } else Space(this)
            grid.addView(v,android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(i/3,1f),android.widget.GridLayout.spec(i%3,1f)).apply { width=0; height=0; setMargins(ui.dp(3),ui.dp(3),ui.dp(3),ui.dp(3)) }) }
        return object:FrameLayout(this) {
            init { background=ui.rounded(ui.surface,28); addView(grid,FrameLayout.LayoutParams(ui.dp(240),ui.dp(240),Gravity.CENTER)) }
            override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) { super.onSizeChanged(w,h,oldw,oldh); val side=minOf(w-ui.dp(16),h-ui.dp(16),ui.dp(280)).coerceAtLeast(1); if(grid.layoutParams.width!=side) grid.layoutParams=FrameLayout.LayoutParams(side,side,Gravity.CENTER) }
        }
    }
    private fun keyButton(label:String,code:Int,repeat:Boolean=false):RemoteKeyButton {
        var touchClick=false
        val b=RemoteKeyButton(this).apply { ui.styleButton(this,label); contentDescription=label; remoteButtons.add(this) }; val tick=object:Runnable { override fun run() { if(held.contains(code)) { client.send(Packet(Kind.KEY,code=code,flag=true)); main.postDelayed(this,100) } } }
        b.setOnTouchListener { v,e->
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if(client.connected) { held.add(code); client.send(Packet(Kind.KEY,code=code,flag=true)); if(prefs.getBoolean("haptics",true)) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); if(repeat) main.postDelayed(tick,400); v.isPressed=true }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { held.remove(code); main.removeCallbacks(tick); client.send(Packet(Kind.KEY,code=code,flag=false)); v.isPressed=false; if(e.actionMasked==MotionEvent.ACTION_UP) { touchClick=true; v.performClick(); touchClick=false } }
            }; true
        }
        b.setOnClickListener { if(!touchClick && !held.contains(code)) { client.send(Packet(Kind.KEY,code=code,flag=true)); client.send(Packet(Kind.KEY,code=code,flag=false)) } }
        return b
    }
    private fun iconForKey(code:Int):String?=when(code) {
        KeyEvent.KEYCODE_BACK->"back"; KeyEvent.KEYCODE_HOME->"home"; KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE->"play"
        KeyEvent.KEYCODE_VOLUME_DOWN->"minus"; KeyEvent.KEYCODE_VOLUME_UP->"plus"
        KeyEvent.KEYCODE_DPAD_UP->"up"; KeyEvent.KEYCODE_DPAD_DOWN->"down"; KeyEvent.KEYCODE_DPAD_LEFT->"left"; KeyEvent.KEYCODE_DPAD_RIGHT->"right"; KeyEvent.KEYCODE_DPAD_CENTER->"confirm"; else->null
    }
    private fun refreshStatus() {
        if(!::status.isInitialized) return
        val connected=client.connected
        deviceLabel.text=if(connected) deviceName else "连接你的大屏设备"
        status.text=if(connected) (if(backendText.isNotEmpty()) "$backendText · " else "●  ")+(if(client.isBluetooth) "已连接" else latestRtt?.let { when { it<80->"顺畅 · ${it}ms"; it<180->"良好 · ${it}ms"; else->"较慢 · ${it}ms" } } ?: "已连接") else statusText
        status.setTextColor(if(connected) if((latestRtt ?: 0)<180) Color.parseColor(if(ui.dark) "#7BCDAC" else "#27785B") else Color.parseColor("#B77818") else ui.muted)
        connectButton.text=if(connected) "设备" else "连接"
        remoteButtons.forEach { it.isEnabled=connected; it.alpha=if(connected) 1f else 0.42f }
        centerButtons.forEach { it.isEnabled=connected && !client.nativeMouse && !directional; it.alpha=if(it.isEnabled) 1f else 0.42f }
        if(::previewButton.isInitialized) { previewButton.isEnabled=connected && client.supportsPreview; previewButton.alpha=if(previewButton.isEnabled) 1f else 0.5f }
    }
    private fun currentMode()=if(directional) InputMode.DPAD else if(touchMode) InputMode.TOUCH else InputMode.MOUSE
    private fun sendMode()=client.send(Packet(Kind.MODE,code=currentMode(),flag=!directional))
    private fun switchMode(mode:Int) { if(mode==currentMode()) return; cancelInputs(); directional=mode==InputMode.DPAD; touchMode=mode==InputMode.TOUCH; sendMode(); if(preview) { client.preview(false) {}; decoder.close() }; buildUi() }
    private fun onPacket(p:Packet) {
        when(p.kind) {
            Kind.CURSOR -> { lastCursorX=p.x; lastCursorY=p.y; return }
            Kind.AUTH_OK -> { deviceName=p.text; latestRtt=null; backendText=""; statusText="已连接"; pairDialog?.dismiss(); if(client.isBluetooth && touchMode) { touchMode=false; directional=false }; if(::pad.isInitialized) pad.remoteEnabled=false }
            Kind.CAPABILITIES -> {
                backendText=when { client.isBluetooth->"蓝牙鼠标"; p.code and Capability.ACCESSIBILITY!=0->"无障碍触摸"; p.flag->"系统鼠标"; directional->"方向导航"; else->"触摸手势" }
                if(!directional && !touchMode && p.code and Capability.NATIVE_MOUSE==0) { touchMode=true; sendMode() }
                if(preview) stopVideo() else buildUi()
            }
            // DISPLAY 在网络层清空旧尺寸输入后才同步模式，防止首次连接／重连时 MODE 被清掉。
            Kind.DISPLAY -> { cancelInputs(); sendMode(); if(::pad.isInitialized) pad.remoteEnabled=client.connected; statusText="已连接 · ${p.x.toInt()} × ${p.y.toInt()}" }
            Kind.PONG -> latestRtt=p.time
            Kind.STATUS -> { statusText=p.text; if(client.isBluetooth && !client.connected && p.code==Capability.BLUETOOTH) {
                try { startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,120)) } catch(e:Exception) { toast("请在系统蓝牙设置中开启可发现状态：${e.message}") }
            }; if(pairDialog?.isShowing==true) pairError?.text=if(p.text.startsWith("正在连接")) "正在连接设备…" else p.text; if(p.code==Kind.PREVIEW && !p.flag && preview) stopVideo(p.text) }
            Kind.ERROR -> { statusText=p.text; if(p.code==Kind.PREVIEW) stopVideo(p.text) else if(pairDialog?.isShowing==true) { pairError?.text=p.text; pairError?.setTextColor(Color.parseColor(if(ui.dark) "#FFA49D" else "#B94B45")); pairCode?.isEnabled=true; pairAddress?.isEnabled=true; pairDialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply { isEnabled=true; text="配对连接" } } else toast(p.text) }
            Kind.STOP -> { cancelInputs(); if(::pad.isInitialized) pad.remoteEnabled=false; deviceName="尚未连接"; statusText=p.text; if(preview) stopVideo() }
        }
        refreshStatus()
    }
    private fun stopVideo(message:String="") { if(!preview) return; preview=false; client.preview(false) {}; decoder.close(); buildUi(); if(message.isNotEmpty()) toast(message) }
    private fun cancelInputs() { if(::pad.isInitialized) pad.cancel(); held.toList().forEach { client.send(Packet(Kind.KEY,code=it,flag=false)) }; held.clear(); client.send(Packet(Kind.CANCEL)) }
    private fun devices() {
        cancelInputs(); connectionSheet?.dismiss()
        val sheet=com.google.android.material.bottomsheet.BottomSheetDialog(this); connectionSheet=sheet
        renderDevices(sheet); sheet.show()
    }
    private fun renderDevices(sheet:com.google.android.material.bottomsheet.BottomSheetDialog) {
        val body=ui.column().apply { setPadding(ui.dp(24),ui.dp(18),ui.dp(24),ui.dp(24)); setBackgroundColor(ui.background) }
        body.addView(ui.text("连接设备",22f,true)); body.addView(ui.text("让手机和大屏连接同一个 Wi-Fi",13f,color=ui.muted)); body.addView(ui.space(12))
        fun action(label:String,primary:Boolean=false,click:()->Unit) { body.addView(ui.button(label,primary) { sheet.dismiss(); click() },LinearLayout.LayoutParams(-1,ui.dp(52)).apply { bottomMargin=ui.dp(8) }) }
        val saved=client.saved()
        if(saved.isNotEmpty()) { body.addView(ui.text("我的设备",13f,true,color=ui.muted)); saved.forEach { d->action(d.name,client.connected && client.device?.pin==d.pin) { client.connect(d) } } }
        body.addView(ui.text("附近设备",13f,true,color=ui.muted))
        if(found.isEmpty()) body.addView(ui.text("正在查找…\n没找到时，可扫描二维码或填写设备地址。",13f,color=ui.muted).apply { setPadding(0,ui.dp(8),0,ui.dp(16)) })
        else found.forEach { (name,d)->action(name.removePrefix("轻控-")) { manual(d.first,d.second) } }
        action("输入设备地址") { manual() }; action("扫描配对二维码") { scan() }
        action("蓝牙鼠标连接") { bluetoothDevices() }
        if(client.connected) action("断开当前设备") { client.disconnect() }
        sheet.setContentView(ScrollView(this).apply { addView(body) })
    }
    @android.annotation.SuppressLint("MissingPermission") // 入口先检查 CONNECT / ADVERTISE 两个运行时权限。
    private fun bluetoothDevices() {
        val permissions=arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE)
        if(permissions.any { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }) { requestPermissions(permissions,72); return }
        try {
            val manager=getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val adapter=manager.adapter ?: return toast("此设备没有蓝牙适配器")
            if(!adapter.isEnabled) { toast("请在系统设置中开启蓝牙"); startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return }
            val paired=adapter.bondedDevices.sortedBy { it.name ?: it.address }
            val body=ui.column().apply { setPadding(ui.dp(24),0,ui.dp(24),ui.dp(12)) }
            body.addView(ui.text("选择已在系统中配对的电视／盒子。蓝牙只传输键鼠，不提供辅助画面与真实双指缩放。请保持主控在前台。",13f,color=ui.muted))
            paired.forEach { device->body.addView(ui.button(device.name ?: device.address) { bluetoothDialog?.dismiss(); cancelInputs(); if(preview) stopVideo(); client.connectBluetooth(device) },LinearLayout.LayoutParams(-1,ui.dp(52)).apply { topMargin=ui.dp(8) }) }
            body.addView(ui.button("让电视搜索此手机") { bluetoothDialog?.dismiss(); cancelInputs(); if(preview) stopVideo(); client.connectBluetooth(null) },LinearLayout.LayoutParams(-1,ui.dp(52)))
            body.addView(ui.button("系统蓝牙设置") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },LinearLayout.LayoutParams(-1,ui.dp(52)))
            bluetoothDialog=com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setBackground(ui.rounded(ui.surface,24)).setTitle("蓝牙鼠标").setView(ScrollView(this).apply { addView(body) }).setNegativeButton("关闭",null).show()
        } catch(e:Exception) { toast("无法访问蓝牙：${e.message}") }
    }
    private var bluetoothDialog:androidx.appcompat.app.AlertDialog?=null
    private fun manual(host:String="",port:Int=Kind.CONTROL_PORT) {
        pairDialog?.dismiss()
        val body=ui.column().apply { setPadding(ui.dp(24),0,ui.dp(24),0) }
        body.addView(ui.text("输入大屏上显示的六位配对码",14f,color=ui.muted))
        val address=ui.input("设备地址，例如 192.168.1.20:$port",if(host.isEmpty()) "" else "$host:$port")
        address.contentDescription="设备地址"
        address.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; address.imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        val code=ui.input("000000").apply { contentDescription="六位配对码"; inputType=InputType.TYPE_CLASS_NUMBER; filters=arrayOf(InputFilter.LengthFilter(6)); textSize=28f; letterSpacing=0.18f; gravity=Gravity.CENTER; typeface=android.graphics.Typeface.MONOSPACE; imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE; setSelectAllOnFocus(true) }
        fun field(view:EditText) { val fill=if(ui.dark) Color.parseColor("#17202E") else Color.parseColor("#F4F6FA"); fun update(focused:Boolean) { view.background=ui.rounded(fill,14,if(focused) ui.blue else if(ui.dark) Color.parseColor("#344157") else Color.parseColor("#DFE5EF")) }; update(false); view.setPadding(ui.dp(14),ui.dp(4),ui.dp(14),ui.dp(4)); view.setOnFocusChangeListener { _,focused->update(focused) } }
        field(address); field(code)
        if(host.isEmpty()) { body.addView(ui.space(12)); body.addView(ui.text("设备地址",12f,color=ui.muted)); body.addView(address) }
        body.addView(ui.space(12)); body.addView(ui.text("六位配对码",12f,color=ui.muted)); body.addView(code,LinearLayout.LayoutParams(-1,ui.dp(64)))
        val error=ui.text("配对后，下次可直接连接",12f,color=ui.muted); body.addView(error)
        val dialog=com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setBackground(ui.rounded(ui.surface,24)).setTitle("六位码配对").setView(ScrollView(this).apply { addView(body) }).setPositiveButton("配对连接",null).setNegativeButton("取消") { _,_->if(!code.isEnabled) client.disconnect() }.create()
        dialog.setOnCancelListener { if(!code.isEnabled) client.disconnect() }
        pairDialog=dialog; pairError=error; pairCode=code; pairAddress=address
        dialog.setOnDismissListener { if(pairDialog===dialog) { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(code.windowToken,0); pairDialog=null; pairError=null; pairCode=null; pairAddress=null } }
        dialog.setOnShowListener { dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val raw=address.text.toString().trim(); val parts=raw.split(":"); require(parts.size in 1..2 && parts[0].isNotBlank()) { "请输入有效地址" }
                val pin=code.text.toString().trim(); require(pin.matches(Regex("[0-9]{6}"))) { "配对码应为六位数字" }
                val p=if(parts.size==2) parts[1].toInt() else Kind.CONTROL_PORT; require(p in 1024..65534)
                error.setTextColor(ui.muted); error.text="正在连接设备…"; code.isEnabled=false; address.isEnabled=false
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).apply { isEnabled=false; text="正在配对…" }
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(code.windowToken,0)
                client.connect(Device(parts[0],p,"",pin,pairing=true))
            } catch(e:Exception) { error.text=e.message ?: "配对信息无效"; error.setTextColor(Color.parseColor(if(ui.dark) "#FFA49D" else "#B94B45")) }
        }; code.setOnEditorActionListener { _,action,_->if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); true } else false } }
        dialog.show(); dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val focus=if(host.isNotEmpty()) code else address; focus.requestFocus(); focus.post { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(focus,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
    }
    private fun scan() { if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA),71) else IntentIntegrator(this).setPrompt("扫描受控端配对二维码").setBeepEnabled(false).setOrientationLocked(false).initiateScan() }
    @Deprecated("二维码组件兼容") override fun onActivityResult(request:Int,result:Int,data:Intent?) {
        val scan=IntentIntegrator.parseActivityResult(request,result,data)
        if(scan!=null) {
            if(scan.contents!=null) try { val u=Uri.parse(scan.contents); require(u.scheme=="controlpad" && u.host=="pair"); val host=u.getQueryParameter("host")!!; val port=u.getQueryParameter("port")!!.toInt(); val fp=u.getQueryParameter("fp")!!; val token=u.getQueryParameter("token")!!; require(fp.length==64 && token.length==48 && port in 1024..65534); client.connect(Device(host,port,fp,token,pairing=true)) } catch(_:Exception) { toast("不是有效的轻控配对二维码") }
            return
        }; super.onActivityResult(request,result,data)
    }
    override fun onRequestPermissionsResult(request:Int,permissions:Array<out String>,results:IntArray) { super.onRequestPermissionsResult(request,permissions,results); if(request==71 && results.firstOrNull()==PackageManager.PERMISSION_GRANTED.toInt()) scan(); if(request==72) { if(results.isNotEmpty() && results.all { it==PackageManager.PERMISSION_GRANTED }) bluetoothDevices() else toast("蓝牙鼠标需要附近设备权限") } }
    private fun settings() {
        cancelInputs()
        val body=ui.column().apply { setPadding(ui.dp(24),0,ui.dp(24),0) }
        fun toggle(label:String,key:String,default:Boolean) { body.addView(Switch(this).apply { text=label; setTextColor(ui.ink); minHeight=ui.dp(48); isChecked=prefs.getBoolean(key,default); setOnCheckedChangeListener { _,v->prefs.edit().putBoolean(key,v).apply() } }) }
        toggle("深色界面","dark",false); toggle("轻振动反馈","haptics",true); toggle("自然滚动","natural",true)
        fun slider(label:String,key:String,default:Float,max:Int,scale:Float) {
            fun caption(v:Float):String {
                return if(key=="acceleration") "$label · ${(v*100).toInt()}%" else "$label · %.1f×".format(v)
            }
            val t=ui.text(caption(prefs.getFloat(key,default)))
            body.addView(t)
            val seek=SeekBar(this).apply {
                this.max=max; progress=(prefs.getFloat(key,default)*scale).toInt(); minHeight=ui.dp(48)
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean) {
                        val value=(p/scale).coerceAtLeast(if(key=="acceleration") 0f else 0.2f)
                        t.text=caption(value); prefs.edit().putFloat(key,value).apply()
                    }
                    override fun onStartTrackingTouch(s:SeekBar) {}
                    override fun onStopTrackingTouch(s:SeekBar) {}
                })
            }
            body.addView(seek)
        }
        slider("光标速度","speed",1.5f,300,100f); slider("光标加速度","acceleration",0f,100,100f); slider("滚动速度","scroll",2f,400,100f)
        body.addView(ui.button("清除主控保存的配对") { client.forget(); toast("已清除本机保存的设备") })
        val scroll=ScrollView(this).apply { addView(body) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setBackground(ui.rounded(ui.surface,24)).setTitle("遥控器设置").setView(scroll).setPositiveButton("完成") { _,_->cancelInputs(); if(preview) stopVideo(); AppCompatDelegate.setDefaultNightMode(if(prefs.getBoolean("dark",false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO); buildUi() }.show()
    }
    private fun startDiscovery() {
        try {
            multicast=(applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createMulticastLock("controlpad-discovery").apply { setReferenceCounted(false); acquire() }
            val nsd=getSystemService(NSD_SERVICE) as NsdManager
            discovery=object:NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t:String) {}; override fun onDiscoveryStopped(t:String) {}
                override fun onStartDiscoveryFailed(t:String,e:Int) {}; override fun onStopDiscoveryFailed(t:String,e:Int) {}
                override fun onServiceLost(s:NsdServiceInfo) { runOnUiThread { found.remove(s.serviceName); connectionSheet?.takeIf { it.isShowing }?.let { renderDevices(it) } } }
                @Suppress("DEPRECATION") override fun onServiceFound(s:NsdServiceInfo) { nsd.resolveService(s,object:NsdManager.ResolveListener {
                    override fun onResolveFailed(s:NsdServiceInfo,e:Int) {}
                    override fun onServiceResolved(s:NsdServiceInfo) { val h=s.host?.hostAddress ?: return; runOnUiThread { found[s.serviceName]=h to s.port; connectionSheet?.takeIf { it.isShowing }?.let { renderDevices(it) } } }
                }) }
            }
            nsd.discoverServices(Kind.SERVICE_TYPE,NsdManager.PROTOCOL_DNS_SD,discovery)
        } catch(_:Exception) {}
    }
    private fun toast(message:String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
    override fun onPause() { cancelInputs(); if(preview) stopVideo(); super.onPause() }
    override fun onConfigurationChanged(config:Configuration) { super.onConfigurationChanged(config); cancelInputs(); if(preview) { client.preview(false) {}; decoder.close() }; buildUi() }
    override fun onDestroy() { pairDialog?.dismiss(); bluetoothDialog?.dismiss(); connectionSheet?.dismiss(); cancelInputs(); decoder.close(); client.close(); discovery?.let { try { (getSystemService(NSD_SERVICE) as NsdManager).stopServiceDiscovery(it) } catch(_:Exception) {} }; multicast?.let { if(it.isHeld) it.release() }; super.onDestroy() }
}
