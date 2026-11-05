package com.stanislavlyalin.stopenuresis.training

import com.stanislavlyalin.stopenuresis.audio.AudioEnvelope
import java.io.File
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

data class TrainingDatasetStats(
    val positiveCount: Int,
    val negativeCount: Int,
    val positiveLevelRange: IntRange? = null,
    val negativeLevelRange: IntRange? = null
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
    private val acousticFeatureExtractor = AcousticFeatureExtractor()
    private val trainer = ExtraTreesTrainer()
    private val sampleLevelCache = mutableMapOf<String, CachedSampleLevel>()

    fun getDatasetStats(): TrainingDatasetStats {
        val files = listSampleFiles()
        var positiveCount = 0
        var negativeCount = 0
        val positiveLevels = mutableListOf<Float>()
        val negativeLevels = mutableListOf<Float>()

        files.forEach { file ->
            when (getStatsLabel(file)) {
                1 -> {
                    positiveCount++
                    readSampleLevel(file)?.let { level -> positiveLevels.add(level) }
                }

                0 -> {
                    negativeCount++
                    readSampleLevel(file)?.let { level -> negativeLevels.add(level) }
                }
            }
        }

        return TrainingDatasetStats(
            positiveCount = positiveCount,
            negativeCount = negativeCount,
            positiveLevelRange = centralLevelRange(positiveLevels),
            negativeLevelRange = centralLevelRange(negativeLevels)
        )
    }

    fun hasModel(): Boolean = readModel() != null

    fun readModelAccuracy(): Double? {
        return readModel()?.accuracy
    }

    fun train(
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

        val positiveSplit = splitClassSamples(positives, random)
        val negativeSplit = splitClassSamples(negatives, random)
        val trainSet = (positiveSplit.first + negativeSplit.first).shuffled(random)
        val testSet = (positiveSplit.second + negativeSplit.second).shuffled(random)
        val totalFeatureFiles = trainSet.size + testSet.size
        var processedFeatureFiles = 0

        fun extractFeatures(file: File): DoubleArray {
            val features = acousticFeatureExtractor.extractFeatures(WavReader.readPcm16Mono(file))
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

    private fun readModel(): ExtraTreesModel? {
        if (!modelFile.exists()) return null
        return runCatching {
            ExtraTreesModel.fromJson(JSONObject(modelFile.readText()))
        }.getOrNull()
    }

    fun clearDataset() {
        listSampleFiles().forEach { it.delete() }
        sampleLevelCache.clear()
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
            name.endsWith("_1.wav") -> 1
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

    private fun readSampleLevel(file: File): Float? {
        val key = file.absolutePath
        val cached = sampleLevelCache[key]
        if (cached != null && cached.lastModified == file.lastModified()) {
            return cached.level
        }

        val level = runCatching {
            val pcm = WavReader.readPcm16MonoSamples(file)
            AudioEnvelope.averageSmoothedAbsolute(
                input = pcm.samples,
                sampleRate = pcm.sampleRate
            )
        }.getOrNull()
        sampleLevelCache[key] = CachedSampleLevel(file.lastModified(), level)
        return level
    }

    private fun centralLevelRange(levels: List<Float>): IntRange? {
        if (levels.isEmpty()) return null

        val sortedLevels = levels.sorted()
        val lowerLevel = percentile(sortedLevels, LEVEL_RANGE_LOWER_PERCENTILE).roundToInt()
        val upperLevel = percentile(sortedLevels, LEVEL_RANGE_UPPER_PERCENTILE).roundToInt()
        return lowerLevel..upperLevel
    }

    private fun percentile(sortedLevels: List<Float>, percentile: Double): Float {
        val position = (sortedLevels.lastIndex * percentile)
            .coerceIn(0.0, sortedLevels.lastIndex.toDouble())
        val lowerIndex = position.toInt()
        val upperIndex = ceil(position).toInt()
        if (lowerIndex == upperIndex) return sortedLevels[lowerIndex]

        val upperWeight = position - lowerIndex.toDouble()
        val lowerWeight = 1.0 - upperWeight
        return (
            sortedLevels[lowerIndex].toDouble() * lowerWeight +
                sortedLevels[upperIndex].toDouble() * upperWeight
            ).toFloat()
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
        const val LEVEL_RANGE_LOWER_PERCENTILE = 0.05
        const val LEVEL_RANGE_UPPER_PERCENTILE = 0.95
    }

    private data class CachedSampleLevel(
        val lastModified: Long,
        val level: Float?
    )
}
