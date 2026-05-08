package com.stanislavlyalin.stopenuresis.training

import java.io.File
import kotlin.random.Random

data class TrainingDatasetStats(
    val positiveCount: Int,
    val negativeCount: Int
)

data class TrainingResult(
    val modelFile: File,
    val accuracy: Double
)

class TrainingRepository(
    private val samplesDir: File,
    private val modelFile: File
) {
    private val mfccExtractor = MfccExtractor()
    private val trainer = LogisticRegressionTrainer()

    fun getDatasetStats(): TrainingDatasetStats {
        val files = listSampleFiles()
        return TrainingDatasetStats(
            positiveCount = files.count { it.name.endsWith("_1.wav") },
            negativeCount = files.count { getLabel(it) == 0 }
        )
    }

    fun hasModel(): Boolean = modelFile.exists()

    fun readModelAccuracy(): Double? {
        if (!modelFile.exists()) return null
        return runCatching {
            org.json.JSONObject(modelFile.readText()).optDouble("accuracy")
        }.getOrNull()
    }

    fun train(balanceDataset: Boolean): TrainingResult {
        val labeledFiles = listSampleFiles()
            .mapNotNull { file -> getLabel(file)?.let { label -> file to label } }

        val random = Random(System.currentTimeMillis())
        val positives = labeledFiles.filter { it.second == 1 }
        val negatives = labeledFiles.filter { it.second == 0 }
        require(positives.size >= MIN_SAMPLES_PER_CLASS && negatives.size >= MIN_SAMPLES_PER_CLASS) {
            "At least $MIN_SAMPLES_PER_CLASS samples per class are required"
        }

        val balancedSampleCount = minOf(positives.size, negatives.size)
        val trainingPositives = if (balanceDataset) {
            positives.shuffled(random).take(balancedSampleCount)
        } else {
            positives
        }
        val trainingNegatives = if (balanceDataset) {
            negatives.shuffled(random).take(balancedSampleCount)
        } else {
            negatives
        }

        val positiveSplit = splitClassSamples(trainingPositives, random)
        val negativeSplit = splitClassSamples(trainingNegatives, random)
        val trainSet = (positiveSplit.first + negativeSplit.first).shuffled(random)
        val testSet = (positiveSplit.second + negativeSplit.second).shuffled(random)

        val trainFeatures = trainSet.map { mfccExtractor.extractFeatures(WavReader.readPcm16Mono(it.first)) }
        val trainLabels = trainSet.map { it.second }
        val testFeatures = testSet.map { mfccExtractor.extractFeatures(WavReader.readPcm16Mono(it.first)) }
        val testLabels = testSet.map { it.second }

        val model = trainer.train(
            trainFeatures = trainFeatures,
            trainLabels = trainLabels,
            testFeatures = testFeatures,
            testLabels = testLabels
        )

        modelFile.parentFile?.mkdirs()
        modelFile.writeText(model.toJson().toString(2))
        return TrainingResult(modelFile, model.accuracy)
    }

    fun clearDataset() {
        listSampleFiles().forEach { it.delete() }
    }

    private fun listSampleFiles(): List<File> {
        samplesDir.mkdirs()
        return samplesDir.listFiles { file ->
            file.isFile && getLabel(file) != null
        }?.toList().orEmpty()
    }

    private fun getLabel(file: File): Int? {
        val name = file.name
        return when {
            name.endsWith("_1.wav") || name.endsWith("_1_unchecked.wav") -> 1
            name.endsWith("_0.wav") -> 0
            else -> null
        }
    }

    private fun splitClassSamples(
        samples: List<Pair<File, Int>>,
        random: Random
    ): Pair<List<Pair<File, Int>>, List<Pair<File, Int>>> {
        val shuffled = samples.shuffled(random)
        val trainCount = (shuffled.size * TRAIN_SPLIT).toInt()
            .coerceIn(1, shuffled.size - 1)
        return shuffled.take(trainCount) to shuffled.drop(trainCount)
    }

    private companion object {
        const val TRAIN_SPLIT = 0.7
        const val MIN_SAMPLES_PER_CLASS = 2
    }
}
