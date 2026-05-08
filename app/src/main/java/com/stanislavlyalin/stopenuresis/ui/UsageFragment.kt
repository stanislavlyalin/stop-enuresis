package com.stanislavlyalin.stopenuresis.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
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

    override fun onStop() {
        super.onStop()
        stopListening()
        stopAlarm()
        clearCooldownMessage()
    }

    override fun onDestroyView() {
        stopListening()
        stopAlarm()
        clearCooldownMessage()
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

        val model = loadModel()
        if (model == null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.modelFileNotFound),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Toast.makeText(requireContext(), getString(R.string.failedToInitMicrophone), Toast.LENGTH_SHORT).show()
            return
        }

        val bufferSize = minBufferSize * 2
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (e: SecurityException) {
            Toast.makeText(requireContext(), getString(R.string.noAccessToMicrophone), Toast.LENGTH_SHORT).show()
            return
        } catch (e: IllegalArgumentException) {
            Toast.makeText(requireContext(), getString(R.string.audioRecordingSettingsError), Toast.LENGTH_SHORT).show()
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            Toast.makeText(requireContext(), getString(R.string.audioRecordNotInitialized), Toast.LENGTH_SHORT).show()
            return
        }

        val smoother = StreamingMovingAverageSmoother(
            windowSeconds = 1f,
            sampleRate = SAMPLE_RATE
        )
        val volumeThreshold = AppSettings.getVolumeThreshold(requireContext()).toFloat()
        val requiredExceedancePercent =
            AppSettings.getRustlingThresholdExceedancePercent(requireContext())

        audioRecord = record
        isListening.set(true)
        setUsageState(UsageState.MONITORING)
        smoother.reset()

        record.startRecording()

        listeningThread = thread(start = true, name = "UsageAudioRecordThread") {
            val buffer = ShortArray(bufferSize / 2)
            val smoothedBuffer = FloatArray(buffer.size)
            val fragmentBuffer = ShortArray(FRAGMENT_SIZE_SAMPLES)
            var fragmentBufferSize = 0
            var aboveThresholdSamples = 0

            while (isListening.get()) {
                val readCount = record.read(buffer, 0, buffer.size)
                if (readCount <= 0) continue

                smoother.smooth(buffer, readCount, smoothedBuffer)

                for (i in 0 until readCount) {
                    fragmentBuffer[fragmentBufferSize] = buffer[i]
                    if (smoothedBuffer[i] >= volumeThreshold) aboveThresholdSamples++
                    fragmentBufferSize++

                    if (fragmentBufferSize == FRAGMENT_SIZE_SAMPLES) {
                        val shouldClassify = isThresholdExceeded(
                            aboveThresholdSamples,
                            requiredExceedancePercent
                        )
                        val fragment = fragmentBuffer.copyOf()
                        fragmentBufferSize = 0
                        aboveThresholdSamples = 0

                        if (shouldClassify && canTriggerAlarm() && isRustling(model, fragment)) {
                            activity?.runOnUiThread {
                                if (
                                    isAdded &&
                                    this@UsageFragment.view != null &&
                                    usageState == UsageState.MONITORING
                                ) {
                                    clearCooldownMessage()
                                    triggerAlarm()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopListening() {
        if (!isListening.get() && usageState != UsageState.MONITORING) {
            if (usageState == UsageState.MONITORING) setUsageState(UsageState.IDLE)
            return
        }

        isListening.set(false)

        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }

        audioRecord?.release()
        audioRecord = null

        listeningThread?.join(300)
        listeningThread = null

        if (usageState == UsageState.MONITORING) {
            clearCooldownMessage()
            setUsageState(UsageState.IDLE)
        }
    }

    private fun triggerAlarm() {
        stopListening()
        setUsageState(UsageState.ALARMING)
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, ALARM_VOLUME_PERCENT)
        mainHandler.removeCallbacks(alarmTick)
        alarmTick.run()
    }

    private fun turnOffAlarm() {
        stopAlarm()
        setUsageState(UsageState.POST_ALARM)
    }

    private fun continueMonitoring(useCooldown: Boolean) {
        if (useCooldown) {
            val cooldownMillis = AppSettings.getCooldownHours(requireContext()) * HOUR_MS
            cooldownUntilElapsedRealtime = SystemClock.elapsedRealtime() + cooldownMillis
            cooldownUntilWallClockMillis = System.currentTimeMillis() + cooldownMillis
            showCooldownMessage()
        }
        ensurePermissionAndStart()
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
