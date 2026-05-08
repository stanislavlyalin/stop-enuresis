package com.stanislavlyalin.stopenuresis.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.stanislavlyalin.stopenuresis.R
import com.stanislavlyalin.stopenuresis.training.TrainingDatasetStats
import com.stanislavlyalin.stopenuresis.training.TrainingRepository
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class TrainingFragment : Fragment(R.layout.fragment_training) {

    private lateinit var tvTrainingStatusCircle: TextView
    private lateinit var tvTrainingDatasetStats: TextView
    private lateinit var checkBoxBalanceDataset: CheckBox
    private lateinit var btnStartTraining: Button
    private lateinit var btnClearTrainingDataset: Button
    private lateinit var trainingRepository: TrainingRepository

    private var isTraining = false
    private var lastAccuracy: Double? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvTrainingStatusCircle = view.findViewById(R.id.tvTrainingStatusCircle)
        tvTrainingDatasetStats = view.findViewById(R.id.tvTrainingDatasetStats)
        checkBoxBalanceDataset = view.findViewById(R.id.checkBoxBalanceDataset)
        btnStartTraining = view.findViewById(R.id.btnStartTraining)
        btnClearTrainingDataset = view.findViewById(R.id.btnClearTrainingDataset)

        trainingRepository = TrainingRepository(
            samplesDir = File(requireContext().filesDir, "samples"),
            modelFile = File(requireContext().filesDir, "model/stopenuresis_model.json")
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
        checkBoxBalanceDataset.isEnabled = !isTraining
        btnStartTraining.text = if (isTraining) {
            getString(R.string.trainingInProgress)
        } else {
            getString(R.string.startTraining)
        }
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
        isTraining = true
        refreshUi()

        thread(start = true, name = "ModelTrainingThread") {
            val result = runCatching {
                trainingRepository.train(balanceDataset = balanceDataset)
            }

            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread

                isTraining = false
                result
                    .onSuccess {
                        lastAccuracy = it.accuracy
                        refreshUi()
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

    private fun formatDatasetStats(stats: TrainingDatasetStats): String {
        return getString(
            R.string.trainingDatasetStats,
            stats.positiveCount,
            stats.negativeCount
        )
    }

    private fun getVisibleDatasetStats(stats: TrainingDatasetStats): TrainingDatasetStats {
        if (!checkBoxBalanceDataset.isChecked) return stats

        val balancedCount = minOf(stats.positiveCount, stats.negativeCount)
        return TrainingDatasetStats(
            positiveCount = balancedCount,
            negativeCount = balancedCount
        )
    }

    private companion object {
        const val MIN_SAMPLES_PER_CLASS = 2
    }
}
