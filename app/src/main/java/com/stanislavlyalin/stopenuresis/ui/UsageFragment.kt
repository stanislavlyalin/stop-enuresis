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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.stanislavlyalin.stopenuresis.training.LogisticRegressionModel
import com.stanislavlyalin.stopenuresis.training.MfccExtractor
import com.stanislavlyalin.stopenuresis.training.WavAudio
import com.stanislavlyalin.stopenuresis.audio.StreamingMovingAverageSmoother
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class UsageFragment : Fragment(R.layout.fragment_usage) {

    private lateinit var tvUsageStatusCircle: TextView
    private lateinit var layoutRustlingDetections: LinearLayout
    private lateinit var tvNextAlarmAllowed: TextView
    private lateinit var btnUsageMain: Button
    private lateinit var cbSaveSamples: CheckBox
    private lateinit var layoutUsageFeedback: LinearLayout
    private lateinit var btnFalseAlarm: Button
    private lateinit var btnSuccess: Button

    private val isListening = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mfccExtractor = MfccExtractor()

    private var usageState = UsageState.IDLE
    private var audioRecord: AudioRecord? = null
    private var listeningThread: Thread? = null
    private var toneGenerator: ToneGenerator? = null
    @Volatile
    private var cooldownUntilElapsedRealtime = 0L
    private var cooldownUntilWallClockMillis = 0L
    private var rustlingDetectionCount = 0
    private var requiredRustlingDetectionsPerMinute =
        AppSettings.DEFAULT_ALARM_RUSTLING_DETECTIONS_PER_MINUTE
    private var displayedRustlingDetectionSlots = 0

    private val usageStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsageMonitoringService.ACTION_STATUS) return
            cooldownUntilWallClockMillis = intent.getLongExtra(
                UsageMonitoringService.EXTRA_COOLDOWN_UNTIL_WALL_CLOCK,
                0L
            )
            rustlingDetectionCount = intent.getIntExtra(
                UsageMonitoringService.EXTRA_RUSTLING_DETECTION_COUNT,
                0
            )
            requiredRustlingDetectionsPerMinute = intent.getIntExtra(
                UsageMonitoringService.EXTRA_REQUIRED_RUSTLING_DETECTIONS_PER_MINUTE,
                AppSettings.DEFAULT_ALARM_RUSTLING_DETECTIONS_PER_MINUTE
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

    private val alarmTick = object : Runnable {
        override fun run() {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, ALARM_TONE_MS)
            mainHandler.postDelayed(this, ALARM_REPEAT_MS)
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
        layoutUsageFeedback = view.findViewById(R.id.layoutUsageFeedback)
        btnFalseAlarm = view.findViewById(R.id.btnFalseAlarm)
        btnSuccess = view.findViewById(R.id.btnSuccess)

        cbSaveSamples.isChecked = AppSettings.isUsageSampleSavingEnabled(requireContext())
        cbSaveSamples.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setUsageSampleSavingEnabled(requireContext(), isChecked)
        }

        btnUsageMain.setOnClickListener {
            when (usageState) {
                UsageState.IDLE -> ensurePermissionAndStart()
                UsageState.MONITORING -> stopListening()
                UsageState.ALARMING -> turnOffAlarm()
                UsageState.POST_ALARM -> Unit
            }
        }
        btnFalseAlarm.setOnClickListener { continueMonitoring(useCooldown = false) }
        btnSuccess.setOnClickListener { continueMonitoring(useCooldown = true) }

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

    override fun onDestroyView() {
        super.onDestroyView()
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
        if (usageState == UsageState.MONITORING) return

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

        setUsageState(UsageState.MONITORING)
        rustlingDetectionCount = 0
        requiredRustlingDetectionsPerMinute =
            AppSettings.getAlarmRustlingDetectionsPerMinute(requireContext())
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

    private fun triggerAlarm() {
        stopListening()
        setUsageState(UsageState.ALARMING)
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, ALARM_VOLUME_PERCENT)
        mainHandler.removeCallbacks(alarmTick)
        alarmTick.run()
    }

    private fun turnOffAlarm() {
        UsageMonitoringService.turnOffAlarm(requireContext())
        setUsageState(UsageState.POST_ALARM)
    }

    private fun continueMonitoring(useCooldown: Boolean) {
        UsageMonitoringService.continueMonitoring(requireContext(), useCooldown)
        setUsageState(UsageState.MONITORING)
    }

    private fun stopAlarm() {
        mainHandler.removeCallbacks(alarmTick)
        toneGenerator?.release()
        toneGenerator = null
    }

    private fun setUsageState(state: UsageState) {
        usageState = state
        tvUsageStatusCircle.text = when (state) {
            UsageState.IDLE -> getString(R.string.usageHello)
            UsageState.MONITORING -> getString(R.string.usageSleeping)
            UsageState.ALARMING -> getString(R.string.usageGoToToilet)
            UsageState.POST_ALARM -> getString(R.string.usageContinue)
        }

        btnUsageMain.text = when (state) {
            UsageState.IDLE -> getString(R.string.startListening)
            UsageState.MONITORING -> getString(R.string.stopListening)
            UsageState.ALARMING -> getString(R.string.turnOffAlarm)
            UsageState.POST_ALARM -> getString(R.string.turnOffAlarm)
        }
        btnUsageMain.visibility = if (state == UsageState.POST_ALARM) View.GONE else View.VISIBLE
        layoutUsageFeedback.visibility = if (state == UsageState.POST_ALARM) View.VISIBLE else View.GONE
        layoutRustlingDetections.visibility =
            if (state == UsageState.MONITORING) View.VISIBLE else View.GONE
        if (::cbSaveSamples.isInitialized) {
            cbSaveSamples.isEnabled = state == UsageState.IDLE || state == UsageState.POST_ALARM
        }
    }

    private fun updateRustlingDetectionIndicator() {
        if (!::layoutRustlingDetections.isInitialized) return

        val requiredCount = requiredRustlingDetectionsPerMinute.coerceAtLeast(1)
        val activeCount = rustlingDetectionCount.coerceIn(0, requiredCount)
        if (displayedRustlingDetectionSlots != requiredCount) {
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
        displayedRustlingDetectionSlots = requiredCount

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
            R.string.nextAlarmAllowed,
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
        val filter = IntentFilter(UsageMonitoringService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                usageStatusReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            requireContext().registerReceiver(usageStatusReceiver, filter)
        }
    }

    private fun unregisterUsageReceiver() {
        runCatching { requireContext().unregisterReceiver(usageStatusReceiver) }
    }

    private fun syncUsageStateFromService() {
        cooldownUntilWallClockMillis = UsageMonitoringService.cooldownUntilWallClockSnapshot
        rustlingDetectionCount = UsageMonitoringService.rustlingDetectionCountSnapshot
        requiredRustlingDetectionsPerMinute =
            UsageMonitoringService.requiredRustlingDetectionsPerMinuteSnapshot
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
            UsageMonitoringService.STATE_POST_ALARM -> UsageState.POST_ALARM
            else -> UsageState.IDLE
        }
    }

    private fun canTriggerAlarm(): Boolean {
        return SystemClock.elapsedRealtime() >= cooldownUntilElapsedRealtime
    }

    private fun isRustling(model: LogisticRegressionModel, pcm16MonoSamples: ShortArray): Boolean {
        val floatSamples = FloatArray(pcm16MonoSamples.size) { index ->
            pcm16MonoSamples[index].toFloat() / Short.MAX_VALUE.toFloat()
        }
        val features = mfccExtractor.extractFeatures(
            WavAudio(
                sampleRate = SAMPLE_RATE,
                samples = floatSamples
            )
        )
        return model.predictProbability(features) >= CLASSIFICATION_THRESHOLD
    }

    private fun loadModel(): LogisticRegressionModel? {
        return runCatching {
            LogisticRegressionModel.fromJson(JSONObject(modelFile().readText()))
        }.getOrNull()
    }

    private fun modelFile(): File {
        return AppStorage.modelFile(requireContext())
    }

    private enum class UsageState {
        IDLE,
        MONITORING,
        ALARMING,
        POST_ALARM
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val FRAGMENT_SECONDS = 5
        const val FRAGMENT_SIZE_SAMPLES = SAMPLE_RATE * FRAGMENT_SECONDS
        const val CLASSIFICATION_THRESHOLD = 0.5
        const val ALARM_VOLUME_PERCENT = 100
        const val ALARM_TONE_MS = 450
        const val ALARM_REPEAT_MS = 1000L
        const val HOUR_MS = 60L * 60L * 1000L
    }
}
