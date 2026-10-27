package dev.controlpad.controller

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.controlpad.shared.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Device benchmark: reports measured LAN/root round trips rather than asserting a router-specific limit. */
@RunWith(AndroidJUnit4::class)
class PointerLatencyTest {
    @Test fun measurePointerRoundTripsAndStreamTail() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val cursors=LinkedBlockingQueue<Packet>()
        val client=RemoteClient(inst.targetContext) { if(it.kind==Kind.CURSOR) cursors.offer(it) }
        fun await(message:String,timeout:Long=15000,test:()->Boolean) {
            val deadline=SystemClock.uptimeMillis()+timeout
            while(!test() && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(10)
            assertTrue(message,test())
        }
        try {
            client.connect(client.saved().first())
            await("受控端未连接") { client.connected && client.displayVersion>0 }
            client.send(Packet(Kind.MODE,code=InputMode.TOUCH,flag=true))
            SystemClock.sleep(200)
            cursors.clear(); client.send(Packet(Kind.CENTER))
            val center=requireNotNull(cursors.poll(3,TimeUnit.SECONDS)) { "没有光标反馈" }
            val samples=mutableListOf<Double>()
            repeat(60) { i ->
                cursors.clear()
                val start=SystemClock.elapsedRealtimeNanos()
                client.send(Packet(Kind.MOVE,x=if(i%2==0) 1f else -1f))
                assertNotNull("移动反馈超时",cursors.poll(3,TimeUnit.SECONDS))
                samples.add((SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0)
                SystemClock.sleep(8)
            }
            cursors.clear()
            repeat(240) {
                client.send(Packet(Kind.MOVE,x=0.25f))
                SystemClock.sleep(8)
            }
            val released=SystemClock.elapsedRealtimeNanos()
            client.send(Packet(Kind.MOVE,x=0.125f))
            val expected=center.x+60.125f
            var position=Float.NaN
            await("连续移动丢失位移") {
                var packet=cursors.poll()
                while(packet!=null) { position=packet.x; packet=cursors.poll() }
                kotlin.math.abs(position-expected)<0.01f
            }
            val tail=(SystemClock.elapsedRealtimeNanos()-released)/1_000_000.0
            val sorted=samples.sorted()
            val result=JSONObject().put("samples",samples.size).put("roundTripMedianMs",sorted[sorted.size/2])
                .put("roundTripP95Ms",sorted[(sorted.size*0.95).toInt()]).put("roundTripMaxMs",sorted.last())
                .put("streamTailMs",tail).put("movementPreserved",true)
            File(inst.targetContext.filesDir,"pointer-latency-result.json").writeText(result.toString())
        } finally { client.close() }
    }
}
