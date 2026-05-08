package com.stanislavlyalin.stopenuresis.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.stanislavlyalin.stopenuresis.AppSettings
import com.stanislavlyalin.stopenuresis.MainActivity
import com.stanislavlyalin.stopenuresis.R
import com.stanislavlyalin.stopenuresis.audio.ManualWavFileWriter
import com.stanislavlyalin.stopenuresis.audio.SampleFileBuilder
import com.stanislavlyalin.stopenuresis.audio.StreamingMovingAverageSmoother
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DataCollectionRecordingService : Service() {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val isRecording = AtomicBoolean(false)
    private var silenceFilesCount = 0
    private var rustlingFilesCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(
                threshold = intent.getFloatExtra(
                    EXTRA_THRESHOLD,
                    AppSettings.DEFAULT_VOLUME_THRESHOLD.toFloat()
                )
            )
            ACTION_STOP -> stopRecording(stopService = true)
            ACTION_REQUEST_STATUS -> broadcastStatus()
            ACTION_ADJUST_REMOVED_RUSTLING -> adjustRemovedRustling()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording(stopService = false)
        super.onDestroy()
    }

    private fun startRecording(threshold: Float) {
        if (isRecording.get()) {
            broadcastStatus()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            stopRecording(stopService = true)
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
            stopRecording(stopService = true)
            return
        } catch (_: SecurityException) {
            stopRecording(stopService = true)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            stopRecording(stopService = true)
            return
        }

        val samplesDir = File(filesDir, "samples")
        val sampleFileBuilder = SampleFileBuilder(
            fragmentSizeSamples = SAMPLE_RATE * FRAGMENT_SECONDS,
            sampleRate = SAMPLE_RATE,
            channelCount = CHANNEL_COUNT,
            bitsPerSample = BITS_PER_SAMPLE,
            samplesDir = samplesDir,
            wavFileWriter = ManualWavFileWriter(),
            onSampleFileWritten = { file -> onSampleFileWritten(file) }
        )
        sampleFileBuilder.setThreshold(threshold)
        sampleFileBuilder.setThresholdExceedancePercent(
            AppSettings.getRustlingThresholdExceedancePercent(this)
        )

        val smoother = StreamingMovingAverageSmoother(
            windowSeconds = 1f,
            sampleRate = SAMPLE_RATE
        )

        audioRecord = record
        silenceFilesCount = 0
        rustlingFilesCount = 0
        isRecording.set(true)
        broadcastStatus()

        record.startRecording()

        recordingThread = thread(start = true, name = "DataCollectionRecordingServiceThread") {
            val buffer = ShortArray(bufferSize / 2)
            val smoothedBuffer = FloatArray(buffer.size)
            var chartAccumulator = 0f
            var chartAccumulatorCount = 0

            while (isRecording.get()) {
                val readCount = record.read(buffer, 0, buffer.size)
                if (readCount <= 0) continue

                smoother.smooth(buffer, readCount, smoothedBuffer)
                sampleFileBuilder.addSamples(buffer, smoothedBuffer, readCount)

                val chartPoints = ArrayList<Float>()
                for (i in 0 until readCount) {
                    chartAccumulator += smoothedBuffer[i]
                    chartAccumulatorCount++

                    if (chartAccumulatorCount >= CHART_SAMPLES_PER_POINT) {
                        chartPoints.add(chartAccumulator / chartAccumulatorCount.toFloat())
                        chartAccumulator = 0f
                        chartAccumulatorCount = 0
                    }
                }

                if (chartPoints.isNotEmpty()) {
                    sendBroadcast(Intent(ACTION_CHART_POINTS).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_CHART_POINTS, chartPoints.toFloatArray())
                    })
                }
            }
        }
    }

    private fun stopRecording(stopService: Boolean) {
        if (!isRecording.getAndSet(false)) {
            if (stopService) stopSelf()
            return
        }

        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }

        audioRecord?.release()
        audioRecord = null
        recordingThread?.join(300)
        recordingThread = null
        releaseWakeLock()
        silenceFilesCount = 0
        rustlingFilesCount = 0
        broadcastStatus()

        if (stopService) stopSelf()
    }

    private fun onSampleFileWritten(file: File) {
        if (!isRecording.get()) {
            if (file.name.endsWith("_1_unchecked.wav")) broadcastStatus()
            return
        }

        when {
            file.name.endsWith("_0.wav") -> silenceFilesCount++
            file.name.endsWith("_1_unchecked.wav") -> rustlingFilesCount++
        }
        updateNotification()
        broadcastStatus()
    }

    private fun adjustRemovedRustling() {
        if (!isRecording.get()) return
        rustlingFilesCount = (rustlingFilesCount - 1).coerceAtLeast(0)
        silenceFilesCount++
        updateNotification()
        broadcastStatus()
    }

    private fun broadcastStatus() {
        isRecordingNow = isRecording.get()
        silenceCountSnapshot = silenceFilesCount
        rustlingCountSnapshot = rustlingFilesCount
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RECORDING, isRecording.get())
            putExtra(EXTRA_SILENCE_COUNT, silenceFilesCount)
            putExtra(EXTRA_RUSTLING_COUNT, rustlingFilesCount)
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
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.dataCollectionNotificationTitle))
            .setContentText(
                getString(R.string.dataCollectionNotificationText, silenceFilesCount, rustlingFilesCount)
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.dataCollectionNotificationChannel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:DataCollectionRecording"
        ).apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val ACTION_STATUS = "com.stanislavlyalin.stopenuresis.DATA_COLLECTION_STATUS"
        const val ACTION_CHART_POINTS = "com.stanislavlyalin.stopenuresis.DATA_COLLECTION_CHART_POINTS"
        const val EXTRA_IS_RECORDING = "isRecording"
        const val EXTRA_SILENCE_COUNT = "silenceCount"
        const val EXTRA_RUSTLING_COUNT = "rustlingCount"
        const val EXTRA_CHART_POINTS = "chartPoints"
        @Volatile
        var isRecordingNow = false
            private set
        @Volatile
        var silenceCountSnapshot = 0
            private set
        @Volatile
        var rustlingCountSnapshot = 0
            private set

        private const val ACTION_START = "com.stanislavlyalin.stopenuresis.action.START_DATA_COLLECTION"
        private const val ACTION_STOP = "com.stanislavlyalin.stopenuresis.action.STOP_DATA_COLLECTION"
        private const val ACTION_REQUEST_STATUS =
            "com.stanislavlyalin.stopenuresis.action.REQUEST_DATA_COLLECTION_STATUS"
        private const val ACTION_ADJUST_REMOVED_RUSTLING =
            "com.stanislavlyalin.stopenuresis.action.ADJUST_REMOVED_RUSTLING"
        private const val EXTRA_THRESHOLD = "threshold"
        private const val CHANNEL_ID = "data_collection_recording"
        private const val NOTIFICATION_ID = 1001

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_COUNT = 1
        private const val BITS_PER_SAMPLE = 16
        private const val FRAGMENT_SECONDS = 5
        private const val CHART_TARGET_POINTS_PER_SECOND = 10
        private const val CHART_SAMPLES_PER_POINT = SAMPLE_RATE / CHART_TARGET_POINTS_PER_SECOND

        fun start(context: Context, threshold: Float) {
            val intent = Intent(context, DataCollectionRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_THRESHOLD, threshold)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DataCollectionRecordingService::class.java).apply {
                action = ACTION_STOP
            })
        }

        fun requestStatus(context: Context) {
            context.startService(Intent(context, DataCollectionRecordingService::class.java).apply {
                action = ACTION_REQUEST_STATUS
            })
        }

        fun adjustRemovedRustling(context: Context) {
            context.startService(Intent(context, DataCollectionRecordingService::class.java).apply {
                action = ACTION_ADJUST_REMOVED_RUSTLING
            })
        }
    }
}
