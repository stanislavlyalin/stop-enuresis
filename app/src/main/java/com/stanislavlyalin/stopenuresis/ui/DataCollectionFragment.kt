package com.stanislavlyalin.stopenuresis.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.stanislavlyalin.stopenuresis.AppSettings
import com.stanislavlyalin.stopenuresis.R
import com.stanislavlyalin.stopenuresis.audio.ManualWavFileWriter
import com.stanislavlyalin.stopenuresis.audio.SampleFileBuilder
import com.stanislavlyalin.stopenuresis.audio.StreamingMovingAverageSmoother
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DataCollectionFragment : Fragment(R.layout.fragment_data_collection) {

    private lateinit var btnRecordToggle: Button
    private lateinit var lineChartAmplitude: LineChart
    private lateinit var rvAudioFragments: RecyclerView
    private lateinit var seekBarThreshold: SeekBar
    private lateinit var sampleFileBuilder: SampleFileBuilder
    private lateinit var audioFragmentsAdapter: AudioFragmentsAdapter
    private lateinit var samplesDir: File

    private var recordingState: RecordingState = RecordingState.IDLE

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var mediaPlayer: MediaPlayer? = null
    private val isRecording = AtomicBoolean(false)

    private val chartEntries = mutableListOf<Entry>()
    private var chartX = 0f
    private val maxVisiblePoints = 60
    private val chartTargetPointsPerSecond = 10
    private val chartSamplesPerPoint = SAMPLE_RATE / chartTargetPointsPerSecond
    private var currentThreshold = DEFAULT_THRESHOLD.toFloat()

    private companion object {
        const val CHART_AXIS_MAX = 500f
        const val THRESHOLD_MAX = 500
        const val DEFAULT_THRESHOLD = 100

        const val SAMPLE_RATE = 16000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val FRAGMENT_SECONDS = 5
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.recordAudioPermissionNeeded),
                    Toast.LENGTH_SHORT
                ).show()
                setRecordingState(RecordingState.IDLE)
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnRecordToggle = view.findViewById(R.id.btnRecordToggle)
        lineChartAmplitude = view.findViewById(R.id.lineChartAmplitude)
        rvAudioFragments = view.findViewById(R.id.rvAudioFragments)
        seekBarThreshold = view.findViewById(R.id.seekBarThreshold)
        samplesDir = File(requireContext().filesDir, "samples")

        sampleFileBuilder = SampleFileBuilder(
            fragmentSizeSamples = SAMPLE_RATE * FRAGMENT_SECONDS,
            sampleRate = SAMPLE_RATE,
            channelCount = CHANNEL_COUNT,
            bitsPerSample = BITS_PER_SAMPLE,
            samplesDir = samplesDir,
            wavFileWriter = ManualWavFileWriter(),
            onSampleFileWritten = { file ->
                if (file.name.endsWith("_unchecked.wav")) {
                    activity?.runOnUiThread {
                        if (isAdded && this@DataCollectionFragment.view != null) {
                            refreshAudioFragmentsList()
                        }
                    }
                }
            }
        )
        sampleFileBuilder.setThreshold(currentThreshold)
        refreshRustlingThresholdExceedanceSetting()

        setupChart()
        setupThresholdSeekBar()
        setupAudioFragmentsList()
        setRecordingState(RecordingState.IDLE)
        refreshAudioFragmentsList()

        btnRecordToggle.setOnClickListener {
            when (recordingState) {
                RecordingState.IDLE -> ensurePermissionAndStart()
                RecordingState.RECORDING -> stopRecording()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        stopRecording()
        stopPlayback()
    }

    override fun onDestroyView() {
        stopRecording()
        stopPlayback()
        super.onDestroyView()
    }

    private fun setRecordingState(state: RecordingState) {
        recordingState = state
        btnRecordToggle.text = when (state) {
            RecordingState.IDLE -> getString(R.string.startRecording)
            RecordingState.RECORDING -> getString(R.string.stopRecording)
        }
    }

    private fun ensurePermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startRecording()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        if (recordingState == RecordingState.RECORDING) return

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Toast.makeText(requireContext(),
                getString(R.string.failedToInitMicrophone), Toast.LENGTH_SHORT).show()
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

        audioRecord = record
        isRecording.set(true)
        setRecordingState(RecordingState.RECORDING)
        resetChart()
        smoother.reset()
        sampleFileBuilder.reset()
        sampleFileBuilder.setThreshold(currentThreshold)
        refreshRustlingThresholdExceedanceSetting()

        record.startRecording()

        recordingThread = thread(start = true, name = "AudioRecordThread") {
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

                    if (chartAccumulatorCount >= chartSamplesPerPoint) {
                        chartPoints.add(chartAccumulator / chartAccumulatorCount.toFloat())
                        chartAccumulator = 0f
                        chartAccumulatorCount = 0
                    }
                }


                if (chartPoints.isNotEmpty()) {
                    activity?.runOnUiThread {
                        if (
                            isAdded &&
                            this@DataCollectionFragment.view != null &&
                            recordingState == RecordingState.RECORDING
                        ) {
                            for (point in chartPoints) {
                                appendChartPoint(point)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        if (recordingState == RecordingState.IDLE) {
            setRecordingState(RecordingState.IDLE)
            return
        }

        isRecording.set(false)

        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }

        audioRecord?.release()
        audioRecord = null

        recordingThread?.join(300)
        recordingThread = null

        setRecordingState(RecordingState.IDLE)
    }

    private fun setupChart() {
        lineChartAmplitude.description.isEnabled = false
        lineChartAmplitude.legend.isEnabled = false
        lineChartAmplitude.setTouchEnabled(false)
        lineChartAmplitude.setPinchZoom(false)
        lineChartAmplitude.setScaleEnabled(false)

        lineChartAmplitude.axisRight.isEnabled = false
        lineChartAmplitude.axisLeft.axisMinimum = 0f
        lineChartAmplitude.axisLeft.axisMaximum = CHART_AXIS_MAX
        lineChartAmplitude.axisLeft.setDrawGridLines(true)

        lineChartAmplitude.xAxis.position = XAxis.XAxisPosition.BOTTOM
        lineChartAmplitude.xAxis.setDrawGridLines(false)

        val dataSet = LineDataSet(mutableListOf(), "Amplitude")
        dataSet.setDrawValues(false)
        dataSet.setDrawCircles(false)
        dataSet.lineWidth = 2f

        lineChartAmplitude.data = LineData(dataSet)
        updateThresholdLine()
        lineChartAmplitude.invalidate()
    }

    private fun setupThresholdSeekBar() {
        seekBarThreshold.max = THRESHOLD_MAX
        seekBarThreshold.progress = DEFAULT_THRESHOLD

        seekBarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentThreshold = progress.toFloat()
                sampleFileBuilder.setThreshold(currentThreshold)
                updateThresholdLine()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun setupAudioFragmentsList() {
        audioFragmentsAdapter = AudioFragmentsAdapter(
            onPlay = { file -> playAudioFragment(file) },
            onRemove = { file -> removeAudioFragment(file) },
            onApprove = { file -> approveAudioFragment(file) }
        )
        rvAudioFragments.layoutManager = LinearLayoutManager(requireContext())
        rvAudioFragments.adapter = audioFragmentsAdapter
    }

    private fun refreshAudioFragmentsList() {
        samplesDir.mkdirs()
        val files = samplesDir
            .listFiles { file ->
                file.isFile && file.name.endsWith("_1_unchecked.wav")
            }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        audioFragmentsAdapter.submitFiles(files)
    }

    private fun refreshRustlingThresholdExceedanceSetting() {
        sampleFileBuilder.setThresholdExceedancePercent(
            AppSettings.getRustlingThresholdExceedancePercent(requireContext())
        )
    }

    private fun playAudioFragment(file: File) {
        if (!file.exists()) {
            refreshAudioFragmentsList()
            return
        }

        stopPlayback()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { stopPlayback() }
            setOnErrorListener { _, _, _ ->
                stopPlayback()
                true
            }
            prepare()
            start()
        }
    }

    private fun removeAudioFragment(file: File) {
        stopPlayback()
        if (file.exists()) file.delete()
        refreshAudioFragmentsList()
        Toast.makeText(
            requireContext(),
            getString(R.string.audioFragmentDeleted),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun approveAudioFragment(file: File) {
        stopPlayback()
        if (file.exists()) {
            val approvedFile = File(file.parentFile, file.name.replace("_unchecked.wav", ".wav"))
            if (!approvedFile.exists()) {
                file.renameTo(approvedFile)
            }
        }
        refreshAudioFragmentsList()
        Toast.makeText(
            requireContext(),
            getString(R.string.audioFragmentSaved),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun stopPlayback() {
        mediaPlayer?.run {
            try {
                if (isPlaying) stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        mediaPlayer = null
    }

    private fun resetChart() {
        chartEntries.clear()
        chartX = 0f

        val dataSet = LineDataSet(mutableListOf(), "Amplitude")
        dataSet.setDrawValues(false)
        dataSet.setDrawCircles(false)
        dataSet.lineWidth = 2f

        lineChartAmplitude.data = LineData(dataSet)
        updateThresholdLine()
        lineChartAmplitude.invalidate()
    }

    private fun updateThresholdLine() {
        val axisLeft = lineChartAmplitude.axisLeft
        axisLeft.removeAllLimitLines()

        val thresholdLine = LimitLine(currentThreshold, getString(R.string.thresholdLabel))
        thresholdLine.lineWidth = 2f
        thresholdLine.enableDashedLine(12f, 8f, 0f)

        axisLeft.addLimitLine(thresholdLine)
        lineChartAmplitude.invalidate()
    }

    private fun appendChartPoint(value: Float) {
        val data = lineChartAmplitude.data ?: return
        val dataSet = data.getDataSetByIndex(0) as? LineDataSet ?: return

        data.addEntry(Entry(chartX, value), 0)
        chartX += 1f

        if (dataSet.entryCount > maxVisiblePoints) {
            dataSet.removeFirst()
            for (i in 0 until dataSet.entryCount) {
                dataSet.getEntryForIndex(i).x = i.toFloat()
            }
            chartX = dataSet.entryCount.toFloat()
        }

        data.notifyDataChanged()
        lineChartAmplitude.notifyDataSetChanged()
        lineChartAmplitude.setVisibleXRangeMaximum(maxVisiblePoints.toFloat())
        lineChartAmplitude.moveViewToX(chartX)
        lineChartAmplitude.invalidate()
    }
}
