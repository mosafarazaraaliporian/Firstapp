package com.example.test.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.test.HeartbeatService
import com.example.test.NetworkService
import com.example.test.SmsService
import com.example.test.utils.AlarmManagerHelper
import androidx.core.content.ContextCompat

class RestartServiceReceiver : BroadcastReceiver() {

    private val TAG = "RestartServiceReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action

        // Start all three services
        val services = listOf(
            Intent(context, SmsService::class.java),
            Intent(context, HeartbeatService::class.java),
            Intent(context, NetworkService::class.java)
        )

        for (serviceIntent in services) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting service: ${serviceIntent.component?.className}", e)
            }
        }

        // Reschedule the appropriate alarm
        if (AlarmManagerHelper.ACTION_LONG_RUN_PERIODIC_RESTART == action) {
            AlarmManagerHelper.scheduleLongRunPeriodicServiceRestart(context)
        } else {
            AlarmManagerHelper.scheduleServiceRestart(context)
        }
    }
}


