package com.stanislavlyalin.stopenuresis.ui

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.stanislavlyalin.stopenuresis.AppSettings
import com.stanislavlyalin.stopenuresis.R

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var seekBarRustlingThresholdExceedance: SeekBar
    private lateinit var tvRustlingThresholdExceedanceValue: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        seekBarRustlingThresholdExceedance =
            view.findViewById(R.id.seekBarRustlingThresholdExceedance)
        tvRustlingThresholdExceedanceValue =
            view.findViewById(R.id.tvRustlingThresholdExceedanceValue)

        setupRustlingThresholdExceedanceSetting()
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
}
