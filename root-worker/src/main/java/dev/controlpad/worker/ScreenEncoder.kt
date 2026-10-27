package dev.controlpad.worker

import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.IBinder
import android.os.Bundle
import android.view.Surface
import dev.controlpad.shared.*
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Android 7/8 的 SurfaceControl 适配，仅采集普通显示内容。 */
class ScreenEncoder(private val allowSoftware:Boolean,private val send:(Packet)->Unit) {
    @Volatile private var worker:Thread?=null
    @Volatile private var running=false
    @Synchronized fun start(low:Boolean) {
        stop(); running=true
        worker=thread(name="screen-encoder",isDaemon=true) {
            var codec:MediaCodec?=null; var surface:Surface?=null; var token:IBinder?=null
            try {
                val global=Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)
                val info=global.javaClass.getMethod("getDisplayInfo",Int::class.javaPrimitiveType).invoke(global,0)
                fun field(name:String)=info.javaClass.getField(name).getInt(info)
                val w=field("logicalWidth"); val h=field("logicalHeight"); val rotation=field("rotation")
                val layer=field("layerStack"); val target=if(low) 360 else 540
                val ratio=minOf(1f,target.toFloat()/minOf(w,h))
                val candidates=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.any { t->t.equals("video/avc",true) } }
                val hardware=candidates.firstOrNull { !it.name.startsWith("OMX.google.") && !it.name.startsWith("c2.android.") }
                val chosen=hardware ?: if(allowSoftware) candidates.firstOrNull() else null
                require(chosen!=null) { "设备没有可用 H.264 硬件编码器" }
                val caps=requireNotNull(chosen.getCapabilitiesForType("video/avc").videoCapabilities)
                fun align(v:Float,a:Int)=((v.roundToInt()/a)*a).coerceAtLeast(a)
                val outW=align(w*ratio,caps.widthAlignment); val outH=align(h*ratio,caps.heightAlignment)
                require(caps.isSizeSupported(outW,outH)) { "编码器不支持预览尺寸 $outW × $outH" }
                val fmt=MediaFormat.createVideoFormat("video/avc",outW,outH).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE,if(low) 600000 else 1500000)
                    setInteger(MediaFormat.KEY_FRAME_RATE,if(low) 10 else 15); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                    setFloat("max-fps-to-encoder",if(low) 10f else 15f)
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,if(low) 100000L else 66666L)
                }
                codec=MediaCodec.createByCodecName(chosen.name); codec.configure(fmt,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface=codec.createInputSurface()
                val sc=Class.forName("android.view.SurfaceControl")
                token=sc.getMethod("createDisplay",String::class.java,Boolean::class.javaPrimitiveType).invoke(null,"ControlPad",false) as IBinder
                sc.getMethod("openTransaction").invoke(null)
                try {
                    sc.getMethod("setDisplaySurface",IBinder::class.java,Surface::class.java).invoke(null,token,surface)
                    sc.getMethod("setDisplayProjection",IBinder::class.java,Int::class.javaPrimitiveType,Rect::class.java,Rect::class.java)
                        .invoke(null,token,rotation,Rect(0,0,w,h),Rect(0,0,outW,outH))
                    sc.getMethod("setDisplayLayerStack",IBinder::class.java,Int::class.javaPrimitiveType).invoke(null,token,layer)
                } finally { sc.getMethod("closeTransaction").invoke(null) }
                codec.start()
                send(Packet(Kind.VIDEO_FORMAT,x=outW.toFloat(),y=outH.toFloat(),text=chosen.name))
                val buffer=MediaCodec.BufferInfo()
                while(running && !Thread.currentThread().isInterrupted) {
                    val index=codec.dequeueOutputBuffer(buffer,10000)
                    if(index>=0) {
                        try {
                            if(buffer.size>0) {
                                val data=codec.getOutputBuffer(index)!!; data.position(buffer.offset); data.limit(buffer.offset+buffer.size)
                                val bytes=ByteArray(buffer.size); data.get(bytes)
                                send(Packet(Kind.VIDEO_FRAME,code=buffer.flags,blob=bytes,time=buffer.presentationTimeUs))
                            }
                        } finally { codec.releaseOutputBuffer(index,false) }
                    }
                }
            } catch(e:Throwable) { if(running) try { send(Packet(Kind.ERROR,code=Kind.PREVIEW,text="预览不可用：${e.cause?.message ?: e.message}")) } catch(_:Exception) {} }
            finally {
                try { if(token!=null) Class.forName("android.view.SurfaceControl").getMethod("destroyDisplay",IBinder::class.java).invoke(null,token) } catch(_:Exception) {}
                try { codec?.stop() } catch(_:Exception) {}; try { codec?.release() } catch(_:Exception) {}; surface?.release()
            }
        }
    }
    @Synchronized fun stop() {
        running=false
        val old=worker; old?.interrupt(); if(old!=Thread.currentThread()) old?.join(1200)
        worker=null
    }
}
