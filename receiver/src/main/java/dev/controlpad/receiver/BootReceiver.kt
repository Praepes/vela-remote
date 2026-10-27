package dev.controlpad.receiver

import android.content.*
import android.os.Build

class BootReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val prefs=context.getSharedPreferences("receiver",Context.MODE_PRIVATE)
        if(intent.action==Intent.ACTION_BOOT_COMPLETED && prefs.getBoolean("initialized",false) && prefs.getBoolean("autostart",true)) {
            val service=Intent(context,ReceiverService::class.java)
            if(Build.VERSION.SDK_INT>=26) context.startForegroundService(service) else context.startService(service)
        }
    }
}
