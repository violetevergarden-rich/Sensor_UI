package com.example.sensormonitor

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.DialogFragment
import com.example.sensormonitor.model.AltitudeUnit
import com.example.sensormonitor.model.AppSettings
import com.example.sensormonitor.model.SpeedUnit
import com.example.sensormonitor.model.UiRefreshRate

class SettingsDialogFragment : DialogFragment() {

    interface Listener {
        fun onSettingsUpdated(settings: AppSettings)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        val chartInput = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.settings_chart_window_hint)
            setText(arguments?.getInt(ARG_CURRENT_WINDOW)?.toString() ?: getString(R.string.settings_chart_window_default))
        }

        val sampleInput = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.settings_sample_interval_hint)
            setText(arguments?.getInt(ARG_SAMPLE_INTERVAL)?.toString() ?: getString(R.string.settings_sample_interval_default))
        }

        val recordInput = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.settings_record_interval_hint)
            setText(arguments?.getInt(ARG_RECORD_INTERVAL)?.toString() ?: getString(R.string.settings_record_interval_default))
        }

        val alphaInput = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.settings_filter_alpha_hint)
            setText(arguments?.getFloat(ARG_ALPHA)?.toString() ?: getString(R.string.settings_filter_alpha_default))
        }

        val autoSaveSwitch = SwitchCompat(context).apply {
            text = getString(R.string.settings_auto_save_csv)
            isChecked = arguments?.getBoolean(ARG_AUTO_SAVE) ?: false
        }

        val keepScreenOnSwitch = SwitchCompat(context).apply {
            text = getString(R.string.settings_keep_screen_on)
            isChecked = arguments?.getBoolean(ARG_KEEP_SCREEN_ON) ?: true
        }

        val speedUnitSwitch = SwitchCompat(context).apply {
            text = getString(R.string.settings_speed_unit_kmh)
            isChecked = (arguments?.getString(ARG_SPEED_UNIT) ?: SpeedUnit.MS.name) == SpeedUnit.KMH.name
        }

        val altitudeUnitSwitch = SwitchCompat(context).apply {
            text = getString(R.string.settings_altitude_unit_ft)
            isChecked = (arguments?.getString(ARG_ALTITUDE_UNIT) ?: AltitudeUnit.METER.name) == AltitudeUnit.FEET.name
        }

        val smoothingSwitch = SwitchCompat(context).apply {
            text = getString(R.string.settings_lowpass_enabled)
            isChecked = arguments?.getBoolean(ARG_SMOOTHING_ENABLED) ?: false
        }

        val refreshRateSwitch = SwitchCompat(context).apply {
            text = getString(R.string.settings_refresh_rate_30hz)
            isChecked = (arguments?.getString(ARG_REFRESH_RATE) ?: UiRefreshRate.HZ_10.name) == UiRefreshRate.HZ_30.name
        }

        val languageSwitch = SwitchCompat(context).apply {
            text = getString(R.string.settings_language_english)
            isChecked = (arguments?.getString(ARG_LANGUAGE_CODE) ?: "zh") == "en"
        }

        val uploadSwitch = SwitchCompat(context).apply {
            text = getString(R.string.settings_upload_enabled)
            isChecked = arguments?.getBoolean(ARG_UPLOAD_ENABLED) ?: false
        }

        val serverUrlInput = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.settings_server_url_hint)
            setText(arguments?.getString(ARG_SERVER_URL) ?: getString(R.string.settings_server_url_default))
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = resources.getDimensionPixelSize(R.dimen.settings_dialog_padding)
            setPadding(padding, padding, padding, padding)

            addView(TextView(context).apply { text = getString(R.string.settings_chart_window) })
            addView(chartInput)

            addView(TextView(context).apply { text = getString(R.string.settings_sample_interval) })
            addView(sampleInput)

            addView(autoSaveSwitch)
            addView(TextView(context).apply { text = getString(R.string.settings_record_interval) })
            addView(recordInput)

            addView(keepScreenOnSwitch)
            addView(speedUnitSwitch)
            addView(altitudeUnitSwitch)

            addView(smoothingSwitch)
            addView(TextView(context).apply { text = getString(R.string.settings_filter_alpha) })
            addView(alphaInput)

            addView(refreshRateSwitch)
            addView(languageSwitch)

            addView(uploadSwitch)
            addView(TextView(context).apply { text = getString(R.string.settings_server_url) })
            addView(serverUrlInput)
        }

        val container = ScrollView(context).apply {
            addView(content)
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.settings_apply) { _, _ ->
                val points = (chartInput.text.toString().toIntOrNull()
                    ?: getString(R.string.settings_chart_window_default).toInt()).coerceAtLeast(30)
                val sample = (sampleInput.text.toString().toIntOrNull()
                    ?: getString(R.string.settings_sample_interval_default).toInt()).coerceIn(50, 2000)
                val record = (recordInput.text.toString().toIntOrNull()
                    ?: getString(R.string.settings_record_interval_default).toInt()).coerceIn(100, 60000)
                val alpha = (alphaInput.text.toString().toFloatOrNull()
                    ?: getString(R.string.settings_filter_alpha_default).toFloat()).coerceIn(0.01f, 0.99f)
                val language = if (languageSwitch.isChecked) "en" else "zh"
                val settings = AppSettings(
                    chartWindowSize = points,
                    sampleIntervalMs = sample,
                    autoSaveCsv = autoSaveSwitch.isChecked,
                    recordIntervalMs = record,
                    languageCode = language,
                    keepScreenOn = keepScreenOnSwitch.isChecked,
                    speedUnit = if (speedUnitSwitch.isChecked) SpeedUnit.KMH else SpeedUnit.MS,
                    altitudeUnit = if (altitudeUnitSwitch.isChecked) AltitudeUnit.FEET else AltitudeUnit.METER,
                    smoothingEnabled = smoothingSwitch.isChecked,
                    smoothingAlpha = alpha,
                    uiRefreshRate = if (refreshRateSwitch.isChecked) UiRefreshRate.HZ_30 else UiRefreshRate.HZ_10,
                    uploadEnabled = uploadSwitch.isChecked,
                    serverUrl = serverUrlInput.text.toString().ifBlank { getString(R.string.settings_server_url_default) },
                )
                (activity as? Listener)?.onSettingsUpdated(settings)
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .create()
    }

    companion object {
        private const val ARG_CURRENT_WINDOW = "arg_current_window"
        private const val ARG_SAMPLE_INTERVAL = "arg_sample_interval"
        private const val ARG_RECORD_INTERVAL = "arg_record_interval"
        private const val ARG_LANGUAGE_CODE = "arg_language_code"
        private const val ARG_AUTO_SAVE = "arg_auto_save"
        private const val ARG_KEEP_SCREEN_ON = "arg_keep_screen_on"
        private const val ARG_SPEED_UNIT = "arg_speed_unit"
        private const val ARG_ALTITUDE_UNIT = "arg_altitude_unit"
        private const val ARG_SMOOTHING_ENABLED = "arg_smoothing_enabled"
        private const val ARG_ALPHA = "arg_alpha"
        private const val ARG_REFRESH_RATE = "arg_refresh_rate"
        private const val ARG_UPLOAD_ENABLED = "arg_upload_enabled"
        private const val ARG_SERVER_URL = "arg_server_url"

        fun newInstance(settings: AppSettings): SettingsDialogFragment {
            val fragment = SettingsDialogFragment()
            fragment.arguments = Bundle().apply {
                putInt(ARG_CURRENT_WINDOW, settings.chartWindowSize)
                putInt(ARG_SAMPLE_INTERVAL, settings.sampleIntervalMs)
                putInt(ARG_RECORD_INTERVAL, settings.recordIntervalMs)
                putString(ARG_LANGUAGE_CODE, settings.languageCode)
                putBoolean(ARG_AUTO_SAVE, settings.autoSaveCsv)
                putBoolean(ARG_KEEP_SCREEN_ON, settings.keepScreenOn)
                putString(ARG_SPEED_UNIT, settings.speedUnit.name)
                putString(ARG_ALTITUDE_UNIT, settings.altitudeUnit.name)
                putBoolean(ARG_SMOOTHING_ENABLED, settings.smoothingEnabled)
                putFloat(ARG_ALPHA, settings.smoothingAlpha)
                putString(ARG_REFRESH_RATE, settings.uiRefreshRate.name)
                putBoolean(ARG_UPLOAD_ENABLED, settings.uploadEnabled)
                putString(ARG_SERVER_URL, settings.serverUrl)
            }
            return fragment
        }
    }
}
