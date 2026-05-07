package com.yacbridge.vpn.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.yacbridge.vpn.R
import com.yacbridge.vpn.databinding.ActivitySettingsBinding
import com.yacbridge.vpn.model.TunnelConfig

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Настройки"

        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Set defaults
            val defaults = TunnelConfig()
            preferenceManager.sharedPreferences?.let { prefs ->
                if (!prefs.contains("gateway_url")) {
                    prefs.edit().putString("gateway_url", defaults.gatewayUrl).apply()
                }
                if (!prefs.contains("auth_token")) {
                    prefs.edit().putString("auth_token", defaults.authToken).apply()
                }
            }
        }
    }
}
