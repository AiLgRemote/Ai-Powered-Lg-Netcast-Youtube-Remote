package com.tatilacratita.lgcast.sampler

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tatilacratita.lgcast.sampler.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager

    override fun attachBaseContext(newBase: Context) {
        settingsManager = SettingsManager(newBase)
        val updatedContext = settingsManager.applyLanguage(newBase, settingsManager.getLanguage())
        super.attachBaseContext(updatedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager(this)

        setupToolbar()
        setupThemeSelector()
        setupLanguageSelector()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }



    private fun setupThemeSelector() {
        when (settingsManager.getTheme()) {
            SettingsManager.THEME_LIGHT -> binding.radioLight.isChecked = true
            SettingsManager.THEME_DARK -> binding.radioDark.isChecked = true
            else -> binding.radioSystem.isChecked = true
        }

        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val newTheme = when (checkedId) {
                R.id.radioLight -> SettingsManager.THEME_LIGHT
                R.id.radioDark -> SettingsManager.THEME_DARK
                else -> SettingsManager.THEME_SYSTEM
            }
            settingsManager.setTheme(newTheme)
        }
    }

    private fun setupLanguageSelector() {
        when (settingsManager.getLanguage()) {
            SettingsManager.LANG_ROMANIAN -> binding.radioRomanian.isChecked = true
            SettingsManager.LANG_ENGLISH -> binding.radioEnglish.isChecked = true
            else -> binding.radioLanguageSystem.isChecked = true
        }

        binding.radioGroupLanguage.setOnCheckedChangeListener { _, checkedId ->
            val newLanguage = when (checkedId) {
                R.id.radioRomanian -> SettingsManager.LANG_ROMANIAN
                R.id.radioEnglish -> SettingsManager.LANG_ENGLISH
                else -> SettingsManager.LANG_SYSTEM
            }
            if (settingsManager.getLanguage() != newLanguage) {
                settingsManager.setLanguage(newLanguage)
                // Changed MainActivity to RemoteControlActivity
                setResult(RemoteControlActivity.RESULT_CODE_LANGUAGE_CHANGED)
                recreate()
            }
        }
    }
}