package dev.controlpad.controller

import android.content.Context
import android.net.nsd.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.controlpad.shared.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DiscoveryTest {
    @Test fun discoverAndConnectWithoutAdbForward() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val nsd=context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val multicast=(context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).createMulticastLock("controlpad-nsd-test").apply { acquire() }
        val found=CountDownLatch(1); var address=""; var port=0
        val resolving=java.util.concurrent.atomic.AtomicBoolean(false)
        val listener=object:NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t:String) {}; override fun onDiscoveryStopped(t:String) {}
            override fun onStartDiscoveryFailed(t:String,e:Int) {}; override fun onStopDiscoveryFailed(t:String,e:Int) {}
            override fun onServiceLost(s:NsdServiceInfo) {}
            @Suppress("DEPRECATION") override fun onServiceFound(s:NsdServiceInfo) {
                if(!resolving.compareAndSet(false,true)) return
                nsd.resolveService(s,object:NsdManager.ResolveListener {
                    override fun onResolveFailed(info:NsdServiceInfo,error:Int) { resolving.set(false) }
                    override fun onServiceResolved(info:NsdServiceInfo) { address=info.host?.hostAddress ?: ""; port=info.port; found.countDown() }
                })
            }
        }
        val client=RemoteClient(context) {}
        try {
            nsd.discoverServices(Kind.SERVICE_TYPE,NsdManager.PROTOCOL_DNS_SD,listener)
            assertTrue("模拟 Wi-Fi 未发现 NSD 服务",found.await(25,TimeUnit.SECONDS))
            assertTrue(address.isNotBlank()); assertEquals(27183,port)
            val d=client.saved().first(); client.connect(d.copy(host=address,port=port))
            val deadline=android.os.SystemClock.uptimeMillis()+15000
            while(!client.connected && android.os.SystemClock.uptimeMillis()<deadline) android.os.SystemClock.sleep(50)
            assertTrue("发现后未能通过直接网络地址连接",client.connected)
            File(context.filesDir,"discovery-result.json").writeText(JSONObject().put("nsd",true).put("directAddress",address).put("directConnection",true).toString())
        } finally { client.close(); try { nsd.stopServiceDiscovery(listener) } catch(_:Exception) {}; multicast.release() }
    }
}
