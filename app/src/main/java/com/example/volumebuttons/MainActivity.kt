package com.example.volumebuttons

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        // Static file: no server to maintain, works over any network.
        const val UPDATE_URL =
            "https://raw.githubusercontent.com/koopmandylan/volume-bar/main/version.json"
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Ignored: service still runs, notification just stays hidden on 13+.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val prefs = getSharedPreferences(
            FloatingVolumeService.PREFS, MODE_PRIVATE
        )
        val opacitySeek = findViewById<SeekBar>(R.id.opacitySeek)
        val opacityValue = findViewById<TextView>(R.id.opacityValue)

        // SeekBar range 30..100.
        opacitySeek.max = 70
        opacitySeek.progress =
            prefs.getInt(FloatingVolumeService.KEY_OPACITY,
                FloatingVolumeService.DEFAULT_OPACITY) - 30
        opacityValue.text = "${opacitySeek.progress + 30}%"

        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress + 30
                opacityValue.text = "$pct%"
                prefs.edit().putInt(FloatingVolumeService.KEY_OPACITY, pct).apply()
                // Live-update the visible bar, if any.
                if (FloatingVolumeService.isRunning) {
                    val update = Intent(
                        this@MainActivity,
                        FloatingVolumeService::class.java
                    ).setAction(FloatingVolumeService.ACTION_UPDATE_OPACITY)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(update)
                    } else {
                        startService(update)
                    }
                }
            }

            override fun onStartTrackingTouch(seek: SeekBar?) = Unit
            override fun onStopTrackingTouch(seek: SeekBar?) = Unit
        })

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow Display over other apps", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                val svc = Intent(this, FloatingVolumeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc)
                } else {
                    startService(svc)
                }
            }
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, FloatingVolumeService::class.java))
        }

        findViewById<Button>(R.id.btnUpdate).setOnClickListener {
            runUpdateCheck(manual = true)
        }
        // Silent check on launch; only speaks up if an update exists.
        runUpdateCheck(manual = false)
    }

    private fun runUpdateCheck(manual: Boolean) {
        UpdateChecker.checkForUpdate(this, UPDATE_URL) { info ->
            runOnUiThread {
                if (info == null) {
                    if (manual) {
                        Toast.makeText(
                            this,
                            "No update (or server unreachable)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("Update ${info.versionName} available")
                    .setMessage(info.notes.ifBlank { "New version ready to install." })
                    .setPositiveButton("Download & install") { _, _ ->
                        UpdateChecker.downloadAndInstall(this, info)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }
}
