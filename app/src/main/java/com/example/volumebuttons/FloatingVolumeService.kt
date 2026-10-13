package com.example.volumebuttons

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.max
import kotlin.math.min

class FloatingVolumeService : Service() {

    companion object {
        const val ACTION_UPDATE_OPACITY = "com.example.volumebuttons.UPDATE_OPACITY"
        const val PREFS = "dock_prefs"
        const val KEY_OPACITY = "opacity" // 0..100, 0 = invisible but still tappable
        const val DEFAULT_OPACITY = 85

        @Volatile
        var isRunning = false

        fun opacityOf(context: Context): Float {
            val pct = context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_OPACITY, DEFAULT_OPACITY).coerceIn(0, 100)
            return pct / 100f
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var dock: Button
    private var params: WindowManager.LayoutParams? = null

    // Touch-strip size in px (48dp x 160dp), used for edge snap math.
    private var stripW = 0
    private var stripH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_OPACITY && ::floatingView.isInitialized) {
            floatingView.alpha = opacityOf(this)
        }
        return START_STICKY
    }

    private fun screenSize(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()

        val channelId = "vol_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Volume dock",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Volume bar active")
            .setContentText("Tap bar for volume slider. Long-press to hide.")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .build()
        startForeground(1, notif)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val density = resources.displayMetrics.density
        stripW = (48 * density).toInt()
        stripH = (160 * density).toInt()

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_overlay, null)
        dock = floatingView.findViewById(R.id.btnDock)
        floatingView.alpha = opacityOf(this)

        val metrics = screenSize()
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Start docked at the right edge, vertically centered.
            x = max(metrics.widthPixels - stripW, 0)
            y = max(metrics.heightPixels / 2 - stripH / 2, 0)
        }

        windowManager.addView(floatingView, params)
        isRunning = true

        // Slim bar: tap = system volume slider, drag = move (snaps to
        // nearest edge on release), long-press = hide.
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        val handler = Handler(Looper.getMainLooper())

        var downRawX = 0
        var downRawY = 0
        var initX = 0
        var initY = 0
        var dragging = false
        var longPressed = false

        val longPressTask = Runnable {
            if (!dragging) {
                longPressed = true
                dock.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                Toast.makeText(this, "Bar hidden", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }

        // Listener on the bar itself: the Button consumes touches on the
        // visible strip, so a listener on the transparent container would
        // never see taps landing on the bar.
        dock.setOnTouchListener { v, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX.toInt()
                    downRawY = e.rawY.toInt()
                    initX = p.x
                    initY = p.y
                    dragging = false
                    longPressed = false
                    handler.postDelayed(longPressTask, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX.toInt() - downRawX
                    val dy = e.rawY.toInt() - downRawY
                    if (!dragging && dx * dx + dy * dy >= slop * slop) {
                        dragging = true
                        handler.removeCallbacks(longPressTask)
                    }
                    if (dragging) {
                        p.x = initX + dx
                        p.y = initY + dy
                        clampToScreen(p)
                        windowManager.updateViewLayout(floatingView, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressTask)
                    if (dragging) {
                        snapToEdge(p)
                        windowManager.updateViewLayout(floatingView, p)
                    } else if (!longPressed) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        // Show the system volume slider without changing volume.
                        audio.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_SAME,
                            AudioManager.FLAG_SHOW_UI
                        )
                    }
                    dragging = false
                    longPressed = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressTask)
                    dragging = false
                    longPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private fun clampToScreen(p: WindowManager.LayoutParams) {
        val metrics = screenSize()
        val w = floatingView.width.takeIf { it > 0 } ?: stripW
        val h = floatingView.height.takeIf { it > 0 } ?: stripH
        p.x = min(max(p.x, 0), max(metrics.widthPixels - w, 0))
        p.y = min(max(p.y, 0), max(metrics.heightPixels - h, 0))
    }

    private fun snapToEdge(p: WindowManager.LayoutParams) {
        val metrics = screenSize()
        val w = floatingView.width.takeIf { it > 0 } ?: stripW
        val centerX = p.x + w / 2
        p.x = if (centerX < metrics.widthPixels / 2) 0
        else max(metrics.widthPixels - w, 0)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation changes screen dimensions: absolute x/y would leave
        // the bar floating mid-screen, so re-clamp and re-dock it.
        val p = params ?: return
        if (!::floatingView.isInitialized) return
        clampToScreen(p)
        snapToEdge(p)
        try {
            windowManager.updateViewLayout(floatingView, p)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        isRunning = false
        try {
            windowManager.removeView(floatingView)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
