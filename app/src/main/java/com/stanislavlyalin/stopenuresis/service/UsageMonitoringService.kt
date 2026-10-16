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

    private val isListening = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val acousticFeatureExtractor = AcousticFeatureExtractor()

    private var usageState = STATE_IDLE
    private var audioRecord: AudioRecord? = null
    private var listeningThread: Thread? = null
    private var toneGenerator: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var confirmation = RustlingConfirmation(
        AppSettings.DEFAULT_ALARM_RUSTLING_EPISODES,
        AppSettings.DEFAULT_CONFIRMATION_PERIOD_MINUTES * MINUTE_MS
    )
    private var requiredRustlingEpisodes = AppSettings.DEFAULT_ALARM_RUSTLING_EPISODES
    @Volatile
    private var cooldownUntilElapsedRealtime = 0L
    private var cooldownUntilWallClockMillis = 0L
    private var sampleSavingAllowedAfterElapsedRealtime = 0L

    private val detectionStatusTick = object : Runnable {
        override fun run() {
            if (usageState != STATE_MONITORING) return
            val nowElapsedRealtime = SystemClock.elapsedRealtime()
            confirmation.advance(nowElapsedRealtime)
            clearExpiredCooldown(nowElapsedRealtime)
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
            ACTION_START -> startListening(applyStartProtection = true)
            ACTION_STOP -> stopListening(stopService = true)
            ACTION_TURN_OFF_ALARM -> turnOffAlarm()
            ACTION_CONTINUE_MONITORING -> continueMonitoring(
                useCooldown = intent.getBooleanExtra(EXTRA_USE_COOLDOWN, false)
            )
            ACTION_REQUEST_STATUS -> broadcastStatus()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening(stopService = false)
        stopAlarm()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startListening(applyStartProtection: Boolean) {
        if (usageState == STATE_MONITORING) {
            broadcastStatus()
            return
        }

        val model = loadModel()
        if (model == null) {
            usageState = STATE_IDLE
            broadcastStatus()
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            stopListening(stopService = true)
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
        } catch (_: RuntimeException) {
            stopListening(stopService = true)
            return
        } catch (_: SecurityException) {
            stopListening(stopService = true)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            stopListening(stopService = true)
            return
        }

        val volumeThreshold = AppSettings.getVolumeThreshold(this).toFloat()
        val saveSamples = AppSettings.isUsageSampleSavingEnabled(this)
        requiredRustlingEpisodes = AppSettings.getAlarmRustlingEpisodes(this)
        confirmation = RustlingConfirmation(
            requiredRustlingEpisodes,
            AppSettings.getConfirmationPeriodMinutes(this) * MINUTE_MS
        )
        if (applyStartProtection) {
            val protectionMillis = AppSettings.getListeningStartProtectionHours(this) * HOUR_MS
            if (protectionMillis > 0L) {
                cooldownUntilElapsedRealtime = SystemClock.elapsedRealtime() + protectionMillis
                cooldownUntilWallClockMillis = System.currentTimeMillis() + protectionMillis
                sampleSavingAllowedAfterElapsedRealtime = cooldownUntilElapsedRealtime
            } else {
                cooldownUntilElapsedRealtime = 0L
                cooldownUntilWallClockMillis = 0L
                sampleSavingAllowedAfterElapsedRealtime = 0L
            }
        }
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
        isListening.set(true)
        usageState = STATE_MONITORING
        broadcastStatus()
        updateNotification()
        mainHandler.removeCallbacks(detectionStatusTick)
        mainHandler.postDelayed(detectionStatusTick, DETECTION_STATUS_UPDATE_MS)

        record.startRecording()

        listeningThread = thread(start = true, name = "UsageMonitoringServiceThread") {
            val buffer = ShortArray(bufferSize / 2)
            val detectionWindows = DetectionWindowStream(FRAGMENT_SIZE_SAMPLES)
            var alarmTriggerPosted = false

            while (isListening.get()) {
                val readCount = record.read(buffer, 0, buffer.size)
                if (readCount <= 0) continue

                detectionWindows.append(buffer, readCount) { fragment ->
                    val now = SystemClock.elapsedRealtime()
                    val allowed = !alarmTriggerPosted && canTriggerAlarm()
                    val positive = allowed &&
                        AudioEnvelope.averageSmoothedAbsolute(fragment, SAMPLE_RATE) >= volumeThreshold &&
                        isRustling(model, fragment)
                    val result = confirmation.accept(positive, now, allowed)
                    if (result.newEpisode) {
                        if (now >= sampleSavingAllowedAfterElapsedRealtime) {
                            sampleFileBuilder?.saveDetectedWindow(fragment)
                        }
                        broadcastStatus()
                        if (result.alarm) {
                            alarmTriggerPosted = true
                            mainHandler.post {
                                if (usageState == STATE_MONITORING) triggerAlarm()
                            }
                        }
                    }
                    if (confirmation.isConfirming) HALF_FRAGMENT_SIZE_SAMPLES
                    else FRAGMENT_SIZE_SAMPLES
                }

                if (SystemClock.elapsedRealtime() >= sampleSavingAllowedAfterElapsedRealtime) {
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

    private fun stopListening(stopService: Boolean) {
        if (!isListening.getAndSet(false)) {
            if (usageState == STATE_MONITORING) usageState = STATE_IDLE
            broadcastStatus()
            if (stopService) stopSelf()
            return
        }

        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }

        audioRecord?.release()
        audioRecord = null
        listeningThread?.join(300)
        listeningThread = null
        mainHandler.removeCallbacks(detectionStatusTick)

        if (usageState == STATE_MONITORING) {
            cooldownUntilWallClockMillis = 0L
            usageState = STATE_IDLE
        }
        confirmation.reset()
        broadcastStatus()
        updateNotification()

        if (stopService) {
            releaseWakeLock()
            stopSelf()
        }
    }

    private fun triggerAlarm() {
        stopListening(stopService = false)
        usageState = STATE_ALARMING
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, ALARM_VOLUME_PERCENT)
        mainHandler.removeCallbacks(alarmTick)
        alarmTick.run()
        broadcastStatus()
        updateNotification()
    }

    private fun turnOffAlarm() {
        stopAlarm()
        usageState = STATE_POST_ALARM
        broadcastStatus()
        updateNotification()
    }

    private fun continueMonitoring(useCooldown: Boolean) {
        if (useCooldown) {
            val cooldownMillis = AppSettings.getCooldownHours(this) * HOUR_MS
            cooldownUntilElapsedRealtime = SystemClock.elapsedRealtime() + cooldownMillis
            cooldownUntilWallClockMillis = System.currentTimeMillis() + cooldownMillis
        }
        startListening(applyStartProtection = false)
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
    }

    private fun buildNotification(): Notification {
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
            STATE_POST_ALARM -> getString(R.string.usagePostAlarmNotificationText)
            else -> getString(R.string.usageMonitoringNotificationText)
        }
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (usageState == STATE_ALARMING) {
                    android.R.drawable.ic_dialog_alert
                } else {
                    android.R.drawable.ic_media_play
                }
            )
            .setContentTitle(getString(R.string.usageNotificationTitle))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(usageState != STATE_ALARMING)
            .setOnlyAlertOnce(usageState != STATE_ALARMING)
            .setPriority(
                if (usageState == STATE_ALARMING) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_LOW
                }
            )
        if (usageState == STATE_ALARMING) {
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

    private fun canTriggerAlarm(): Boolean {
        return SystemClock.elapsedRealtime() >= cooldownUntilElapsedRealtime
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

    private fun clearExpiredCooldown(nowElapsedRealtime: Long) {
        if (cooldownUntilElapsedRealtime in 1..nowElapsedRealtime) {
            cooldownUntilElapsedRealtime = 0L
            cooldownUntilWallClockMillis = 0L
        }
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
        const val STATE_IDLE = "idle"
        const val STATE_MONITORING = "monitoring"
        const val STATE_ALARMING = "alarming"
        const val STATE_POST_ALARM = "post_alarm"
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
        private const val ACTION_CONTINUE_MONITORING =
            "com.stanislavlyalin.stopenuresis.action.CONTINUE_USAGE_MONITORING"
        private const val ACTION_REQUEST_STATUS =
            "com.stanislavlyalin.stopenuresis.action.REQUEST_USAGE_STATUS"
        private const val EXTRA_USE_COOLDOWN = "useCooldown"
        private const val CHANNEL_ID = "usage_monitoring"
        private const val NOTIFICATION_ID = 1002

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
        private const val HOUR_MS = 60L * 60L * 1000L

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

        fun continueMonitoring(context: Context, useCooldown: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UsageMonitoringService::class.java).apply {
                    action = ACTION_CONTINUE_MONITORING
                    putExtra(EXTRA_USE_COOLDOWN, useCooldown)
                }
            )
        }

        fun requestStatus(context: Context) {
            context.startService(Intent(context, UsageMonitoringService::class.java).apply {
                action = ACTION_REQUEST_STATUS
            })
        }
    }
}
