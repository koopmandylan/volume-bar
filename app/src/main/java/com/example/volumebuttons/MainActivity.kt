package com.example.volumebuttons

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.example.volumebuttons.FloatingVolumeService.Companion.ACTION_UPDATE
import com.example.volumebuttons.FloatingVolumeService.Companion.DEFAULT_OPACITY
import com.example.volumebuttons.FloatingVolumeService.Companion.KEY_HAPTICS
import com.example.volumebuttons.FloatingVolumeService.Companion.KEY_LAST_SEEN
import com.example.volumebuttons.FloatingVolumeService.Companion.KEY_MODE
import com.example.volumebuttons.FloatingVolumeService.Companion.KEY_OPACITY
import com.example.volumebuttons.FloatingVolumeService.Companion.KEY_THEME
import com.example.volumebuttons.FloatingVolumeService.Companion.PREFS
import com.example.volumebuttons.FloatingVolumeService.Companion.UPDATE_URL
import com.example.volumebuttons.FloatingVolumeService.Companion.isRunning
import com.example.volumebuttons.FloatingVolumeService.Companion.prefsOf

class MainActivity : AppCompatActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Ignored: service still runs, notification just stays hidden on 13+.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val prefs = prefsOf(this)

        // ---------- Mode ----------
        val modeGroup = findViewById<RadioGroup>(R.id.modeGroup)
        if (prefs.getString(KEY_MODE, "bar") == "buttons") {
            modeGroup.check(R.id.modeButtons)
        } else {
            modeGroup.check(R.id.modeBar)
        }
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.edit().putString(
                KEY_MODE, if (checkedId == R.id.modeButtons) "buttons" else "bar"
            ).apply()
            pushServiceUpdate()
        }

        // ---------- Opacity (0..100, survives upgrades) ----------
        val opacitySeek = findViewById<SeekBar>(R.id.opacitySeek)
        val opacityValue = findViewById<TextView>(R.id.opacityValue)
        opacitySeek.max = 100
        opacitySeek.progress = prefs.getInt(KEY_OPACITY, DEFAULT_OPACITY)
        opacityValue.text = "${opacitySeek.progress}%"
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                opacityValue.text = "$progress%"
                prefs.edit().putInt(KEY_OPACITY, progress).apply()
                pushServiceUpdate()
            }

            override fun onStartTrackingTouch(seek: SeekBar?) = Unit
            override fun onStopTrackingTouch(seek: SeekBar?) = Unit
        })

        // ---------- Haptics ----------
        val hapticsSwitch =
            findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
                R.id.hapticsSwitch
            )
        hapticsSwitch.isChecked = prefs.getBoolean(KEY_HAPTICS, true)
        hapticsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_HAPTICS, checked).apply()
        }

        // ---------- Theme ----------
        val themeGroup = findViewById<RadioGroup>(R.id.themeGroup)
        when (prefs.getString(KEY_THEME, "dark")) {
            "light" -> themeGroup.check(R.id.themeLight)
            "system" -> themeGroup.check(R.id.themeSystem)
            else -> themeGroup.check(R.id.themeDark)
        }
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.themeLight -> "light"
                R.id.themeSystem -> "system"
                else -> "dark"
            }
            prefs.edit().putString(KEY_THEME, mode).apply()
            applySavedTheme()
        }

        // ---------- Actions ----------
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow Display over other apps", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                startService()
            }
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, FloatingVolumeService::class.java))
        }

        findViewById<Button>(R.id.btnChangelog).setOnClickListener {
            startActivity(Intent(this, ChangelogActivity::class.java))
        }

        findViewById<Button>(R.id.btnUpdate).setOnClickListener {
            runUpdateCheck(manual = true)
        }
        runUpdateCheck(manual = false)

        maybeShowWhatsNew()
    }

    private fun startService() {
        val svc = Intent(this, FloatingVolumeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
    }

    private fun pushServiceUpdate() {
        if (!isRunning) return
        val update = Intent(this, FloatingVolumeService::class.java)
            .setAction(ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(update)
        } else {
            startService(update)
        }
    }

    private fun applySavedTheme() {
        when (prefsOf(this).getString(KEY_THEME, "dark")) {
            "light" -> AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_NO)
            "system" -> AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            else -> AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    private fun maybeShowWhatsNew() {
        val prefs = prefsOf(this)
        val current = try {
            val pm = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pm.longVersionCode.toInt()
            else {
                @Suppress("DEPRECATION") pm.versionCode
            }
        } catch (_: Exception) {
            return
        }
        val seen = prefs.getInt(KEY_LAST_SEEN, 0)
        if (seen != 0 && current > seen) {
            val latest = Changelogs.load(this).maxByOrNull { it.code }
            if (latest != null) {
                AlertDialog.Builder(this)
                    .setTitle("Updated to v${latest.version}")
                    .setMessage(latest.notes)
                    .setPositiveButton("Nice", null)
                    .setNeutralButton("Full changelog") { _, _ ->
                        startActivity(Intent(this, ChangelogActivity::class.java))
                    }
                    .show()
            }
        }
        prefs.edit().putInt(KEY_LAST_SEEN, current).apply()
    }

    private fun runUpdateCheck(manual: Boolean) {
        UpdateChecker.checkForUpdate(this, UPDATE_URL) { result ->
            runOnUiThread {
                when (result) {
                    is UpdateChecker.CheckResult.Available -> {
                        val info = result.info
                        AlertDialog.Builder(this)
                            .setTitle("Update ${info.versionName} available")
                            .setMessage(info.notes.ifBlank { "New version ready to install." })
                            .setPositiveButton("Download & install") { _, _ ->
                                UpdateChecker.downloadAndInstall(this, info)
                            }
                            .setNegativeButton("Later", null)
                            .show()
                    }
                    is UpdateChecker.CheckResult.Latest -> {
                        if (manual) {
                            Toast.makeText(
                                this,
                                "You're on the latest version",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    is UpdateChecker.CheckResult.Unreachable -> {
                        if (manual) {
                            Toast.makeText(
                                this,
                                "Couldn't reach the update server - check your connection",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }
}
