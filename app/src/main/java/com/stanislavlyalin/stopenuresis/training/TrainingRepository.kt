package com.stanislavlyalin.stopenuresis.training

import com.stanislavlyalin.stopenuresis.audio.AudioEnvelope
import java.io.File
import kotlin.math.roundToInt
import kotlin.random.Random

data class TrainingDatasetStats(
    val positiveCount: Int,
    val negativeCount: Int,
    val positiveAverageLevel: Int? = null,
    val negativeAverageLevel: Int? = null
)

data class TrainingProgress(
    val processedFiles: Int,
    val totalFiles: Int
)

data class TrainingResult(
    val modelJson: String,
    val accuracy: Double
)

class TrainingRepository(
    private val samplesDir: File,
    private val modelFile: File
) {
    private val mfccExtractor = MfccExtractor()
    private val trainer = LogisticRegressionTrainer()
    private val averageLevelCache = mutableMapOf<String, CachedAverageLevel>()

    fun getDatasetStats(): TrainingDatasetStats {
        val files = listSampleFiles()
        var positiveCount = 0
        var negativeCount = 0
        var positiveLevelSum = 0.0
        var negativeLevelSum = 0.0
        var positiveLevelCount = 0
        var negativeLevelCount = 0

        files.forEach { file ->
            when (getStatsLabel(file)) {
                1 -> {
                    positiveCount++
                    readAverageLevel(file)?.let { level ->
                        positiveLevelSum += level.toDouble()
                        positiveLevelCount++
                    }
                }

                0 -> {
                    negativeCount++
                    readAverageLevel(file)?.let { level ->
                        negativeLevelSum += level.toDouble()
                        negativeLevelCount++
                    }
                }
            }
        }

        return TrainingDatasetStats(
            positiveCount = positiveCount,
            negativeCount = negativeCount,
            positiveAverageLevel = averageLevel(positiveLevelSum, positiveLevelCount),
            negativeAverageLevel = averageLevel(negativeLevelSum, negativeLevelCount)
        )
    }

    fun hasModel(): Boolean = modelFile.exists()

    fun readModelAccuracy(): Double? {
        if (!modelFile.exists()) return null
        return runCatching {
            org.json.JSONObject(modelFile.readText()).optDouble("accuracy")
        }.getOrNull()
    }

    fun train(
        balanceDataset: Boolean,
        onProgress: (TrainingProgress) -> Unit = {}
    ): TrainingResult {
        val labeledFiles = listSampleFiles()
            .mapNotNull { file -> getStatsLabel(file)?.let { label -> file to label } }

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
        val totalFeatureFiles = trainSet.size + testSet.size
        var processedFeatureFiles = 0

        fun extractFeatures(file: File): DoubleArray {
            val features = mfccExtractor.extractFeatures(WavReader.readPcm16Mono(file))
            processedFeatureFiles++
            onProgress(TrainingProgress(processedFeatureFiles, totalFeatureFiles))
            return features
        }

        onProgress(TrainingProgress(0, totalFeatureFiles))
        val trainFeatures = trainSet.map { extractFeatures(it.first) }
        val trainLabels = trainSet.map { it.second }
        val testFeatures = testSet.map { extractFeatures(it.first) }
        val testLabels = testSet.map { it.second }

        val model = trainer.train(
            trainFeatures = trainFeatures,
            trainLabels = trainLabels,
            testFeatures = testFeatures,
            testLabels = testLabels
        )

        return TrainingResult(
            modelJson = model.toJson().toString(2),
            accuracy = model.accuracy
        )
    }

    fun saveModel(result: TrainingResult) {
        modelFile.parentFile?.mkdirs()
        modelFile.writeText(result.modelJson)
    }

    fun clearDataset() {
        listSampleFiles().forEach { it.delete() }
        averageLevelCache.clear()
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

    private fun getStatsLabel(file: File): Int? {
        val name = file.name
        return when {
            name.endsWith("_1.wav") -> 1
            name.endsWith("_0.wav") -> 0
            else -> null
        }
    }

    private fun readAverageLevel(file: File): Float? {
        val key = file.absolutePath
        val cached = averageLevelCache[key]
        if (cached != null && cached.lastModified == file.lastModified()) {
            return cached.averageLevel
        }

        val averageLevel = runCatching {
            val pcm = WavReader.readPcm16MonoSamples(file)
            AudioEnvelope.averageSmoothedAbsolute(
                input = pcm.samples,
                sampleRate = pcm.sampleRate
            )
        }.getOrNull()
        averageLevelCache[key] = CachedAverageLevel(file.lastModified(), averageLevel)
        return averageLevel
    }

    private fun averageLevel(sum: Double, count: Int): Int? {
        if (count == 0) return null
        return (sum / count.toDouble()).roundToInt()
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

    private data class CachedAverageLevel(
        val lastModified: Long,
        val averageLevel: Float?
    )
}
