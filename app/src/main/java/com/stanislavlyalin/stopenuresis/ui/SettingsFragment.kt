package com.stanislavlyalin.stopenuresis.ui

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.stanislavlyalin.stopenuresis.AppSettings
import com.stanislavlyalin.stopenuresis.R

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var seekBarCooldown: SeekBar
    private lateinit var tvCooldownValue: TextView
    private lateinit var switchDarkTheme: Switch
    private lateinit var seekBarRustlingThresholdExceedance: SeekBar
    private lateinit var tvRustlingThresholdExceedanceValue: TextView
    private lateinit var seekBarAlarmRustlingDetectionsPerMinute: SeekBar
    private lateinit var tvAlarmRustlingDetectionsPerMinuteValue: TextView

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

        setupDarkThemeSetting()
        setupCooldownSetting()
        setupRustlingThresholdExceedanceSetting()
        setupAlarmRustlingDetectionsPerMinuteSetting()
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
}
