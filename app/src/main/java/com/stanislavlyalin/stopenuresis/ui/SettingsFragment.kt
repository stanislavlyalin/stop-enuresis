package com.stanislavlyalin.stopenuresis.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import com.google.android.material.slider.Slider
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.stanislavlyalin.stopenuresis.AppSettings
import com.stanislavlyalin.stopenuresis.AppStorage
import com.stanislavlyalin.stopenuresis.BuildConfig
import com.stanislavlyalin.stopenuresis.R
import com.stanislavlyalin.stopenuresis.training.ExtraTreesModel
import org.json.JSONObject

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var seekBarCooldown: Slider
    private lateinit var tvCooldownValue: TextView
    private lateinit var themeChoice: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var tvAlarmRustlingEpisodesValue: TextView
    private lateinit var tvConfirmationPeriodValue: TextView
    private lateinit var tvAppVersion: TextView
    private lateinit var btnExportModel: View
    private lateinit var btnImportModel: View

    private val importModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importModel(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        seekBarCooldown = view.findViewById(R.id.seekBarCooldown)
        tvCooldownValue = view.findViewById(R.id.tvCooldownValue)
        themeChoice = view.findViewById(R.id.themeChoice)
        tvAlarmRustlingEpisodesValue = view.findViewById(R.id.tvAlarmRustlingEpisodesValue)
        tvConfirmationPeriodValue = view.findViewById(R.id.tvConfirmationPeriodValue)
        tvAppVersion = view.findViewById(R.id.tvAppVersion)
        btnExportModel = view.findViewById(R.id.btnExportModel)
        btnImportModel = view.findViewById(R.id.btnImportModel)

        // Одинаковая ширина обоих полей учитывает максимальное значение и масштаб шрифта.
        val valueWidth = kotlin.math.ceil(tvConfirmationPeriodValue.paint.measureText("10 мин") +
            8 * resources.displayMetrics.density).toInt()
        listOf(tvAlarmRustlingEpisodesValue, tvConfirmationPeriodValue).forEach {
            it.layoutParams = it.layoutParams.apply { width = valueWidth }
        }
        setupDarkThemeSetting()
        setupCooldownSetting()
        setupAlarmRustlingEpisodesSetting()
        setupConfirmationPeriodSetting()
        setupModelTransferButtons()
        tvAppVersion.text = getString(R.string.appVersion, BuildConfig.GIT_COMMIT)
    }

    private fun setupDarkThemeSetting() {
        val ids = listOf(R.id.themeSystem, R.id.themeLight, R.id.themeDark)
        themeChoice.check(ids[AppSettings.getThemeMode(requireContext()).ordinal])
        themeChoice.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                AppSettings.setThemeMode(requireContext(), AppSettings.ThemeMode.entries[ids.indexOf(id)])
                AppCompatDelegate.setDefaultNightMode(AppSettings.nightMode(requireContext()))
            }
        }
    }

    private fun setupCooldownSetting() {
        seekBarCooldown.valueTo = (AppSettings.MAX_PROTECTION_INTERVAL_MINUTES /
            AppSettings.PROTECTION_INTERVAL_STEP_MINUTES - 1).toFloat()
        seekBarCooldown.value = (AppSettings.getProtectionIntervalMinutes(requireContext()) /
            AppSettings.PROTECTION_INTERVAL_STEP_MINUTES - 1).toFloat()
        updateCooldownValue(seekBarCooldown.value.toInt())
        seekBarCooldown.setLabelFormatter { cooldownText(it.toInt()) }
        seekBarCooldown.addOnChangeListener { _, value, fromUser ->
            updateCooldownValue(value.toInt())
            if (fromUser) AppSettings.setProtectionIntervalMinutes(
                requireContext(), (value.toInt() + 1) * AppSettings.PROTECTION_INTERVAL_STEP_MINUTES
            )
        }
    }

    private fun setupAlarmRustlingEpisodesSetting() {
        setupStepper(R.id.episodesMinus, R.id.episodesPlus, 1, 12,
            { AppSettings.getAlarmRustlingEpisodes(requireContext()) },
            { AppSettings.setAlarmRustlingEpisodes(requireContext(), it) },
            { tvAlarmRustlingEpisodesValue.text = it.toString() })
    }

    private fun setupConfirmationPeriodSetting() {
        setupStepper(R.id.periodMinus, R.id.periodPlus, 1, 10,
            { AppSettings.getConfirmationPeriodMinutes(requireContext()) },
            { AppSettings.setConfirmationPeriodMinutes(requireContext(), it) },
            { tvConfirmationPeriodValue.text = getString(R.string.confirmationPeriodValue, it) })
    }

    private fun setupStepper(minusId: Int, plusId: Int, min: Int, max: Int,
        read: () -> Int, save: (Int) -> Unit, display: (Int) -> Unit) {
        val minus = requireView().findViewById<Button>(minusId)
        val plus = requireView().findViewById<Button>(plusId)
        fun update(value: Int) {
            display(value)
            minus.isEnabled = value > min
            plus.isEnabled = value < max
        }
        minus.setOnClickListener { val value = (read() - 1).coerceIn(min, max); save(value); update(value) }
        plus.setOnClickListener { val value = (read() + 1).coerceIn(min, max); save(value); update(value) }
        update(read())
    }

    private fun updateCooldownValue(progress: Int) {
        tvCooldownValue.text = cooldownText(progress)
    }

    private fun cooldownText(progress: Int): String {
        val minutes = (progress + 1) * AppSettings.PROTECTION_INTERVAL_STEP_MINUTES
        return when (minutes) {
            30 -> "30 мин"
            60 -> "1 час"
            else -> "${if (minutes % 60 == 0) (minutes / 60).toString() else (minutes / 60.0).toString()} часа"
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
        val modelCanBeExported = runCatching {
            ExtraTreesModel.fromJson(JSONObject(modelFile.readText()))
        }.isSuccess
        if (!modelCanBeExported) {
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
            ExtraTreesModel.fromJson(JSONObject(jsonText))

            val modelFile = AppStorage.modelFile(requireContext())
            modelFile.parentFile?.mkdirs()
            modelFile.writeText(JSONObject(jsonText).toString(2))
        }.isSuccess

        if (imported) (activity as? com.stanislavlyalin.stopenuresis.MainActivity)?.refreshNavigationAvailability()
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
