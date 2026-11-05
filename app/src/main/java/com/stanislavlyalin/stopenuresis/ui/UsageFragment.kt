package com.stanislavlyalin.stopenuresis.ui

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.stanislavlyalin.stopenuresis.AppSettings
import com.stanislavlyalin.stopenuresis.AppStorage
import com.stanislavlyalin.stopenuresis.R
import com.stanislavlyalin.stopenuresis.service.UsageMonitoringService
import com.stanislavlyalin.stopenuresis.training.ExtraTreesModel
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UsageFragment : Fragment(R.layout.fragment_usage) {

    private lateinit var tvUsageStatusCircle: TextView
    private lateinit var layoutRustlingDetections: LinearLayout
    private lateinit var tvNextAlarmAllowed: TextView
    private lateinit var btnUsageMain: Button
    private lateinit var cbSaveSamples: CheckBox
    private var usageState = UsageState.IDLE
    private var cooldownUntilWallClockMillis = 0L
    private var rustlingEpisodeCount = 0
    private var requiredRustlingEpisodes = AppSettings.DEFAULT_ALARM_RUSTLING_EPISODES
    private var displayedRustlingEpisodeSlots = 0

    private val usageStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsageMonitoringService.ACTION_STATUS) return
            cooldownUntilWallClockMillis = intent.getLongExtra(
                UsageMonitoringService.EXTRA_COOLDOWN_UNTIL_WALL_CLOCK,
                0L
            )
            rustlingEpisodeCount = intent.getIntExtra(
                UsageMonitoringService.EXTRA_RUSTLING_EPISODE_COUNT,
                0
            )
            requiredRustlingEpisodes = intent.getIntExtra(
                UsageMonitoringService.EXTRA_REQUIRED_RUSTLING_EPISODES,
                AppSettings.DEFAULT_ALARM_RUSTLING_EPISODES
            )
            setUsageState(stateFromService(intent.getStringExtra(UsageMonitoringService.EXTRA_STATE)))
            updateRustlingDetectionIndicator()
            if (cooldownUntilWallClockMillis > 0L) {
                showCooldownMessage()
            } else {
                clearCooldownMessage()
            }
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startListening()
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.recordAudioPermissionNeeded),
                    Toast.LENGTH_SHORT
                ).show()
                setUsageState(UsageState.IDLE)
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvUsageStatusCircle = view.findViewById(R.id.tvUsageStatusCircle)
        layoutRustlingDetections = view.findViewById(R.id.layoutRustlingDetections)
        tvNextAlarmAllowed = view.findViewById(R.id.tvNextAlarmAllowed)
        btnUsageMain = view.findViewById(R.id.btnUsageMain)
        cbSaveSamples = view.findViewById(R.id.cbSaveSamples)

        cbSaveSamples.isChecked = AppSettings.isUsageSampleSavingEnabled(requireContext())
        cbSaveSamples.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setUsageSampleSavingEnabled(requireContext(), isChecked)
        }

        btnUsageMain.setOnClickListener {
            when (usageState) {
                UsageState.IDLE -> ensurePermissionAndStart()
                UsageState.MONITORING, UsageState.PAUSED -> stopListening()
                UsageState.ALARMING -> turnOffAlarm()
            }
        }

        setUsageState(UsageState.IDLE)
        updateRustlingDetectionIndicator()
    }

    override fun onStart() {
        super.onStart()
        registerUsageReceiver()
        syncUsageStateFromService()
    }

    override fun onStop() {
        super.onStop()
        unregisterUsageReceiver()
    }

    private fun ensurePermissionAndStart() {
        if (!modelFile().exists()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.modelFileNotFound),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startListening()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening(checkFullScreenIntentPermission: Boolean = true) {
        if (usageState != UsageState.IDLE) return

        if (
            checkFullScreenIntentPermission &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !requireContext().getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            showFullScreenIntentPermissionDialog()
            return
        }

        if (loadModel() == null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.modelFileNotFound),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        setUsageState(UsageState.PAUSED)
        rustlingEpisodeCount = 0
        requiredRustlingEpisodes = AppSettings.getAlarmRustlingEpisodes(requireContext())
        updateRustlingDetectionIndicator()
        UsageMonitoringService.start(requireContext())
    }

    private fun showFullScreenIntentPermissionDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.fullScreenAlarmPermissionTitle)
            .setMessage(R.string.fullScreenAlarmPermissionMessage)
            .setPositiveButton(R.string.openSettings) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                )
            }
            .setNegativeButton(R.string.continueWithoutLockScreen) { _, _ ->
                startListening(checkFullScreenIntentPermission = false)
            }
            .show()
    }

    private fun stopListening() {
        UsageMonitoringService.stop(requireContext())
        clearCooldownMessage()
        setUsageState(UsageState.IDLE)
    }

    private fun turnOffAlarm() {
        UsageMonitoringService.turnOffAlarm(requireContext())
    }

    private fun setUsageState(state: UsageState) {
        usageState = state
        tvUsageStatusCircle.text = when (state) {
            UsageState.IDLE -> getString(R.string.usageHello)
            UsageState.MONITORING -> getString(R.string.usageSleeping)
            UsageState.ALARMING -> getString(R.string.usageGoToToilet)
            UsageState.PAUSED -> getString(R.string.usagePaused)
        }

        btnUsageMain.text = when (state) {
            UsageState.IDLE -> getString(R.string.startListening)
            UsageState.MONITORING -> getString(R.string.stopListening)
            UsageState.ALARMING -> getString(R.string.turnOffAlarm)
            UsageState.PAUSED -> getString(R.string.stopListening)
        }
        layoutRustlingDetections.visibility =
            if (state == UsageState.MONITORING) View.VISIBLE else View.GONE
        if (::cbSaveSamples.isInitialized) {
            cbSaveSamples.isEnabled = state == UsageState.IDLE
        }
    }

    private fun updateRustlingDetectionIndicator() {
        if (!::layoutRustlingDetections.isInitialized) return

        val requiredCount = requiredRustlingEpisodes.coerceAtLeast(1)
        val activeCount = rustlingEpisodeCount.coerceIn(0, requiredCount)
        if (displayedRustlingEpisodeSlots != requiredCount) {
            rebuildRustlingDetectionIndicator(requiredCount)
        }

        for (index in 0 until layoutRustlingDetections.childCount) {
            val color = if (index < activeCount) {
                ContextCompat.getColor(requireContext(), R.color.brand_orange)
            } else {
                Color.rgb(190, 190, 190)
            }
            layoutRustlingDetections.getChildAt(index).background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(color)
                }
        }
    }

    private fun rebuildRustlingDetectionIndicator(requiredCount: Int) {
        layoutRustlingDetections.removeAllViews()
        displayedRustlingEpisodeSlots = requiredCount

        repeat(requiredCount) {
            layoutRustlingDetections.addView(
                View(requireContext()),
                LinearLayout.LayoutParams(dp(44), dp(64)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun showCooldownMessage() {
        if (cooldownUntilWallClockMillis <= 0L) return

        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        tvNextAlarmAllowed.text = getString(
            R.string.usagePauseUntil,
            formatter.format(Date(cooldownUntilWallClockMillis))
        )
        tvNextAlarmAllowed.visibility = View.VISIBLE
    }

    private fun clearCooldownMessage() {
        cooldownUntilWallClockMillis = 0L
        if (::tvNextAlarmAllowed.isInitialized) {
            tvNextAlarmAllowed.visibility = View.GONE
        }
    }

    private fun registerUsageReceiver() {
        ContextCompat.registerReceiver(
            requireContext(), usageStatusReceiver,
            IntentFilter(UsageMonitoringService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterUsageReceiver() {
        runCatching { requireContext().unregisterReceiver(usageStatusReceiver) }
    }

    private fun syncUsageStateFromService() {
        cooldownUntilWallClockMillis = UsageMonitoringService.cooldownUntilWallClockSnapshot
        rustlingEpisodeCount = UsageMonitoringService.rustlingEpisodeCountSnapshot
        requiredRustlingEpisodes = UsageMonitoringService.requiredRustlingEpisodesSnapshot
        setUsageState(stateFromService(UsageMonitoringService.stateSnapshot))
        updateRustlingDetectionIndicator()
        if (cooldownUntilWallClockMillis > 0L) {
            showCooldownMessage()
        } else {
            clearCooldownMessage()
        }
    }

    private fun stateFromService(state: String?): UsageState {
        return when (state) {
            UsageMonitoringService.STATE_MONITORING -> UsageState.MONITORING
            UsageMonitoringService.STATE_ALARMING -> UsageState.ALARMING
            UsageMonitoringService.STATE_PAUSED -> UsageState.PAUSED
            else -> UsageState.IDLE
        }
    }

    private fun loadModel(): ExtraTreesModel? {
        return runCatching {
            ExtraTreesModel.fromJson(JSONObject(modelFile().readText()))
        }.getOrNull()
    }

    private fun modelFile(): File {
        return AppStorage.modelFile(requireContext())
    }

    private enum class UsageState {
        IDLE,
        MONITORING,
        ALARMING,
        PAUSED
    }

}
