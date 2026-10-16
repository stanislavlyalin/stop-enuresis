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
import com.stanislavlyalin.stopenuresis.BuildConfig
import com.stanislavlyalin.stopenuresis.R
import com.stanislavlyalin.stopenuresis.training.ExtraTreesModel
import org.json.JSONObject

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var seekBarListeningStartProtection: SeekBar
    private lateinit var tvListeningStartProtectionValue: TextView
    private lateinit var seekBarCooldown: SeekBar
    private lateinit var tvCooldownValue: TextView
    private lateinit var switchDarkTheme: Switch
    private lateinit var seekBarAlarmRustlingEpisodes: SeekBar
    private lateinit var tvAlarmRustlingEpisodesValue: TextView
    private lateinit var seekBarConfirmationPeriod: SeekBar
    private lateinit var tvConfirmationPeriodValue: TextView
    private lateinit var tvAppVersion: TextView
    private lateinit var btnExportModel: Button
    private lateinit var btnImportModel: Button

    private val importModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importModel(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        seekBarListeningStartProtection = view.findViewById(R.id.seekBarListeningStartProtection)
        tvListeningStartProtectionValue = view.findViewById(R.id.tvListeningStartProtectionValue)
        seekBarCooldown = view.findViewById(R.id.seekBarCooldown)
        tvCooldownValue = view.findViewById(R.id.tvCooldownValue)
        switchDarkTheme = view.findViewById(R.id.switchDarkTheme)
        seekBarAlarmRustlingEpisodes = view.findViewById(R.id.seekBarAlarmRustlingEpisodes)
        tvAlarmRustlingEpisodesValue = view.findViewById(R.id.tvAlarmRustlingEpisodesValue)
        seekBarConfirmationPeriod = view.findViewById(R.id.seekBarConfirmationPeriod)
        tvConfirmationPeriodValue = view.findViewById(R.id.tvConfirmationPeriodValue)
        tvAppVersion = view.findViewById(R.id.tvAppVersion)
        btnExportModel = view.findViewById(R.id.btnExportModel)
        btnImportModel = view.findViewById(R.id.btnImportModel)

        setupDarkThemeSetting()
        setupListeningStartProtectionSetting()
        setupCooldownSetting()
        setupAlarmRustlingEpisodesSetting()
        setupConfirmationPeriodSetting()
        setupModelTransferButtons()
        tvAppVersion.text = getString(R.string.appVersion, BuildConfig.GIT_COMMIT)
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

    private fun setupListeningStartProtectionSetting() {
        seekBarListeningStartProtection.max = 6
        seekBarListeningStartProtection.progress =
            AppSettings.getListeningStartProtectionHours(requireContext())
        updateListeningStartProtectionValue(seekBarListeningStartProtection.progress)

        seekBarListeningStartProtection.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    updateListeningStartProtectionValue(progress)
                    if (fromUser) {
                        AppSettings.setListeningStartProtectionHours(requireContext(), progress)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
    }

    private fun setupAlarmRustlingEpisodesSetting() {
        seekBarAlarmRustlingEpisodes.max = 11
        val value = AppSettings.getAlarmRustlingEpisodes(requireContext())
        seekBarAlarmRustlingEpisodes.progress = value - 1
        updateAlarmRustlingEpisodesValue(value)

        seekBarAlarmRustlingEpisodes.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val episodes = progress + 1
                    updateAlarmRustlingEpisodesValue(episodes)
                    if (fromUser) {
                        AppSettings.setAlarmRustlingEpisodes(requireContext(), episodes)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
    }

    private fun updateAlarmRustlingEpisodesValue(value: Int) {
        tvAlarmRustlingEpisodesValue.text = getString(R.string.integerValue, value)
    }

    private fun setupConfirmationPeriodSetting() {
        seekBarConfirmationPeriod.max = 9
        val minutes = AppSettings.getConfirmationPeriodMinutes(requireContext())
        seekBarConfirmationPeriod.progress = minutes - 1
        tvConfirmationPeriodValue.text = getString(R.string.confirmationPeriodValue, minutes)
        seekBarConfirmationPeriod.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + 1
                    tvConfirmationPeriodValue.text =
                        getString(R.string.confirmationPeriodValue, value)
                    if (fromUser) AppSettings.setConfirmationPeriodMinutes(requireContext(), value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
    }

    private fun updateCooldownValue(value: Int) {
        tvCooldownValue.text = formatHours(value)
    }

    private fun updateListeningStartProtectionValue(value: Int) {
        tvListeningStartProtectionValue.text = formatHours(value)
    }

    private fun formatHours(value: Int): String {
        return when (value) {
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
