package dev.controlpad.controller

import android.media.*
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import dev.controlpad.shared.*

class VideoDecoder(private val onError:(String)->Unit={}) {
    private var codec:MediaCodec?=null
    private var format:Packet?=null
    private var surface:Surface?=null
    private var outputThread:HandlerThread?=null
    private var outputHandler:Handler?=null
    @Volatile var frames=0L; private set
    @Synchronized fun surface(surface:Surface?) {
        if(this.surface===surface && codec!=null) return
        closeCodec(); this.surface=surface
        if(surface!=null) try { format?.let { configure(it) } } catch(e:Exception) { closeCodec(); onError(e.message ?: "无法创建解码器") }
    }
    @Synchronized fun packet(p:Packet) {
        if(p.kind==Kind.VIDEO_FORMAT) { format=p; closeCodec(); if(surface!=null) configure(p); return }
        if(p.kind!=Kind.VIDEO_FRAME) return
        val c=codec ?: return
        drain(c)
        var index=c.dequeueInputBuffer(10000)
        val deadline=SystemClock.elapsedRealtime()+250
        while(index<0 && SystemClock.elapsedRealtime()<deadline) { drain(c); index=c.dequeueInputBuffer(10000) }
        // 不丢弃任意 H.264 预测帧，否则后续画面可能持续花屏直至下个关键帧。
        check(index>=0) { "解码持续积压，请关闭辅助画面后重试" }
        val input=c.getInputBuffer(index)!!; require(p.blob.size<=input.capacity()) { "视频帧超出解码缓冲区" }
        input.clear(); input.put(p.blob); c.queueInputBuffer(index,0,p.blob.size,p.time,p.code)
        drain(c)
    }
    private fun configure(p:Packet) {
        val created=MediaCodec.createDecoderByType("video/avc")
        codec=created
        try {
            created.configure(MediaFormat.createVideoFormat("video/avc",p.x.toInt(),p.y.toInt()).apply { setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,2*1024*1024) },surface,null,0)
            created.start()
            val worker=HandlerThread("preview-decoder-output").apply { start() }; outputThread=worker
            val handler=Handler(worker.looper); outputHandler=handler
            handler.post(object:Runnable {
                override fun run() {
                    synchronized(this@VideoDecoder) {
                        if(codec!==created) return
                        try { drain(created); handler.postDelayed(this,8) }
                        catch(e:Exception) { closeCodec(); onError(e.message ?: "读取解码画面失败") }
                    }
                }
            })
        } catch(e:Exception) { closeCodec(); throw e }
    }
    private fun drain(c:MediaCodec) {
        val info=MediaCodec.BufferInfo(); var latest=-1
        try {
            while(true) {
                val i=c.dequeueOutputBuffer(info,0)
                if(i>=0) {
                    if(latest>=0) c.releaseOutputBuffer(latest,false)
                    latest=i
                } else if(i!=MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && i!=MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) break
            }
        } finally {
            // 一次排出多帧时只显示最新画面，避免旧画面依次挤入 Surface 队列。
            if(latest>=0) { c.releaseOutputBuffer(latest,true); frames++ }
        }
    }
    private fun closeCodec() {
        outputHandler?.removeCallbacksAndMessages(null); outputHandler=null
        outputThread?.quitSafely(); outputThread=null
        try { codec?.stop() } catch(_:Exception) {}; try { codec?.release() } catch(_:Exception) {}; codec=null
    }
    @Synchronized fun close() { closeCodec(); format=null; frames=0 }
}
