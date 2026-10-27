package dev.controlpad.receiver

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class RemoteAccessibilityService:AccessibilityService() {
    companion object { @Volatile var instance:RemoteAccessibilityService?=null; private set }
    override fun onServiceConnected() { instance=this; ReceiverState.changed() }
    override fun onAccessibilityEvent(event:AccessibilityEvent?) {}
    override fun onInterrupt() { ReceiverState.service?.cancelAccessibilityInput() }
    override fun onUnbind(intent:android.content.Intent?):Boolean { if(instance===this) instance=null; ReceiverState.service?.accessibilityLost(); ReceiverState.changed(); return super.onUnbind(intent) }
    override fun onDestroy() { if(instance===this) instance=null; ReceiverState.service?.accessibilityLost(); ReceiverState.changed(); super.onDestroy() }
}
