package dev.controlpad.receiver

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class ProjectionActivity:Activity() {
    override fun onCreate(state:Bundle?) { super.onCreate(state); if(state==null) startActivityForResult((getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent(),42) }
    @Deprecated("旧版受控系统兼容") override fun onActivityResult(request:Int,result:Int,data:Intent?) {
        super.onActivityResult(request,result,data)
        if(request==42 && result==RESULT_OK && data!=null) ReceiverState.service?.startProjection(result,data)
        finish()
    }
}
