package com.stanislavlyalin.stopenuresis.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.stanislavlyalin.stopenuresis.AppSettings
import com.stanislavlyalin.stopenuresis.AppStorage
import com.stanislavlyalin.stopenuresis.MainActivity
import com.stanislavlyalin.stopenuresis.R
import com.stanislavlyalin.stopenuresis.ui.AlarmActivity
import com.stanislavlyalin.stopenuresis.audio.AudioEnvelope
import com.stanislavlyalin.stopenuresis.audio.DetectionWindowStream
import com.stanislavlyalin.stopenuresis.audio.ManualWavFileWriter
import com.stanislavlyalin.stopenuresis.audio.SampleFileBuilder
import com.stanislavlyalin.stopenuresis.training.ExtraTreesModel
import com.stanislavlyalin.stopenuresis.training.AcousticFeatureExtractor
import com.stanislavlyalin.stopenuresis.training.WavAudio
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class UsageMonitoringService : Service() {

    private var isListening = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val acousticFeatureExtractor = AcousticFeatureExtractor()

    private val cycle = MonitoringCycle()
    private val usageState get() = cycle.state
    private var audioRecord: AudioRecord? = null
    private var listeningThread: Thread? = null
    private var reviewAlarmRecorded = false
    private var toneGenerator: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var confirmation = RustlingConfirmation(
        AppSettings.DEFAULT_ALARM_RUSTLING_EPISODES,
        AppSettings.DEFAULT_CONFIRMATION_PERIOD_MINUTES * MINUTE_MS
    )
    private var requiredRustlingEpisodes = AppSettings.DEFAULT_ALARM_RUSTLING_EPISODES
    private var cooldownUntilWallClockMillis = 0L

    private val resumeListening = object : Runnable {
        override fun run() {
            val remaining = cycle.resumeAt - SystemClock.elapsedRealtime()
            if (usageState != STATE_PAUSED) return
            if (remaining > 0L) {
                mainHandler.postDelayed(this, remaining)
            } else if (cycle.resume(SystemClock.elapsedRealtime())) {
                cooldownUntilWallClockMillis = 0L
                startListening()
            }
        }
    }

    private val detectionStatusTick = object : Runnable {
        override fun run() {
            if (usageState != STATE_MONITORING) return
            val nowElapsedRealtime = SystemClock.elapsedRealtime()
            confirmation.advance(nowElapsedRealtime)
            broadcastStatus()
            mainHandler.postDelayed(this, DETECTION_STATUS_UPDATE_MS)
        }
    }

    private val alarmTick = object : Runnable {
        override fun run() {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, ALARM_TONE_MS)
            mainHandler.postDelayed(this, ALARM_REPEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (cycle.start(SystemClock.elapsedRealtime(), protectionMillis())) {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    acquireWakeLock()
                    schedulePause()
                }
            }
            ACTION_STOP -> stopMonitoring()
            ACTION_TURN_OFF_ALARM -> turnOffAlarm()
            ACTION_REQUEST_STATUS -> broadcastStatus()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(resumeListening)
        stopListening()
        stopAlarm()
        cycle.stop()
        cooldownUntilWallClockMillis = 0L
        broadcastStatus()
        getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIFICATION_ID)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startListening() {
        val model = loadModel()
        if (model == null) {
            failListening(R.string.modelFileNotFound)
            return
        }

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            failListening(R.string.audioRecordingSettingsError)
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
        } catch (_: SecurityException) {
            failListening(R.string.noAccessToMicrophone)
            return
        } catch (_: RuntimeException) {
            failListening(R.string.failedToInitMicrophone)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            failListening(R.string.audioRecordNotInitialized)
            return
        }

        val volumeThreshold = AppSettings.getVolumeThreshold(this).toFloat()
        val saveSamples = AppSettings.isUsageSampleSavingEnabled(this)
        requiredRustlingEpisodes = AppSettings.getAlarmRustlingEpisodes(this)
        confirmation = RustlingConfirmation(
            requiredRustlingEpisodes,
            AppSettings.getConfirmationPeriodMinutes(this) * MINUTE_MS
        )
        AppStorage.migrateLegacyData(this)
        val sampleFileBuilder = if (saveSamples) {
            SampleFileBuilder(
                fragmentSizeSamples = FRAGMENT_SIZE_SAMPLES,
                sampleRate = SAMPLE_RATE,
                channelCount = CHANNEL_COUNT,
                bitsPerSample = BITS_PER_SAMPLE,
                samplesDir = AppStorage.samplesDir(this),
                wavFileWriter = ManualWavFileWriter(),
                saveSilenceSamples = false,
                onSampleFileWritten = { onTrainingSampleFileWritten() }
            ).apply {
                setThreshold(volumeThreshold)
            }
        } else {
            null
        }

        audioRecord = record
        try {
            record.startRecording()
        } catch (_: RuntimeException) {
            failListening(R.string.failedToInitMicrophone)
            return
        }
        // Отдельный флаг не позволяет старому потоку возобновиться вместе с новым сеансом.
        val recordingActive = AtomicBoolean(true)
        isListening = recordingActive
        broadcastStatus()
        updateNotification()
        mainHandler.removeCallbacks(detectionStatusTick)
        mainHandler.postDelayed(detectionStatusTick, DETECTION_STATUS_UPDATE_MS)

        listeningThread = thread(start = true, name = "UsageMonitoringServiceThread") {
            val buffer = ShortArray(bufferSize / 2)
            val detectionWindows = DetectionWindowStream(FRAGMENT_SIZE_SAMPLES)
            var alarmTriggerPosted = false

            while (recordingActive.get()) {
                val readCount = record.read(buffer, 0, buffer.size)
                if (readCount <= 0) continue

                detectionWindows.append(buffer, readCount) { fragment ->
                    if (!recordingActive.get()) return@append FRAGMENT_SIZE_SAMPLES
                    val now = SystemClock.elapsedRealtime()
                    val allowed = !alarmTriggerPosted
                    val positive = allowed &&
                        AudioEnvelope.averageSmoothedAbsolute(fragment, SAMPLE_RATE) >= volumeThreshold &&
                        isRustling(model, fragment)
                    if (!recordingActive.get()) return@append FRAGMENT_SIZE_SAMPLES
                    val result = confirmation.accept(positive, now, allowed)
                    if (result.newEpisode) {
                        sampleFileBuilder?.saveDetectedWindow(fragment)
                        mainHandler.post {
                            if (recordingActive.get()) broadcastStatus()
                        }
                        if (result.alarm) {
                            alarmTriggerPosted = true
                            mainHandler.post {
                                if (recordingActive.get() && usageState == STATE_MONITORING) triggerAlarm(model.accuracy)
                            }
                        }
                    }
                    if (confirmation.isConfirming) HALF_FRAGMENT_SIZE_SAMPLES
                    else FRAGMENT_SIZE_SAMPLES
                }

                if (recordingActive.get()) {
                    sampleFileBuilder?.addSamples(buffer, readCount)
                }
            }
        }
    }

    private fun onTrainingSampleFileWritten() {
        sendBroadcast(Intent(DataCollectionRecordingService.ACTION_SAMPLES_CHANGED).apply {
            setPackage(packageName)
        })
    }

    private fun stopListening() {
        isListening.set(false)
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }

        listeningThread?.join(300)
        listeningThread = null
        audioRecord?.release()
        audioRecord = null
        mainHandler.removeCallbacks(detectionStatusTick)

        confirmation.reset()
    }

    private fun triggerAlarm(accuracy: Double) {
        if (!cycle.alarm()) return
        reviewAlarmRecorded = com.stanislavlyalin.stopenuresis.review.ReviewHistory(this).alarmStarted(System.currentTimeMillis(), accuracy)
        stopListening()
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, ALARM_VOLUME_PERCENT)
        mainHandler.removeCallbacks(alarmTick)
        alarmTick.run()
        broadcastStatus()
        updateNotification()
    }

    private fun turnOffAlarm() {
        if (!cycle.dismiss(SystemClock.elapsedRealtime(), protectionMillis())) return
        if (reviewAlarmRecorded) com.stanislavlyalin.stopenuresis.review.ReviewHistory(this).alarmDismissed()
        reviewAlarmRecorded = false
        stopAlarm()
        schedulePause()
    }

    private fun protectionMillis() = AppSettings.getProtectionIntervalMinutes(this) * MINUTE_MS

    private fun schedulePause() {
        val remaining = cycle.resumeAt - SystemClock.elapsedRealtime()
        cooldownUntilWallClockMillis = System.currentTimeMillis() + remaining
        mainHandler.removeCallbacks(resumeListening)
        mainHandler.postDelayed(resumeListening, remaining.coerceAtLeast(0L))
        broadcastStatus()
        updateNotification()
    }

    private fun stopMonitoring() {
        mainHandler.removeCallbacks(resumeListening)
        stopListening()
        stopAlarm()
        cycle.stop()
        cooldownUntilWallClockMillis = 0L
        broadcastStatus()
        releaseWakeLock()
        getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failListening(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        stopMonitoring()
    }

    private fun stopAlarm() {
        mainHandler.removeCallbacks(alarmTick)
        toneGenerator?.release()
        toneGenerator = null
    }

    private fun broadcastStatus() {
        stateSnapshot = usageState
        cooldownUntilWallClockSnapshot = cooldownUntilWallClockMillis
        rustlingEpisodeCountSnapshot = confirmation.episodeCount
        requiredRustlingEpisodesSnapshot = requiredRustlingEpisodes
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, usageState)
            putExtra(EXTRA_COOLDOWN_UNTIL_WALL_CLOCK, cooldownUntilWallClockMillis)
            putExtra(EXTRA_RUSTLING_EPISODE_COUNT, rustlingEpisodeCountSnapshot)
            putExtra(
                EXTRA_REQUIRED_RUSTLING_EPISODES,
                requiredRustlingEpisodes
            )
        })
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
        // Полноэкранное действие запускается новым уведомлением, а не обновлением статуса сервиса.
        if (usageState == STATE_ALARMING) {
            manager.notify(ALARM_NOTIFICATION_ID, buildNotification(alarming = true))
        } else {
            manager.cancel(ALARM_NOTIFICATION_ID)
        }
    }

    private fun buildNotification(alarming: Boolean = false): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when (usageState) {
            STATE_MONITORING -> getString(R.string.usageMonitoringNotificationText)
            STATE_ALARMING -> getString(R.string.usageAlarmNotificationText)
            STATE_PAUSED -> getString(R.string.usagePauseUntil, SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(cooldownUntilWallClockMillis)))
            else -> getString(R.string.usageMonitoringNotificationText)
        }
        val notificationBuilder = NotificationCompat.Builder(this, if (alarming) CHANNEL_ID else STATUS_CHANNEL_ID)
            .setSmallIcon(
                if (alarming) {
                    android.R.drawable.ic_dialog_alert
                } else {
                    android.R.drawable.ic_media_play
                }
            )
            .setContentTitle(getString(R.string.usageNotificationTitle))
            .setContentText(text)
            .setContentIntent(if (alarming) alarmFullScreenPendingIntent() else pendingIntent)
            .setCategory(if (alarming) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(!alarming)
            .setOnlyAlertOnce(!alarming)
            .setPriority(
                if (alarming) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_LOW
                }
            )
        if (alarming) {
            notificationBuilder
                .setFullScreenIntent(alarmFullScreenPendingIntent(), true)
                .setDeleteIntent(alarmStopPendingIntent())
                .addAction(
                    R.drawable.ic_stop_24,
                    getString(R.string.turnOffAlarm),
                    alarmStopPendingIntent()
                )
        }
        return notificationBuilder.build()
    }

    private fun alarmFullScreenPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            2,
            Intent(this, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun alarmStopPendingIntent(): PendingIntent {
        return PendingIntent.getService(
            this,
            3,
            Intent(this, UsageMonitoringService::class.java).apply {
                action = ACTION_TURN_OFF_ALARM
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.usageNotificationChannel),
            NotificationManager.IMPORTANCE_HIGH
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(
            STATUS_CHANNEL_ID, getString(R.string.usageMonitoringChannel), NotificationManager.IMPORTANCE_LOW
        ))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:UsageMonitoring"
        ).apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun isRustling(model: ExtraTreesModel, pcm16MonoSamples: ShortArray): Boolean {
        val floatSamples = FloatArray(pcm16MonoSamples.size) { index ->
            pcm16MonoSamples[index].toFloat() / Short.MAX_VALUE.toFloat()
        }
        val features = acousticFeatureExtractor.extractFeatures(
            WavAudio(
                sampleRate = SAMPLE_RATE,
                samples = floatSamples
            )
        )
        return model.predictProbability(features) >= CLASSIFICATION_THRESHOLD
    }

    private fun loadModel(): ExtraTreesModel? {
        return runCatching {
            ExtraTreesModel.fromJson(JSONObject(modelFile().readText()))
        }.getOrNull()
    }

    private fun modelFile(): File {
        return AppStorage.modelFile(this)
    }

    companion object {
        const val ACTION_STATUS = "com.stanislavlyalin.stopenuresis.USAGE_STATUS"
        const val EXTRA_STATE = "state"
        const val EXTRA_COOLDOWN_UNTIL_WALL_CLOCK = "cooldownUntilWallClock"
        const val EXTRA_RUSTLING_EPISODE_COUNT = "rustlingEpisodeCount"
        const val EXTRA_REQUIRED_RUSTLING_EPISODES = "requiredRustlingEpisodes"
        const val STATE_IDLE = MonitoringCycle.IDLE
        const val STATE_PAUSED = MonitoringCycle.PAUSED
        const val STATE_MONITORING = MonitoringCycle.MONITORING
        const val STATE_ALARMING = MonitoringCycle.ALARMING
        @Volatile
        var stateSnapshot = STATE_IDLE
            private set
        @Volatile
        var cooldownUntilWallClockSnapshot = 0L
            private set
        @Volatile
        var rustlingEpisodeCountSnapshot = 0
            private set
        @Volatile
        var requiredRustlingEpisodesSnapshot = AppSettings.DEFAULT_ALARM_RUSTLING_EPISODES
            private set

        private const val ACTION_START = "com.stanislavlyalin.stopenuresis.action.START_USAGE_MONITORING"
        private const val ACTION_STOP = "com.stanislavlyalin.stopenuresis.action.STOP_USAGE_MONITORING"
        private const val ACTION_TURN_OFF_ALARM = "com.stanislavlyalin.stopenuresis.action.TURN_OFF_ALARM"
        private const val ACTION_REQUEST_STATUS =
            "com.stanislavlyalin.stopenuresis.action.REQUEST_USAGE_STATUS"
        private const val CHANNEL_ID = "usage_monitoring"
        private const val STATUS_CHANNEL_ID = "usage_monitoring_status"
        private const val NOTIFICATION_ID = 1002
        private const val ALARM_NOTIFICATION_ID = 1003

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_COUNT = 1
        private const val BITS_PER_SAMPLE = 16
        private const val FRAGMENT_SECONDS = 5
        private const val FRAGMENT_SIZE_SAMPLES = SAMPLE_RATE * FRAGMENT_SECONDS
        private const val HALF_FRAGMENT_SIZE_SAMPLES = FRAGMENT_SIZE_SAMPLES / 2
        private const val CLASSIFICATION_THRESHOLD = 0.5
        private const val ALARM_VOLUME_PERCENT = 100
        private const val ALARM_TONE_MS = 450
        private const val ALARM_REPEAT_MS = 1000L
        private const val DETECTION_STATUS_UPDATE_MS = 1000L
        private const val MINUTE_MS = 60L * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UsageMonitoringService::class.java).apply { action = ACTION_START }
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, UsageMonitoringService::class.java).apply {
                action = ACTION_STOP
            })
        }

        fun turnOffAlarm(context: Context) {
            context.startService(Intent(context, UsageMonitoringService::class.java).apply {
                action = ACTION_TURN_OFF_ALARM
            })
        }

        fun requestStatus(context: Context) {
            context.startService(Intent(context, UsageMonitoringService::class.java).apply {
                action = ACTION_REQUEST_STATUS
            })
        }
    }
}
