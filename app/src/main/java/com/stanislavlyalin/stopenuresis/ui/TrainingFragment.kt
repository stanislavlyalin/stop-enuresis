package com.stanislavlyalin.stopenuresis.ui

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.stanislavlyalin.stopenuresis.AppStorage
import com.stanislavlyalin.stopenuresis.R
import com.stanislavlyalin.stopenuresis.training.TrainingDatasetStats
import com.stanislavlyalin.stopenuresis.training.TrainingProgress
import com.stanislavlyalin.stopenuresis.training.TrainingRepository
import com.stanislavlyalin.stopenuresis.training.TrainingResult
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class TrainingFragment : Fragment(R.layout.fragment_training) {

    private lateinit var tvTrainingStatusCircle: TextView
    private lateinit var tvTrainingDatasetStats: TextView
    private lateinit var checkBoxBalanceDataset: CheckBox
    private lateinit var btnStartTraining: Button
    private lateinit var layoutTrainingProgress: View
    private lateinit var progressTrainingFiles: ProgressBar
    private lateinit var tvTrainingProgress: TextView
    private lateinit var btnClearTrainingDataset: Button
    private lateinit var trainingRepository: TrainingRepository

    private var isTraining = false
    private var trainingProgress = TrainingProgress(0, 1)
    private var lastAccuracy: Double? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvTrainingStatusCircle = view.findViewById(R.id.tvTrainingStatusCircle)
        tvTrainingDatasetStats = view.findViewById(R.id.tvTrainingDatasetStats)
        checkBoxBalanceDataset = view.findViewById(R.id.checkBoxBalanceDataset)
        btnStartTraining = view.findViewById(R.id.btnStartTraining)
        layoutTrainingProgress = view.findViewById(R.id.layoutTrainingProgress)
        progressTrainingFiles = view.findViewById(R.id.progressTrainingFiles)
        tvTrainingProgress = view.findViewById(R.id.tvTrainingProgress)
        btnClearTrainingDataset = view.findViewById(R.id.btnClearTrainingDataset)

        trainingRepository = TrainingRepository(
            samplesDir = AppStorage.samplesDir(requireContext()),
            modelFile = AppStorage.modelFile(requireContext())
        )

        btnStartTraining.setOnClickListener { startTraining() }
        btnClearTrainingDataset.setOnClickListener { showClearDatasetDialog() }
        checkBoxBalanceDataset.setOnCheckedChangeListener { _, _ -> refreshUi() }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        if (::trainingRepository.isInitialized && !isTraining) refreshUi()
    }

    private fun refreshUi() {
        val stats = trainingRepository.getDatasetStats()
        val visibleStats = getVisibleDatasetStats(stats)
        val modelAccuracy = lastAccuracy ?: trainingRepository.readModelAccuracy()
        val hasModel = trainingRepository.hasModel()

        tvTrainingDatasetStats.text = formatDatasetStats(visibleStats)
        tvTrainingStatusCircle.text = when {
            isTraining -> getString(R.string.trainingInProgress)
            hasModel -> getString(
                R.string.modelTrainedWithAccuracy,
                ((modelAccuracy ?: 0.0) * 100.0).roundToInt()
            )
            else -> getString(R.string.modelNotTrained)
        }

        btnStartTraining.isEnabled = !isTraining
        btnStartTraining.visibility = if (isTraining) View.GONE else View.VISIBLE
        layoutTrainingProgress.visibility = if (isTraining) View.VISIBLE else View.GONE
        checkBoxBalanceDataset.isEnabled = !isTraining
        btnStartTraining.text = if (isTraining) {
            getString(R.string.trainingInProgress)
        } else {
            getString(R.string.startTraining)
        }
        updateTrainingProgress()
        btnClearTrainingDataset.visibility = if (hasModel && !isTraining) View.VISIBLE else View.GONE
    }

    private fun startTraining() {
        if (isTraining) return

        val stats = trainingRepository.getDatasetStats()
        val effectiveStats = getVisibleDatasetStats(stats)
        if (
            effectiveStats.positiveCount < MIN_SAMPLES_PER_CLASS ||
            effectiveStats.negativeCount < MIN_SAMPLES_PER_CLASS
        ) {
            Toast.makeText(
                requireContext(),
                getString(R.string.notEnoughTrainingSamples),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val balanceDataset = checkBoxBalanceDataset.isChecked
        val existingAccuracy = trainingRepository.readModelAccuracy()
        isTraining = true
        trainingProgress = TrainingProgress(
            processedFiles = 0,
            totalFiles = effectiveStats.positiveCount + effectiveStats.negativeCount
        )
        refreshUi()

        thread(start = true, name = "ModelTrainingThread") {
            val result = runCatching {
                trainingRepository.train(
                    balanceDataset = balanceDataset,
                    onProgress = { progress -> postTrainingProgress(progress) }
                )
            }

            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread

                isTraining = false
                trainingProgress = TrainingProgress(0, 1)
                result
                    .onSuccess {
                        handleTrainingSuccess(it, existingAccuracy)
                    }
                    .onFailure {
                        refreshUi()
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.trainingFailed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
        }
    }

    private fun handleTrainingSuccess(
        result: TrainingResult,
        existingAccuracy: Double?
    ) {
        if (existingAccuracy != null && result.accuracy < existingAccuracy) {
            showReplaceWorseModelDialog(result, existingAccuracy)
            return
        }

        saveTrainingResult(result)
    }

    private fun showReplaceWorseModelDialog(
        result: TrainingResult,
        existingAccuracy: Double
    ) {
        AlertDialog.Builder(requireContext())
            .setMessage(
                getString(
                    R.string.replaceWorseModelDialogMessage,
                    (result.accuracy * 100.0).roundToInt(),
                    (existingAccuracy * 100.0).roundToInt()
                )
            )
            .setPositiveButton(R.string.confirm) { _, _ -> saveTrainingResult(result) }
            .setNegativeButton(R.string.no) { _, _ -> refreshUi() }
            .show()
    }

    private fun saveTrainingResult(result: TrainingResult) {
        runCatching {
            trainingRepository.saveModel(result)
        }.onSuccess {
            lastAccuracy = result.accuracy
            refreshUi()
        }.onFailure {
            refreshUi()
            Toast.makeText(
                requireContext(),
                getString(R.string.trainingFailed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showClearDatasetDialog() {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.clearTrainingDatasetDialogMessage)
            .setPositiveButton(R.string.confirm) { _, _ ->
                trainingRepository.clearDataset()
                refreshUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun formatDatasetStats(stats: TrainingDatasetStats): CharSequence {
        return SpannableStringBuilder().apply {
            append(getString(R.string.trainingDatasetTitle))
            append('\n')
            appendDatasetLine(
                line = getString(R.string.trainingDatasetPositiveLine, stats.positiveCount),
                levelRange = stats.positiveLevelRange,
                levelColor = Color.RED
            )
            append('\n')
            appendDatasetLine(
                line = getString(R.string.trainingDatasetNegativeLine, stats.negativeCount),
                levelRange = stats.negativeLevelRange,
                levelColor = Color.rgb(0, 140, 0)
            )
        }
    }

    private fun SpannableStringBuilder.appendDatasetLine(
        line: String,
        levelRange: IntRange?,
        levelColor: Int
    ) {
        append(line)
        if (levelRange == null) return

        val start = length
        append(' ')
        append(
            getString(
                R.string.trainingDatasetLevelRange,
                levelRange.first,
                levelRange.last
            )
        )
        setSpan(
            ForegroundColorSpan(levelColor),
            start,
            length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun postTrainingProgress(progress: TrainingProgress) {
        activity?.runOnUiThread {
            if (!isAdded || view == null || !isTraining) return@runOnUiThread

            trainingProgress = progress
            updateTrainingProgress()
        }
    }

    private fun updateTrainingProgress() {
        if (!::progressTrainingFiles.isInitialized) return

        val maxProgress = trainingProgress.totalFiles.coerceAtLeast(1)
        val currentProgress = trainingProgress.processedFiles.coerceIn(0, maxProgress)
        progressTrainingFiles.max = maxProgress
        progressTrainingFiles.progress = currentProgress
        progressTrainingFiles.contentDescription = getString(
            R.string.trainingProgressDescription,
            currentProgress,
            maxProgress
        )
        tvTrainingProgress.text = getString(R.string.trainingInProgress)
    }

    private fun getVisibleDatasetStats(stats: TrainingDatasetStats): TrainingDatasetStats {
        if (!checkBoxBalanceDataset.isChecked) return stats

        val balancedCount = minOf(stats.positiveCount, stats.negativeCount)
        return stats.copy(
            positiveCount = balancedCount,
            negativeCount = balancedCount
        )
    }

    private companion object {
        const val MIN_SAMPLES_PER_CLASS = 2
    }
}
