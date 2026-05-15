package com.stanislavlyalin.stopenuresis

import android.content.Context
import android.os.Environment
import java.io.File

object AppStorage {

    private const val APP_DIRECTORY = "StopEnurez"
    private const val SAMPLES_DIRECTORY = "samples"
    private const val MODEL_DIRECTORY = "model"
    private const val MODEL_FILE_NAME = "stopenuresis_model.json"

    fun migrateLegacyData(context: Context) {
        migrateDirectory(
            from = File(context.filesDir, SAMPLES_DIRECTORY),
            to = samplesDir(context)
        )
        migrateDirectory(
            from = File(context.filesDir, MODEL_DIRECTORY),
            to = modelDir(context)
        )
    }

    fun samplesDir(context: Context): File {
        return File(rootDir(context), SAMPLES_DIRECTORY).also { it.mkdirs() }
    }

    fun modelFile(context: Context): File {
        return File(modelDir(context), MODEL_FILE_NAME)
    }

    private fun modelDir(context: Context): File {
        return File(rootDir(context), MODEL_DIRECTORY).also { it.mkdirs() }
    }

    private fun rootDir(context: Context): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            APP_DIRECTORY
        )
        if (ensureWritableDirectory(publicDir)) return publicDir

        return File(context.filesDir, APP_DIRECTORY).also { it.mkdirs() }
    }

    private fun ensureWritableDirectory(directory: File): Boolean {
        return runCatching {
            if (!directory.exists() && !directory.mkdirs()) return false
            val probe = File(directory, ".write_test")
            probe.writeText("")
            probe.delete()
            true
        }.getOrDefault(false)
    }

    private fun migrateDirectory(from: File, to: File) {
        if (!from.exists() || from.absolutePath == to.absolutePath) return
        to.mkdirs()
        from.listFiles()?.forEach { source ->
            val target = File(to, source.name)
            when {
                source.isDirectory -> migrateDirectory(source, target)
                source.isFile && !target.exists() -> runCatching {
                    source.copyTo(target, overwrite = false)
                }
            }
        }
    }
}
