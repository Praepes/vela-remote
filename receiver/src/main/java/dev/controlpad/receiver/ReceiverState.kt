package dev.controlpad.receiver

import android.os.Handler
import android.os.Looper
import dev.controlpad.shared.Security
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet

object ReceiverState {
    @Volatile var service:ReceiverService?=null
    @Volatile var connectable=false
    @Volatile var needsOverlay=false
    @Volatile var status="尚未启动"
    @Volatile var root="等待授权"
    @Volatile var input="等待输入服务"
    @Volatile var fingerprint=""
    @Volatile var client="未连接"
    @Volatile var preview="画面预览已关闭"
    @Volatile var code=""
    @Volatile var qrToken=""
    val observers=CopyOnWriteArraySet<()->Unit>()
    private val handler=Handler(Looper.getMainLooper())
    fun pairing() { code="%06d".format(SecureRandom().nextInt(1000000)); qrToken=Security.randomToken(); changed() }
    fun changed() { handler.post { observers.forEach { it() } } }
}
