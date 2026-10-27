package dev.controlpad.controller

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Decode the receiver's actual screen, then deliver the same result as the camera scanner. */
@RunWith(AndroidJUnit4::class)
class QrPairingTest {
    @Test fun pairFromReceiverScreenshotOverLan() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val path=requireNotNull(InstrumentationRegistry.getArguments().getString("pairingScreenshot"))
        val bitmap=requireNotNull(BitmapFactory.decodeFile(path))
        val pixels=IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val result=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels))))
        bitmap.recycle()
        val activity=inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            inst.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply { isAccessible=true }
                    .invoke(activity,IntentIntegrator.REQUEST_CODE,Activity.RESULT_OK,
                        Intent().putExtra("SCAN_RESULT",result.text).putExtra("SCAN_RESULT_FORMAT","QR_CODE"))
            }
            val deadline=SystemClock.uptimeMillis()+15000
            while((!activity.client.connected || activity.client.displayVersion==0) && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(50)
            assertTrue("二维码配对未建立局域网连接",activity.client.connected)
            assertTrue("没有收到受控端显示信息",activity.client.displayVersion>0)
            assertEquals(android.net.Uri.parse(result.text).getQueryParameter("host"),activity.client.device?.host)
            assertEquals(64,activity.client.device?.pin?.length)
            assertEquals(48,activity.client.device?.token?.length)
        } finally { inst.runOnMainSync { activity.client.disconnect(); activity.finish() } }
    }
}
