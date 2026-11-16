package io.github.marchio11.heatlogger

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, SettingsFragment()).commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            val numeric = listOf(
                "still_minutes", "heartbeat_minutes", "interval_vehicle", "interval_bicycle", "interval_running",
                "interval_walking", "interval_unknown", "vehicle_kmh", "drive_minutes", "qs_minutes",
            )
            numeric.forEach { key ->
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            }
            findPreference<EditTextPreference>("qs_url")?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            // Il token non si mostra mai per intero
            findPreference<EditTextPreference>("qs_token")?.apply {
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                    val t = p.text?.trim().orEmpty()
                    if (t.isEmpty()) "Non impostato" else "••••••" + t.takeLast(4)
                }
            }
        }
    }
}
