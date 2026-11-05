package com.stanislavlyalin.stopenuresis.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.WindowManager
import android.widget.TextClock
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import com.stanislavlyalin.stopenuresis.R
import com.stanislavlyalin.stopenuresis.service.UsageMonitoringService

class AlarmActivity : Activity() {

    private val alarmStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsageMonitoringService.ACTION_STATUS) return
            if (intent.getStringExtra(UsageMonitoringService.EXTRA_STATE) !=
                UsageMonitoringService.STATE_ALARMING
            ) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false
        findViewById<TextClock>(R.id.alarmClock).doOnLayout {
            val clock = it as TextClock
            val textWidth = clock.paint.measureText(clock.text.toString())
            if (textWidth > clock.width) {
                clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, clock.textSize * clock.width / textWidth)
            }
        }
        findViewById<AlarmDismissView>(R.id.alarmDismiss).onDismiss = {
            UsageMonitoringService.turnOffAlarm(this)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            alarmStatusReceiver,
            IntentFilter(UsageMonitoringService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (UsageMonitoringService.stateSnapshot != UsageMonitoringService.STATE_ALARMING) {
            finish()
        }
    }

    override fun onStop() {
        unregisterReceiver(alarmStatusReceiver)
        super.onStop()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

}
