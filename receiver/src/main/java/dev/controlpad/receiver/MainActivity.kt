package dev.controlpad.receiver

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.controlpad.shared.*
import java.net.NetworkInterface

class MainActivity:AppCompatActivity() {
    private lateinit var ui:Ui
    private lateinit var state:TextView; private lateinit var details:TextView; private lateinit var pair:TextView
    private lateinit var qr:ImageView
    private var pendingStart=false; private var renderedToken=""
    private val refreshHandler=Handler(Looper.getMainLooper())
    private val refreshAddress=object:Runnable { override fun run() { refresh(); refreshHandler.postDelayed(this,2000) } }
    private val prefs by lazy { getSharedPreferences("receiver",MODE_PRIVATE) }
    private val observer:()->Unit={ refresh() }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); ui=Ui(this); window.statusBarColor=ui.background; window.navigationBarColor=ui.background
        setContentView(R.layout.activity_main)
        val root=findViewById<FrameLayout>(R.id.root); root.setBackgroundColor(ui.background)
        val scroll=ScrollView(this); val body=ui.column().apply { setPadding(ui.dp(28),ui.dp(16),ui.dp(28),ui.dp(28)) }; scroll.addView(body); root.addView(scroll)
        body.addView(ui.text("轻控",30f,true)); body.addView(ui.text("让旧设备，也能轻松操控。",15f,color=ui.muted)); body.addView(ui.space(14))
        val row=ui.row().apply { gravity=Gravity.TOP }
        val left=ui.column(); val right=ui.column()
        row.addView(left,LinearLayout.LayoutParams(0,-2,1.15f).apply { rightMargin=ui.dp(18) }); row.addView(right,LinearLayout.LayoutParams(0,-2,1f)); body.addView(row)
        val statusCard=ui.column(); statusCard.addView(ui.text("受控设备",13f,color=ui.muted)); statusCard.addView(ui.text(Build.MODEL,22f,true))
        state=ui.text("尚未启动",17f,true); details=ui.text("",13f,color=ui.muted); statusCard.addView(state); statusCard.addView(details); left.addView(ui.card(statusCard))
        left.addView(ui.space(12)); left.addView(ui.button("启动遥控服务",true) { startReceiver() },LinearLayout.LayoutParams(-1,ui.dp(56)))
        left.addView(ui.space(8)); left.addView(ui.button("停止服务") { stopService(Intent(this,ReceiverService::class.java)) },LinearLayout.LayoutParams(-1,ui.dp(52)))
        left.addView(Switch(this).apply { text="无 Root：使用无障碍兼容模式"; setTextColor(ui.ink); minHeight=ui.dp(52); isChecked=prefs.getString("input_backend","auto")=="accessibility"; setOnCheckedChangeListener { _,v->prefs.edit().putString("input_backend",if(v) "accessibility" else "auto").apply(); Toast.makeText(this@MainActivity,"停止服务后重新启动生效",Toast.LENGTH_SHORT).show() } })
        left.addView(ui.button("开启无障碍遥控") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },LinearLayout.LayoutParams(-1,ui.dp(52)))
        left.addView(ui.space(8)); left.addView(ui.button("授权兼容光标") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) },LinearLayout.LayoutParams(-1,ui.dp(52)))
        val auto=Switch(this).apply { text="开机自动待命"; isChecked=prefs.getBoolean("autostart",true); setTextColor(ui.ink); minHeight=ui.dp(52); setOnCheckedChangeListener { _,checked->prefs.edit().putBoolean("autostart",checked).apply() } }; left.addView(auto)
        if(BuildConfig.DEBUG) left.addView(Switch(this).apply { text="调试：允许模拟器软件编码"; isChecked=prefs.getBoolean("software_preview",false); setTextColor(ui.muted); setOnCheckedChangeListener { _,v->prefs.edit().putBoolean("software_preview",v).apply(); Toast.makeText(this@MainActivity,"重启服务后生效；不计为硬件性能通过",Toast.LENGTH_LONG).show() } })
        left.addView(ui.button("备用画面采集授权") { startActivity(Intent(this,ProjectionActivity::class.java)) },LinearLayout.LayoutParams(-1,ui.dp(52)))
        left.addView(ui.space(8)); left.addView(ui.button("打开输入测试页") { startActivity(Intent(this,InputLabActivity::class.java)) },LinearLayout.LayoutParams(-1,ui.dp(52)))
        val pairCard=ui.column(); pairCard.addView(ui.text("连接这台设备",20f,true)); pair=ui.text("启动服务后显示配对信息",15f); pairCard.addView(pair)
        qr=ImageView(this).apply { contentDescription="主控端扫描此二维码配对"; setBackgroundColor(android.graphics.Color.WHITE) }; pairCard.addView(qr,LinearLayout.LayoutParams(ui.dp(174),ui.dp(174)).apply { gravity=Gravity.CENTER_HORIZONTAL })
        pairCard.addView(ui.text("主控端选择这台设备，输入六位配对码；也可直接扫描二维码。",13f,color=ui.muted)); right.addView(ui.card(pairCard))
        right.addView(ui.space(12)); right.addView(ui.button("更新一次性配对码") { ReceiverState.pairing() },LinearLayout.LayoutParams(-1,ui.dp(52)))
        right.addView(ui.space(8)); right.addView(ui.button("撤销全部已配对设备") { android.app.AlertDialog.Builder(this).setTitle("撤销配对？").setMessage("当前连接将断开，主控端需要重新配对。").setPositiveButton("撤销") { _,_->ReceiverState.service?.revoke() }.setNegativeButton("取消",null).show() },LinearLayout.LayoutParams(-1,ui.dp(52)))
        refresh()
    }
    private fun startReceiver() {
        if(prefs.getString("input_backend","auto")=="accessibility" && RemoteAccessibilityService.instance==null) { Toast.makeText(this,"请先开启“轻控 · 无障碍遥控”",Toast.LENGTH_LONG).show(); startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return }
        if(prefs.getString("input_backend","auto")=="accessibility" && !Settings.canDrawOverlays(this)) {
            pendingStart=true; startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))); return
        }
        val intent=Intent(this,ReceiverService::class.java)
        if(Build.VERSION.SDK_INT>=26) startForegroundService(intent) else startService(intent)
    }
    override fun onResume() { super.onResume(); ReceiverState.observers.add(observer); refreshHandler.post(refreshAddress); if(pendingStart) { pendingStart=false; if(Settings.canDrawOverlays(this)) startReceiver() } }
    override fun onPause() { refreshHandler.removeCallbacks(refreshAddress); ReceiverState.observers.remove(observer); super.onPause() }
    private fun refresh() {
        if(!::state.isInitialized) return
        state.text=ReceiverState.status
        details.text="Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\nRoot：${ReceiverState.root}\n输入：${ReceiverState.input}\n无障碍：${if(RemoteAccessibilityService.instance!=null) "已开启" else "未开启"}\n兼容光标：${if(Settings.canDrawOverlays(this)) "已授权" else "未授权（系统鼠标不需要）"}\n${ReceiverState.client}\n${ReceiverState.preview}"
        val address=try {
            val cm=getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val active=cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            (active ?: NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address })?.hostAddress ?: "未联网"
        } catch(_:Exception) { "未联网" }
        val fingerprint=ReceiverState.fingerprint
        pair.text="配对码：${ReceiverState.code}\n地址：$address:${Kind.CONTROL_PORT}"
        val qrKey="$address:$fingerprint:${ReceiverState.qrToken}"
        if(fingerprint.isNotEmpty() && qrKey!=renderedToken) {
            renderedToken=qrKey
            val uri=Uri.Builder().scheme("controlpad").authority("pair").appendQueryParameter("host",address).appendQueryParameter("port",Kind.CONTROL_PORT.toString()).appendQueryParameter("fp",fingerprint).appendQueryParameter("token",ReceiverState.qrToken).build().toString()
            val matrix=QRCodeWriter().encode(uri,BarcodeFormat.QR_CODE,480,480)
            val pixels=IntArray(480*480) { i->if(matrix[i%480,i/480]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            qr.setImageBitmap(Bitmap.createBitmap(pixels,480,480,Bitmap.Config.ARGB_8888))
        }
    }
}
