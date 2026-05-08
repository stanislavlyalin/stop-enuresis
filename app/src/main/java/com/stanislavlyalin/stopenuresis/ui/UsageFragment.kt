package com.stanislavlyalin.stopenuresis.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.stanislavlyalin.stopenuresis.AppSettings
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
    private lateinit var tvNextAlarmAllowed: TextView
    private lateinit var btnUsageMain: Button
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

    private val usageStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsageMonitoringService.ACTION_STATUS) return
            cooldownUntilWallClockMillis = intent.getLongExtra(
                UsageMonitoringService.EXTRA_COOLDOWN_UNTIL_WALL_CLOCK,
                0L
            )
            setUsageState(stateFromService(intent.getStringExtra(UsageMonitoringService.EXTRA_STATE)))
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
        tvNextAlarmAllowed = view.findViewById(R.id.tvNextAlarmAllowed)
        btnUsageMain = view.findViewById(R.id.btnUsageMain)
        layoutUsageFeedback = view.findViewById(R.id.layoutUsageFeedback)
        btnFalseAlarm = view.findViewById(R.id.btnFalseAlarm)
        btnSuccess = view.findViewById(R.id.btnSuccess)

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

    private fun startListening() {
        if (usageState == UsageState.MONITORING) return

        if (loadModel() == null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.modelFileNotFound),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        setUsageState(UsageState.MONITORING)
        UsageMonitoringService.start(requireContext())
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
        setUsageState(stateFromService(UsageMonitoringService.stateSnapshot))
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

    private fun isThresholdExceeded(
        aboveThresholdSamples: Int,
        requiredPercent: Int
    ): Boolean {
        val exceedancePercent = aboveThresholdSamples * 100f / FRAGMENT_SIZE_SAMPLES.toFloat()
        return exceedancePercent >= requiredPercent.toFloat()
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
        return File(requireContext().filesDir, "model/stopenuresis_model.json")
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
