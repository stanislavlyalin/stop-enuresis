package com.stanislavlyalin.stopenuresis.ui

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
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
import com.stanislavlyalin.stopenuresis.AppStorage
import com.stanislavlyalin.stopenuresis.R
import com.stanislavlyalin.stopenuresis.service.DataCollectionRecordingService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DataCollectionFragment : Fragment(R.layout.fragment_data_collection) {

    private lateinit var btnRecordToggle: Button
    private lateinit var btnClearPendingSamples: ImageButton
    private lateinit var lineChartAmplitude: LineChart
    private lateinit var rvAudioFragments: RecyclerView
    private lateinit var seekBarThreshold: SeekBar
    private lateinit var tvThresholdLabel: TextView
    private lateinit var tvSilenceCounter: TextView
    private lateinit var tvRemainingCounter: TextView
    private lateinit var tvRustlingCounter: TextView
    private lateinit var audioFragmentsAdapter: AudioFragmentsAdapter
    private lateinit var samplesDir: File

    private var recordingState: RecordingState = RecordingState.IDLE
    private var silenceFilesCount = 0
    private var remainingFilesCount = 0
    private var rustlingFilesCount = 0

    private var mediaPlayer: MediaPlayer? = null
    private var playbackLoudnessEnhancer: LoudnessEnhancer? = null
    private var playingFile: File? = null
    private val playbackProgressHandler = Handler(Looper.getMainLooper())
    private val playbackProgressRunnable = object : Runnable {
        override fun run() {
            val player = mediaPlayer ?: return
            val file = playingFile ?: return
            val duration = runCatching { player.duration }.getOrDefault(0)
            val position = runCatching { player.currentPosition }.getOrDefault(0)
            val progress = if (duration > 0) {
                position.toFloat() / duration.toFloat()
            } else {
                0f
            }
            audioFragmentsAdapter.setPlayback(file, progress)
            playbackProgressHandler.postDelayed(this, PLAYBACK_PROGRESS_UPDATE_MS)
        }
    }

    private val chartEntries = mutableListOf<Entry>()
    private var chartX = 0f
    private val maxVisiblePoints = 60
    private var currentThreshold = AppSettings.DEFAULT_VOLUME_THRESHOLD.toFloat()
    private val sampleFileDateFormat = SimpleDateFormat(
        SAMPLE_FILE_DATE_FORMAT,
        Locale.getDefault()
    ).apply {
        isLenient = false
    }
    private val dialogDateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val dialogTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

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

    private val samplesChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DataCollectionRecordingService.ACTION_SAMPLES_CHANGED) return
            refreshAudioFragmentsList()
        }
    }

    private companion object {
        const val CHART_AXIS_MAX = 200f
        const val CHART_AXIS_STEP = 25f
        const val CHART_AXIS_LABEL_COUNT = 9
        const val THRESHOLD_MAX = 200
        const val PLAYBACK_PROGRESS_UPDATE_MS = 50L
        // 20 * log10(3) дБ - усиление амплитуды семпла в три раза.
        const val PLAYBACK_GAIN_MILLIBELS = 954
        const val SAMPLE_FILE_DATE_FORMAT = "yyyy.MM.dd_HH.mm.ss"
        const val UNCHECKED_RUSTLING_SUFFIX = "_1_unchecked.wav"
        const val DETECTED_UNCHECKED_RUSTLING_SUFFIX = "_1_detected_unchecked.wav"
        const val SILENCE_SUFFIX = "_0.wav"
        const val RUSTLING_SUFFIX = "_1.wav"
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
        btnClearPendingSamples = view.findViewById(R.id.btnClearPendingSamples)
        lineChartAmplitude = view.findViewById(R.id.lineChartAmplitude)
        rvAudioFragments = view.findViewById(R.id.rvAudioFragments)
        seekBarThreshold = view.findViewById(R.id.seekBarThreshold)
        tvThresholdLabel = view.findViewById(R.id.tvThresholdLabel)
        tvSilenceCounter = view.findViewById(R.id.tvSilenceCounter)
        tvRemainingCounter = view.findViewById(R.id.tvRemainingCounter)
        tvRustlingCounter = view.findViewById(R.id.tvRustlingCounter)
        samplesDir = AppStorage.samplesDir(requireContext())

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
        btnClearPendingSamples.setOnClickListener {
            android.widget.PopupMenu(requireContext(), btnClearPendingSamples).apply {
                menu.add(R.string.clearPendingSamples).setOnMenuItemClickListener { showClearPendingSamplesDialog(); true }
                show()
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
        view?.findViewById<TextView>(R.id.tvDataCollectionTitle)?.text = getString(
            if (state == RecordingState.RECORDING) R.string.quiet_recording else R.string.collectData)

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
        lineChartAmplitude.axisLeft.granularity = CHART_AXIS_STEP
        lineChartAmplitude.axisLeft.setLabelCount(CHART_AXIS_LABEL_COUNT, true)
        lineChartAmplitude.axisLeft.setDrawGridLines(true)

        lineChartAmplitude.xAxis.position = XAxis.XAxisPosition.BOTTOM
        lineChartAmplitude.xAxis.setDrawGridLines(false)

        val dataSet = LineDataSet(mutableListOf(), "Amplitude")
        dataSet.setDrawValues(false)
        dataSet.setDrawCircles(false)
        dataSet.color = requireContext().getColor(R.color.brand_orange)
        dataSet.lineWidth = 2f

        lineChartAmplitude.data = LineData(dataSet)
        updateThresholdLine()
        lineChartAmplitude.invalidate()
    }

    private fun setupThresholdSeekBar() {
        seekBarThreshold.max = THRESHOLD_MAX
        currentThreshold = AppSettings.getVolumeThreshold(requireContext()).toFloat()
        seekBarThreshold.progress = currentThreshold.toInt()
        updateThresholdValueLabel()
        updateThresholdLine()

        seekBarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentThreshold = progress.toFloat()
                updateThresholdValueLabel()
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
            onStop = { stopPlayback() },
            onDelete = { file -> deleteAudioFragment(file) },
            onReject = { file -> markAudioFragmentAsSilence(file) },
            onApprove = { file -> approveAudioFragment(file) }
        )
        rvAudioFragments.layoutManager = LinearLayoutManager(requireContext())
        rvAudioFragments.adapter = audioFragmentsAdapter
    }

    private fun refreshAudioFragmentsList() {
        val files = listUncheckedRustlingFiles()

        audioFragmentsAdapter.submitFiles(files)
        remainingFilesCount = files.size
        updateSampleCounters()
    }

    private fun playAudioFragment(file: File) {
        if (!file.exists()) {
            refreshAudioFragmentsList()
            return
        }

        stopPlayback()
        playingFile = file
        audioFragmentsAdapter.setPlayback(file, 0f)
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { stopPlayback() }
            setOnErrorListener { _, _, _ ->
                stopPlayback()
                true
            }
            prepare()
            playbackLoudnessEnhancer = createPlaybackLoudnessEnhancer(audioSessionId)
            start()
        }
        playbackProgressHandler.post(playbackProgressRunnable)
    }

    private fun createPlaybackLoudnessEnhancer(audioSessionId: Int): LoudnessEnhancer? {
        val enhancer = try {
            LoudnessEnhancer(audioSessionId)
        } catch (_: RuntimeException) {
            return null
        }
        return try {
            enhancer.setTargetGain(PLAYBACK_GAIN_MILLIBELS)
            enhancer.enabled = true
            enhancer
        } catch (_: RuntimeException) {
            enhancer.release()
            null
        }
    }

    private fun deleteAudioFragment(file: File) {
        stopPlayback()
        val wasUnchecked = isUncheckedRustlingFile(file)
        val deleted = file.exists() && file.delete()

        if (deleted && wasUnchecked) {
            if (recordingState == RecordingState.RECORDING) {
                DataCollectionRecordingService.adjustDeletedRustling(requireContext())
            }
            notifySamplesChanged()
        }
        refreshAudioFragmentsList()
        Toast.makeText(
            requireContext(),
            getString(
                if (deleted) {
                    R.string.audioFragmentDeleted
                } else {
                    R.string.audioFragmentDeleteFailed
                }
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun markAudioFragmentAsSilence(file: File) {
        stopPlayback()
        val savedAsSilence = saveUncheckedAudioFragmentAs(file, classLabel = 0)

        if (savedAsSilence) {
            if (recordingState == RecordingState.RECORDING) {
                DataCollectionRecordingService.adjustRemovedRustling(requireContext())
            } else {
                silenceFilesCount++
                updateSampleCounters()
            }
            notifySamplesChanged()
        }
        refreshAudioFragmentsList()
        Toast.makeText(
            requireContext(),
            getString(
                if (savedAsSilence) {
                    R.string.audioFragmentSavedAsSilence
                } else {
                    R.string.audioFragmentSaveFailed
                }
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun saveUncheckedAudioFragmentAs(file: File, classLabel: Int): Boolean {
        if (!isUncheckedRustlingFile(file)) return false
        if (!file.exists()) return false

        val labeledFile = buildLabeledFileForUnchecked(file, classLabel)
        if (file.renameTo(labeledFile)) return true

        return runCatching {
            file.copyTo(labeledFile, overwrite = false)
            if (file.delete()) {
                true
            } else {
                labeledFile.delete()
                false
            }
        }.getOrDefault(false)
    }

    private fun buildLabeledFileForUnchecked(file: File, classLabel: Int): File {
        val targetSuffix = when (classLabel) {
            0 -> SILENCE_SUFFIX
            else -> RUSTLING_SUFFIX
        }
        val baseName = file.name
            .removeSuffix(DETECTED_UNCHECKED_RUSTLING_SUFFIX)
            .removeSuffix(UNCHECKED_RUSTLING_SUFFIX) + targetSuffix
        var labeledFile = File(file.parentFile, baseName)
        var duplicateIndex = 1

        while (labeledFile.exists()) {
            labeledFile = File(
                file.parentFile,
                baseName.replace(targetSuffix, "_marked_${duplicateIndex}${targetSuffix}")
            )
            duplicateIndex++
        }

        return labeledFile
    }

    private fun showClearPendingSamplesDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_clear_pending_samples, null, false)
        val btnStartDate = dialogView.findViewById<Button>(R.id.btnStartDate)
        val btnStartTime = dialogView.findViewById<Button>(R.id.btnStartTime)
        val btnEndDate = dialogView.findViewById<Button>(R.id.btnEndDate)
        val btnEndTime = dialogView.findViewById<Button>(R.id.btnEndTime)
        val (startCalendar, endCalendar) = defaultClearPeriod()

        fun updateDateTimeFields() {
            btnStartDate.text = dialogDateFormat.format(startCalendar.time)
            btnStartTime.text = dialogTimeFormat.format(startCalendar.time)
            btnEndDate.text = dialogDateFormat.format(endCalendar.time)
            btnEndTime.text = dialogTimeFormat.format(endCalendar.time)
        }

        updateDateTimeFields()

        btnStartDate.setOnClickListener {
            showDatePicker(startCalendar, isEnd = false, onChanged = ::updateDateTimeFields)
        }
        btnStartTime.setOnClickListener {
            showTimePicker(startCalendar, isEnd = false, onChanged = ::updateDateTimeFields)
        }
        btnEndDate.setOnClickListener {
            showDatePicker(endCalendar, isEnd = true, onChanged = ::updateDateTimeFields)
        }
        btnEndTime.setOnClickListener {
            showTimePicker(endCalendar, isEnd = true, onChanged = ::updateDateTimeFields)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.clearPendingSamplesDialogTitle)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clearPendingSamplesAction, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (startCalendar.time.after(endCalendar.time)) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.invalidDateRange),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                val deletedCount = clearPendingSamplesForPeriod(
                    start = startCalendar.time,
                    end = endCalendar.time
                )
                val message = if (deletedCount > 0) {
                    getString(R.string.pendingSamplesDeletedCount, deletedCount)
                } else {
                    getString(R.string.pendingSamplesDeletedNone)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun defaultClearPeriod(): Pair<Calendar, Calendar> {
        val dates = listUncheckedRustlingFiles().mapNotNull { file -> parseUncheckedSampleDate(file) }
        val startCalendar = Calendar.getInstance()
        val endCalendar = Calendar.getInstance()

        if (dates.isNotEmpty()) {
            startCalendar.time = dates.minByOrNull { date -> date.time } ?: Date()
            endCalendar.time = dates.maxByOrNull { date -> date.time } ?: startCalendar.time
        }

        setMinuteBoundary(startCalendar, isEnd = false)
        setMinuteBoundary(endCalendar, isEnd = true)
        return startCalendar to endCalendar
    }

    private fun showDatePicker(
        calendar: Calendar,
        isEnd: Boolean,
        onChanged: () -> Unit
    ) {
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                setMinuteBoundary(calendar, isEnd)
                onChanged()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showTimePicker(
        calendar: Calendar,
        isEnd: Boolean,
        onChanged: () -> Unit
    ) {
        TimePickerDialog(
            requireContext(),
            { _, hourOfDay, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                calendar.set(Calendar.MINUTE, minute)
                setMinuteBoundary(calendar, isEnd)
                onChanged()
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun setMinuteBoundary(calendar: Calendar, isEnd: Boolean) {
        calendar.set(Calendar.SECOND, if (isEnd) 59 else 0)
        calendar.set(Calendar.MILLISECOND, if (isEnd) 999 else 0)
    }

    private fun clearPendingSamplesForPeriod(start: Date, end: Date): Int {
        stopPlayback()
        val filesToDelete = listUncheckedRustlingFiles().filter { file ->
            val sampleDate = parseUncheckedSampleDate(file) ?: return@filter false
            !sampleDate.before(start) && !sampleDate.after(end)
        }
        val deletedCount = filesToDelete.count { file -> file.delete() }

        if (deletedCount > 0) {
            if (recordingState == RecordingState.RECORDING) {
                DataCollectionRecordingService.adjustDeletedRustling(requireContext(), deletedCount)
            }
            notifySamplesChanged()
        }
        refreshAudioFragmentsList()
        return deletedCount
    }

    private fun listUncheckedRustlingFiles(): List<File> {
        samplesDir.mkdirs()
        return samplesDir
            .listFiles { file ->
                file.isFile && isUncheckedRustlingFile(file)
            }
            ?.sortedByDescending { file -> sampleSortTime(file) }
            .orEmpty()
    }

    private fun sampleSortTime(file: File): Long {
        return parseUncheckedSampleDate(file)?.time ?: file.lastModified()
    }

    private fun parseUncheckedSampleDate(file: File): Date? {
        val timestamp = file.name
            .removeSuffix(DETECTED_UNCHECKED_RUSTLING_SUFFIX)
            .removeSuffix(UNCHECKED_RUSTLING_SUFFIX)
            .removeSuffix("_1")
        if (timestamp == file.name) return null
        return runCatching {
            sampleFileDateFormat.parse(timestamp)
        }.getOrNull()
    }

    private fun isUncheckedRustlingFile(file: File): Boolean {
        return file.name.endsWith(UNCHECKED_RUSTLING_SUFFIX) ||
            file.name.endsWith(DETECTED_UNCHECKED_RUSTLING_SUFFIX)
    }

    private fun resetSampleCounters() {
        silenceFilesCount = 0
        remainingFilesCount = 0
        rustlingFilesCount = 0
        updateSampleCounters()
    }

    private fun updateSampleCounters() {
        tvSilenceCounter.text = getString(R.string.silenceCounter, silenceFilesCount)
        tvRemainingCounter.text = getString(R.string.remainingCounter, remainingFilesCount)
        tvRustlingCounter.text = getString(R.string.rustlingCounter, rustlingFilesCount)
    }

    private fun registerRecordingReceivers() {
        val statusFilter = IntentFilter(DataCollectionRecordingService.ACTION_STATUS)
        val chartFilter = IntentFilter(DataCollectionRecordingService.ACTION_CHART_POINTS)
        val samplesChangedFilter =
            IntentFilter(DataCollectionRecordingService.ACTION_SAMPLES_CHANGED)
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
            requireContext().registerReceiver(
                samplesChangedReceiver,
                samplesChangedFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            requireContext().registerReceiver(recordingStatusReceiver, statusFilter)
            @Suppress("DEPRECATION")
            requireContext().registerReceiver(chartPointsReceiver, chartFilter)
            @Suppress("DEPRECATION")
            requireContext().registerReceiver(samplesChangedReceiver, samplesChangedFilter)
        }
    }

    private fun unregisterRecordingReceivers() {
        runCatching { requireContext().unregisterReceiver(recordingStatusReceiver) }
        runCatching { requireContext().unregisterReceiver(chartPointsReceiver) }
        runCatching { requireContext().unregisterReceiver(samplesChangedReceiver) }
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
        val approved = saveUncheckedAudioFragmentAs(file, classLabel = 1)
        if (approved && recordingState != RecordingState.RECORDING) {
            rustlingFilesCount++
            updateSampleCounters()
        }
        if (approved) notifySamplesChanged()
        refreshAudioFragmentsList()
        Toast.makeText(
            requireContext(),
            getString(
                if (approved) {
                    R.string.audioFragmentSavedAsRustling
                } else {
                    R.string.audioFragmentSaveFailed
                }
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun stopPlayback() {
        playbackProgressHandler.removeCallbacks(playbackProgressRunnable)
        playbackLoudnessEnhancer?.release()
        playbackLoudnessEnhancer = null
        mediaPlayer?.run {
            try {
                if (isPlaying) stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        mediaPlayer = null
        playingFile = null
        if (::audioFragmentsAdapter.isInitialized) {
            audioFragmentsAdapter.setPlayback(null, 0f)
        }
    }

    private fun resetChart() {
        chartEntries.clear()
        chartX = 0f

        val dataSet = LineDataSet(mutableListOf(), "Amplitude")
        dataSet.setDrawValues(false)
        dataSet.setDrawCircles(false)
        dataSet.color = requireContext().getColor(R.color.brand_orange)
        dataSet.lineWidth = 2f

        lineChartAmplitude.data = LineData(dataSet)
        updateThresholdLine()
        lineChartAmplitude.invalidate()
    }

    private fun updateThresholdLine() {
        lineChartAmplitude.axisLeft.textColor = requireContext().getColor(R.color.ui_secondary)
        lineChartAmplitude.xAxis.textColor = requireContext().getColor(R.color.ui_secondary)
        val axisLeft = lineChartAmplitude.axisLeft
        axisLeft.removeAllLimitLines()

        val thresholdLine = LimitLine(
            currentThreshold,
            getString(R.string.thresholdLabel, currentThreshold.toInt())
        )
        thresholdLine.lineWidth = 2f
        thresholdLine.enableDashedLine(12f, 8f, 0f)

        axisLeft.addLimitLine(thresholdLine)
        lineChartAmplitude.invalidate()
    }

    private fun updateThresholdValueLabel() {
        tvThresholdLabel.text = getString(R.string.volumeThresholdValue, currentThreshold.toInt())
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

    private fun notifySamplesChanged() {
        val context = requireContext()
        context.sendBroadcast(Intent(DataCollectionRecordingService.ACTION_SAMPLES_CHANGED).apply {
            setPackage(context.packageName)
        })
    }
}
