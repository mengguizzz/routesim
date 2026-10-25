package com.opensource.routesim

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.opensource.routesim.core.SimulationLogger
import com.opensource.routesim.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAboutBinding
    private var updatingTheme = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val lightBars = !isNightModeActive()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }

        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.aboutRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }

        binding.btnAboutBack.setOnClickListener { finish() }
        binding.btnGithub.setOnClickListener {
            val repository = getString(R.string.github_repository_url)
            SimulationLogger.event("about_open_repository", "url" to repository)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repository)))
        }

        binding.tvAboutVersion.text = getString(
            R.string.version_format,
            getVersionName(),
        )
        setupThemeSelector()
        SimulationLogger.event("about_opened")
    }

    private fun setupThemeSelector() {
        updatingTheme = true
        binding.themeGroup.check(
            when (RoutesimApplication.loadThemeMode(this)) {
                AppCompatDelegate.MODE_NIGHT_NO -> R.id.btnThemeLight
                AppCompatDelegate.MODE_NIGHT_YES -> R.id.btnThemeDark
                else -> R.id.btnThemeSystem
            },
        )
        updatingTheme = false
        updateThemeButtonStyles()

        binding.themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingTheme) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.btnThemeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            SimulationLogger.event("theme_changed", "mode" to mode)
            RoutesimApplication.setThemeMode(mode)
        }
    }

    private fun updateThemeButtonStyles() {
        binding.themeGroup.children.filterIsInstance<MaterialButton>().forEach { button ->
            val selected = button.id == selectedThemeButtonId()
            button.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.route_primary_soft else R.color.route_surface_elevated,
                ),
            )
            button.strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.route_primary else R.color.route_outline,
                ),
            )
            button.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.route_primary_dark else R.color.route_ink,
                ),
            )
        }
    }

    private fun selectedThemeButtonId(): Int =
        when (RoutesimApplication.loadThemeMode(this)) {
            AppCompatDelegate.MODE_NIGHT_NO -> R.id.btnThemeLight
            AppCompatDelegate.MODE_NIGHT_YES -> R.id.btnThemeDark
            else -> R.id.btnThemeSystem
        }

    private fun getVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "dev"

    private fun isNightModeActive(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
}
