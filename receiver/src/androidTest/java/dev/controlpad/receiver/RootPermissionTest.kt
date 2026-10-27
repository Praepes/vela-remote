package dev.controlpad.receiver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RootPermissionTest {
    @Test fun applicationUidCanRequestSu() {
        assertNotEquals("必须从普通应用身份发起",0,android.os.Process.myUid())
        val p=ProcessBuilder("su","-c","id").redirectErrorStream(true).start()
        assertTrue("su 授权超时",p.waitFor(20,TimeUnit.SECONDS))
        val output=p.inputStream.bufferedReader().readText()
        val denied=InstrumentationRegistry.getArguments().getString("expectDenied")=="true"
        if(denied) assertFalse("拒绝授权后仍获得 Root",output.contains("uid=0"))
        else { assertEquals(output,0,p.exitValue()); assertTrue(output,output.contains("uid=0")) }
    }
}
