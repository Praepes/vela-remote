package dev.controlpad.receiver

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.*
import android.os.*
import dev.controlpad.shared.*
import kotlin.concurrent.thread

class ProjectionCapture(private val context:Context,private val software:Boolean,private val send:(Packet)->Unit) {
    @Volatile private var running=false
    private var projection:MediaProjection?=null
    private var display:VirtualDisplay?=null
    private var worker:Thread?=null
    fun start(result:Int,data:Intent) {
        running=true
        projection=(context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(result,data)
        projection!!.registerCallback(object:MediaProjection.Callback() { override fun onStop() { if(running) { close(); send(Packet(Kind.ERROR,code=Kind.PREVIEW,text="系统已停止备用采集")) } } },Handler(Looper.getMainLooper()))
        worker=thread(name="projection-encoder") {
            var codec:MediaCodec?=null; var surface:android.view.Surface?=null
            try {
                val metrics=context.resources.displayMetrics; val ratio=minOf(1f,360f/minOf(metrics.widthPixels,metrics.heightPixels))
                val all=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.contains("video/avc") }
                val info=all.firstOrNull { !it.name.startsWith("OMX.google.") && !it.name.startsWith("c2.android.") } ?: if(software) all.firstOrNull() else null
                require(info!=null) { "没有可用的硬件编码器" }
                val caps=requireNotNull(info.getCapabilitiesForType("video/avc").videoCapabilities)
                val w=(metrics.widthPixels*ratio).toInt()/caps.widthAlignment*caps.widthAlignment
                val h=(metrics.heightPixels*ratio).toInt()/caps.heightAlignment*caps.heightAlignment
                codec=MediaCodec.createByCodecName(info.name)
                codec.configure(MediaFormat.createVideoFormat("video/avc",w,h).apply { setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface); setInteger(MediaFormat.KEY_FRAME_RATE,10); setInteger(MediaFormat.KEY_BIT_RATE,600000); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1); setFloat("max-fps-to-encoder",10f) },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface=codec.createInputSurface(); codec.start()
                display=projection!!.createVirtualDisplay("ControlPadFallback",w,h,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,null)
                send(Packet(Kind.VIDEO_FORMAT,x=w.toFloat(),y=h.toFloat(),text="备用采集 · ${info.name}"))
                val buffer=MediaCodec.BufferInfo()
                while(running) {
                    val i=codec.dequeueOutputBuffer(buffer,10000)
                    if(i>=0) try { if(buffer.size>0) { val b=codec.getOutputBuffer(i)!!; b.position(buffer.offset); b.limit(buffer.offset+buffer.size); val bytes=ByteArray(buffer.size); b.get(bytes); send(Packet(Kind.VIDEO_FRAME,code=buffer.flags,blob=bytes,time=buffer.presentationTimeUs)) } } finally { codec.releaseOutputBuffer(i,false) }
                }
            } catch(e:Exception) { if(running) send(Packet(Kind.ERROR,code=Kind.PREVIEW,text="备用预览失败：${e.message}")) }
            finally { display?.release(); display=null; try { codec?.stop() } catch(_:Exception) {}; codec?.release(); surface?.release() }
        }
    }
    fun close() { if(!running) return; running=false; projection?.stop(); projection=null; if(worker!=Thread.currentThread()) worker?.join(1000) }
}
