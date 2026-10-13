package com.example.volumebuttons

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object UpdateChecker {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String
    )

    /** Null = no update available OR server unreachable (keep it simple). */
    fun checkForUpdate(context: Context, updateUrl: String, cb: (UpdateInfo?) -> Unit) {
        thread {
            try {
                val conn = (URL(updateUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val j = JSONObject(body)
                val info = UpdateInfo(
                    j.getInt("versionCode"),
                    j.optString("versionName", "?"),
                    j.getString("apkUrl"),
                    j.optString("notes", "")
                )
                val pm = context.packageManager.getPackageInfo(context.packageName, 0)
                val current = if (Build.VERSION.SDK_INT >= 28) {
                    pm.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION") pm.versionCode
                }
                cb(if (info.versionCode > current) info else null)
            } catch (_: Exception) {
                cb(null)
            }
        }
    }

    fun downloadAndInstall(activity: Activity, info: UpdateInfo) {
        val file = File(
            activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "volume-update.apk"
        )
        if (file.exists()) file.delete()

        val dm = activity.getSystemService(DownloadManager::class.java)
            ?: return
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("Volume bar update")
                .setDestinationUri(Uri.fromFile(file))
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE
                )
        )
        Toast.makeText(activity, "Downloading update...", Toast.LENGTH_SHORT).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                try {
                    activity.unregisterReceiver(this)
                } catch (_: Exception) {
                }
                installApk(activity, file)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(receiver, filter)
        }
    }

    private fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        context.startActivity(install)
    }
}
