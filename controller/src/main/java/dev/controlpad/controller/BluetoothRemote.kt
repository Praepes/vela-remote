package dev.controlpad.controller

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.controlpad.shared.*
import java.util.concurrent.*

/** 只连接系统已配对的 HID Host；输入完全经蓝牙传输，不复用局域网凭据。 */
@SuppressLint("MissingPermission")
class BluetoothRemote(private val context:Context,private val target:BluetoothDevice?,private val notify:(Packet)->Unit) {
    private val adapter=(context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main=Handler(Looper.getMainLooper())
    private val writer=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(128))
    @Volatile private var profile:BluetoothHidDevice?=null
    @Volatile private var host:BluetoothDevice?=null
    @Volatile private var closed=false
    private var consumer=0
    private val keys=linkedSetOf<Int>()
    private val lastReports=ConcurrentHashMap<Int,ByteArray>()
    private val mouse=HidMouse { report(1,it) }
    private var gesture=0L
    private val timeout=Runnable { if(host==null && !closed) fail("蓝牙 HID 连接超时，请确认电视支持蓝牙鼠标并已完成系统配对") }
    private fun report(id:Int,bytes:ByteArray) {
        val p=profile ?: error("蓝牙 HID 服务不可用"); val device=host ?: error("蓝牙鼠标未连接")
        check(p.sendReport(device,id,bytes)) { "蓝牙 HID 报告发送失败" }
        lastReports[id]=bytes.copyOf()
    }
    fun start() {
        check(adapter!=null && adapter.isEnabled) { "请先在系统设置中开启蓝牙" }
        if(target!=null) check(target.bondState==BluetoothDevice.BOND_BONDED) { "请先完成系统蓝牙配对" }
        main.postDelayed(timeout,15000)
        check(adapter.getProfileProxy(context,listener,BluetoothProfile.HID_DEVICE)) { "此手机固件不支持蓝牙 HID Device" }
    }
    private val listener=object:BluetoothProfile.ServiceListener {
        override fun onServiceConnected(type:Int,proxy:BluetoothProfile) {
            if(type!=BluetoothProfile.HID_DEVICE) return
            val hid=proxy as BluetoothHidDevice
            if(closed) { adapter?.closeProfileProxy(type,proxy); return }
            profile=hid
            val settings=BluetoothHidDeviceAppSdpSettings("Vela Remote","轻控蓝牙遥控器","Vela Remote",(BluetoothHidDevice.SUBCLASS1_MOUSE.toInt() or BluetoothHidDevice.SUBCLASS1_KEYBOARD.toInt()).toByte(),HidReports.descriptor)
            if(!hid.registerApp(settings,null,null,context.mainExecutor,callback)) fail("无法注册蓝牙 HID，请关闭其他蓝牙键鼠应用后重试")
        }
        override fun onServiceDisconnected(type:Int) { if(!closed) fail("蓝牙 HID 系统服务已断开") }
    }
    private val callback=object:BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice:BluetoothDevice?,registered:Boolean) {
            if(closed) return
            if(!registered) { fail("蓝牙鼠标已停止，请保持主控在前台并重新连接"); return }
            if(target!=null) { if(profile?.connect(target)!=true) fail("蓝牙连接请求失败，请在系统中确认配对") }
            else { main.removeCallbacks(timeout); notify(Packet(Kind.STATUS,code=Capability.BLUETOOTH,text="蓝牙鼠标已就绪，请让电视搜索并连接此手机")) }
        }
        override fun onConnectionStateChanged(device:BluetoothDevice,state:Int) {
            if(closed) return
            if(state==BluetoothProfile.STATE_CONNECTED) {
                if((target!=null && target.address!=device.address) || (host!=null && host?.address!=device.address)) { profile?.disconnect(device); return }
                host=device; main.removeCallbacks(timeout)
                notify(Packet(Kind.AUTH_OK,text="${device.name ?: "蓝牙设备"} · 蓝牙"))
                notify(Packet(Kind.DISPLAY,display=1))
                notify(Packet(Kind.CAPABILITIES,code=Capability.NATIVE_MOUSE or Capability.BLUETOOTH,flag=true,text="蓝牙 · 系统鼠标"))
            } else if(state==BluetoothProfile.STATE_DISCONNECTED && host?.address==device.address) fail("蓝牙设备已断开")
        }
        override fun onGetReport(device:BluetoothDevice,type:Byte,id:Byte,bufferSize:Int) {
            if(type!=BluetoothHidDevice.REPORT_TYPE_INPUT) { profile?.reportError(device,BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ); return }
            val empty=lastReports[id.toInt()] ?: when(id.toInt()) { 1->HidReports.mouse(0); 2->HidReports.consumer(0); 3->HidReports.keyboard(emptyList()); else->null }
            if(empty==null) profile?.reportError(device,BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID) else profile?.replyReport(device,type,id,empty)
        }
        override fun onSetProtocol(device:BluetoothDevice,protocol:Byte) { if(protocol==BluetoothHidDevice.PROTOCOL_BOOT_MODE) fail("目标设备仅请求 Boot 协议，本版需要标准 HID Report 协议") }
        override fun onVirtualCableUnplug(device:BluetoothDevice) { if(!closed) fail("目标设备已取消蓝牙 HID 配对连接") }
    }
    fun send(p:Packet) {
        if(closed || host==null) return
        try { writer.execute {
            if(closed) return@execute
            try {
                if(p.kind in listOf(Kind.DRAG_MOVE,Kind.SCROLL,Kind.END) && p.gesture!=gesture) return@execute
                when(p.kind) {
                    Kind.MOVE,Kind.DRAG_MOVE -> mouse.move(p.x,p.y)
                    Kind.TAP -> mouse.click()
                    Kind.DRAG_BEGIN -> { mouse.cancel(); gesture=p.gesture; mouse.down() }
                    Kind.SCROLL_BEGIN -> { mouse.cancel(); gesture=p.gesture }
                    Kind.SCROLL -> mouse.scroll(p.y)
                    Kind.END -> { mouse.cancel(); gesture=0 }
                    Kind.CANCEL,Kind.MODE -> release()
                    Kind.KEY -> {
                        val usage=HidReports.consumerUsage(p.code)
                        if(usage!=0) { if(p.flag) consumer=p.code else if(consumer==p.code) consumer=0; report(2,HidReports.consumer(HidReports.consumerUsage(consumer))) }
                        else { val k=HidReports.keyboardUsage(p.code); if(k!=0) { if(p.flag) keys.add(k) else keys.remove(k); report(3,HidReports.keyboard(keys)) } }
                    }
                    Kind.PINCH_BEGIN -> notify(Packet(Kind.ERROR,text="蓝牙鼠标不支持真实双指缩放，请使用局域网触摸模式"))
                    Kind.CENTER -> notify(Packet(Kind.ERROR,text="蓝牙鼠标无法读取目标屏幕尺寸，不能精确居中"))
                }
            } catch(e:Exception) { fail(e.message ?: "蓝牙输入失败") }
        } } catch(_:RejectedExecutionException) { fail("蓝牙输入积压，连接已停止以释放按压") }
    }
    private fun release() { mouse.cancel(); keys.clear(); consumer=0; gesture=0; report(2,HidReports.consumer(0)); report(3,HidReports.keyboard(emptyList())) }
    private fun fail(message:String) { if(!closed) { close(); notify(Packet(Kind.STOP,text=message)) } }
    fun close() {
        if(closed) return
        closed=true; main.removeCallbacks(timeout); writer.queue.clear()
        try { writer.execute {
            try { if(host!=null) release() } catch(_:Exception) {}
            val hid=profile; try { host?.let { hid?.disconnect(it) }; hid?.unregisterApp() } catch(_:Exception) {}
            try { hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE,it) } } catch(_:Exception) {}
            host=null; profile=null
        } } catch(_:RejectedExecutionException) {}
        writer.shutdown()
    }
}
