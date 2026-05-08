package com.stanislavlyalin.stopenuresis.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
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
import com.stanislavlyalin.stopenuresis.service.DataCollectionRecordingService
import java.io.File

class DataCollectionFragment : Fragment(R.layout.fragment_data_collection) {

    private lateinit var btnRecordToggle: Button
    private lateinit var lineChartAmplitude: LineChart
    private lateinit var rvAudioFragments: RecyclerView
    private lateinit var seekBarThreshold: SeekBar
    private lateinit var tvSilenceCounter: TextView
    private lateinit var tvRustlingCounter: TextView
    private lateinit var audioFragmentsAdapter: AudioFragmentsAdapter
    private lateinit var samplesDir: File

    private var recordingState: RecordingState = RecordingState.IDLE
    private var silenceFilesCount = 0
    private var rustlingFilesCount = 0

    private var mediaPlayer: MediaPlayer? = null

    private val chartEntries = mutableListOf<Entry>()
    private var chartX = 0f
    private val maxVisiblePoints = 60
    private var currentThreshold = AppSettings.DEFAULT_VOLUME_THRESHOLD.toFloat()

    private val recordingStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DataCollectionRecordingService.ACTION_STATUS) return
            val isRecording = intent.getBooleanExtra(
                DataCollectionRecordingService.EXTRA_IS_RECORDING,
                false
            )
            silenceFilesCount = intent.getIntExtra(
                DataCollectionRecordingService.EXTRA_SILENCE_COUNT,
                0
            )
            rustlingFilesCount = intent.getIntExtra(
                DataCollectionRecordingService.EXTRA_RUSTLING_COUNT,
                0
            )
            setRecordingState(if (isRecording) RecordingState.RECORDING else RecordingState.IDLE)
            updateSampleCounters()
            refreshAudioFragmentsList()
        }
    }

    private val chartPointsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DataCollectionRecordingService.ACTION_CHART_POINTS) return
            if (recordingState != RecordingState.RECORDING) return
            intent.getFloatArrayExtra(DataCollectionRecordingService.EXTRA_CHART_POINTS)
                ?.forEach { appendChartPoint(it) }
        }
    }

    private companion object {
        const val CHART_AXIS_MAX = 500f
        const val THRESHOLD_MAX = 500

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
        tvSilenceCounter = view.findViewById(R.id.tvSilenceCounter)
        tvRustlingCounter = view.findViewById(R.id.tvRustlingCounter)
        samplesDir = File(requireContext().filesDir, "samples")

        setupChart()
        setupThresholdSeekBar()
        setupAudioFragmentsList()
        setRecordingState(RecordingState.IDLE)
        updateSampleCounters()
        refreshAudioFragmentsList()

        btnRecordToggle.setOnClickListener {
            when (recordingState) {
                RecordingState.IDLE -> ensurePermissionAndStart()
                RecordingState.RECORDING -> stopRecording()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerRecordingReceivers()
        syncRecordingStateFromService()
    }

    override fun onStop() {
        super.onStop()
        unregisterRecordingReceivers()
        stopPlayback()
    }

    override fun onDestroyView() {
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
        setRecordingState(RecordingState.RECORDING)
        resetChart()
        resetSampleCounters()
        DataCollectionRecordingService.start(requireContext(), currentThreshold)
    }

    private fun stopRecording() {
        if (recordingState == RecordingState.IDLE) {
            setRecordingState(RecordingState.IDLE)
            return
        }
        DataCollectionRecordingService.stop(requireContext())
        setRecordingState(RecordingState.IDLE)
        resetSampleCounters()
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
        currentThreshold = AppSettings.getVolumeThreshold(requireContext()).toFloat()
        seekBarThreshold.progress = currentThreshold.toInt()
        updateThresholdLine()

        seekBarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentThreshold = progress.toFloat()
                updateThresholdLine()
                if (fromUser) {
                    AppSettings.setVolumeThreshold(requireContext(), progress)
                }
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
        val savedAsSilence = saveRemovedRustlingAsSilence(file)

        if (savedAsSilence && file.name.endsWith("_1_unchecked.wav")) {
            if (recordingState == RecordingState.RECORDING) {
                DataCollectionRecordingService.adjustRemovedRustling(requireContext())
            } else {
                rustlingFilesCount = (rustlingFilesCount - 1).coerceAtLeast(0)
                updateSampleCounters()
            }
        }
        refreshAudioFragmentsList()
        Toast.makeText(
            requireContext(),
            getString(
                if (savedAsSilence) {
                    R.string.audioFragmentSavedAsSilence
                } else {
                    R.string.audioFragmentDeleted
                }
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun saveRemovedRustlingAsSilence(file: File): Boolean {
        if (!file.name.endsWith("_1_unchecked.wav")) return false
        if (!file.exists()) return false

        val silenceFile = buildSilenceFileForRemovedRustling(file)
        if (file.renameTo(silenceFile)) return true

        return runCatching {
            file.copyTo(silenceFile, overwrite = false)
            file.delete()
            true
        }.getOrDefault(false)
    }

    private fun buildSilenceFileForRemovedRustling(file: File): File {
        val baseName = file.name.replace("_1_unchecked.wav", "_0.wav")
        var silenceFile = File(file.parentFile, baseName)
        var duplicateIndex = 1

        while (silenceFile.exists()) {
            silenceFile = File(
                file.parentFile,
                baseName.replace("_0.wav", "_removed_${duplicateIndex}_0.wav")
            )
            duplicateIndex++
        }

        return silenceFile
    }

    private fun resetSampleCounters() {
        silenceFilesCount = 0
        rustlingFilesCount = 0
        updateSampleCounters()
    }

    private fun updateSampleCounters() {
        tvSilenceCounter.text = getString(R.string.silenceCounter, silenceFilesCount)
        tvRustlingCounter.text = getString(R.string.rustlingCounter, rustlingFilesCount)
    }

    private fun registerRecordingReceivers() {
        val statusFilter = IntentFilter(DataCollectionRecordingService.ACTION_STATUS)
        val chartFilter = IntentFilter(DataCollectionRecordingService.ACTION_CHART_POINTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                recordingStatusReceiver,
                statusFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
            requireContext().registerReceiver(
                chartPointsReceiver,
                chartFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            requireContext().registerReceiver(recordingStatusReceiver, statusFilter)
            @Suppress("DEPRECATION")
            requireContext().registerReceiver(chartPointsReceiver, chartFilter)
        }
    }

    private fun unregisterRecordingReceivers() {
        runCatching { requireContext().unregisterReceiver(recordingStatusReceiver) }
        runCatching { requireContext().unregisterReceiver(chartPointsReceiver) }
    }

    private fun syncRecordingStateFromService() {
        silenceFilesCount = DataCollectionRecordingService.silenceCountSnapshot
        rustlingFilesCount = DataCollectionRecordingService.rustlingCountSnapshot
        setRecordingState(
            if (DataCollectionRecordingService.isRecordingNow) {
                RecordingState.RECORDING
            } else {
                RecordingState.IDLE
            }
        )
        updateSampleCounters()
        refreshAudioFragmentsList()
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
