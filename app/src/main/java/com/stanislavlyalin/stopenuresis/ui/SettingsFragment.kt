package com.stanislavlyalin.stopenuresis.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.stanislavlyalin.stopenuresis.AppSettings
import com.stanislavlyalin.stopenuresis.AppStorage
import com.stanislavlyalin.stopenuresis.R
import com.stanislavlyalin.stopenuresis.training.LogisticRegressionModel
import org.json.JSONObject

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var seekBarCooldown: SeekBar
    private lateinit var tvCooldownValue: TextView
    private lateinit var switchDarkTheme: Switch
    private lateinit var seekBarRustlingThresholdExceedance: SeekBar
    private lateinit var tvRustlingThresholdExceedanceValue: TextView
    private lateinit var seekBarAlarmRustlingDetectionsPerMinute: SeekBar
    private lateinit var tvAlarmRustlingDetectionsPerMinuteValue: TextView
    private lateinit var btnExportModel: Button
    private lateinit var btnImportModel: Button

    private val importModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importModel(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        seekBarCooldown = view.findViewById(R.id.seekBarCooldown)
        tvCooldownValue = view.findViewById(R.id.tvCooldownValue)
        switchDarkTheme = view.findViewById(R.id.switchDarkTheme)
        seekBarRustlingThresholdExceedance =
            view.findViewById(R.id.seekBarRustlingThresholdExceedance)
        tvRustlingThresholdExceedanceValue =
            view.findViewById(R.id.tvRustlingThresholdExceedanceValue)
        seekBarAlarmRustlingDetectionsPerMinute =
            view.findViewById(R.id.seekBarAlarmRustlingDetectionsPerMinute)
        tvAlarmRustlingDetectionsPerMinuteValue =
            view.findViewById(R.id.tvAlarmRustlingDetectionsPerMinuteValue)
        btnExportModel = view.findViewById(R.id.btnExportModel)
        btnImportModel = view.findViewById(R.id.btnImportModel)

        setupDarkThemeSetting()
        setupCooldownSetting()
        setupRustlingThresholdExceedanceSetting()
        setupAlarmRustlingDetectionsPerMinuteSetting()
        setupModelTransferButtons()
    }

    private fun setupDarkThemeSetting() {
        switchDarkTheme.isChecked = AppSettings.isDarkThemeEnabled(requireContext())
        switchDarkTheme.setOnCheckedChangeListener { _, checked ->
            AppSettings.setDarkThemeEnabled(requireContext(), checked)
            AppCompatDelegate.setDefaultNightMode(
                if (checked) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
            )
        }
    }

    private fun setupCooldownSetting() {
        seekBarCooldown.max = 6
        seekBarCooldown.progress = AppSettings.getCooldownHours(requireContext())
        updateCooldownValue(seekBarCooldown.progress)

        seekBarCooldown.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    updateCooldownValue(progress)
                    if (fromUser) {
                        AppSettings.setCooldownHours(requireContext(), progress)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
    }

    private fun setupRustlingThresholdExceedanceSetting() {
        seekBarRustlingThresholdExceedance.max = 100
        seekBarRustlingThresholdExceedance.progress =
            AppSettings.getRustlingThresholdExceedancePercent(requireContext())
        updateRustlingThresholdExceedanceValue(seekBarRustlingThresholdExceedance.progress)

        seekBarRustlingThresholdExceedance.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    updateRustlingThresholdExceedanceValue(progress)
                    if (fromUser) {
                        AppSettings.setRustlingThresholdExceedancePercent(
                            requireContext(),
                            progress
                        )
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
    }

    private fun updateRustlingThresholdExceedanceValue(value: Int) {
        tvRustlingThresholdExceedanceValue.text =
            getString(R.string.percentValue, value)
    }

    private fun setupAlarmRustlingDetectionsPerMinuteSetting() {
        seekBarAlarmRustlingDetectionsPerMinute.max = 11
        val value = AppSettings.getAlarmRustlingDetectionsPerMinute(requireContext())
        seekBarAlarmRustlingDetectionsPerMinute.progress = value - 1
        updateAlarmRustlingDetectionsPerMinuteValue(value)

        seekBarAlarmRustlingDetectionsPerMinute.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val detectionsPerMinute = progress + 1
                    updateAlarmRustlingDetectionsPerMinuteValue(detectionsPerMinute)
                    if (fromUser) {
                        AppSettings.setAlarmRustlingDetectionsPerMinute(
                            requireContext(),
                            detectionsPerMinute
                        )
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
    }

    private fun updateAlarmRustlingDetectionsPerMinuteValue(value: Int) {
        tvAlarmRustlingDetectionsPerMinuteValue.text =
            getString(R.string.integerValue, value)
    }

    private fun updateCooldownValue(value: Int) {
        tvCooldownValue.text = when (value) {
            0 -> "0 часов"
            1 -> "1 час"
            in 2..4 -> "$value часа"
            else -> "$value часов"
        }
    }

    private fun setupModelTransferButtons() {
        btnExportModel.setOnClickListener { exportModel() }
        btnImportModel.setOnClickListener {
            importModelLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }
    }

    private fun exportModel() {
        val modelFile = AppStorage.modelFile(requireContext())
        if (!modelFile.exists()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.modelExportNoModel),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val uri = runCatching {
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                modelFile
            )
        }.getOrElse {
            Toast.makeText(
                requireContext(),
                getString(R.string.modelExportFailed),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.shareModelChooserTitle)))
    }

    private fun importModel(uri: Uri) {
        val imported = runCatching {
            val jsonText = requireContext().contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("Cannot open model file")
            LogisticRegressionModel.fromJson(JSONObject(jsonText))

            val modelFile = AppStorage.modelFile(requireContext())
            modelFile.parentFile?.mkdirs()
            modelFile.writeText(JSONObject(jsonText).toString(2))
        }.isSuccess

        Toast.makeText(
            requireContext(),
            getString(
                if (imported) {
                    R.string.modelImportSuccess
                } else {
                    R.string.modelImportFailed
                }
            ),
            Toast.LENGTH_SHORT
        ).show()
    }
}
