package com.stanislavlyalin.stopenuresis.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.stanislavlyalin.stopenuresis.R
import com.stanislavlyalin.stopenuresis.service.UsageMonitoringService
import kotlin.math.abs

class AlarmActivity : Activity() {

    private var touchStartX = 0f
    private val swipeDistance by lazy { resources.displayMetrics.density * SWIPE_DISTANCE_DP }

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
        setContentView(R.layout.activity_alarm)
        findViewById<View>(R.id.alarmScreen).setOnTouchListener { _, event ->
            handleSwipe(event)
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

    private fun handleSwipe(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchStartX = event.x
            MotionEvent.ACTION_UP -> {
                if (abs(event.x - touchStartX) >= swipeDistance) {
                    UsageMonitoringService.turnOffAlarm(this)
                    finish()
                }
            }
        }
        return true
    }

    private companion object {
        const val SWIPE_DISTANCE_DP = 96f
    }
}
