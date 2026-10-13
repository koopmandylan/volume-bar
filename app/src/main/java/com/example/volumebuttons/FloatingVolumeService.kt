package com.example.volumebuttons

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
        const val ACTION_UPDATE = "com.example.volumebuttons.UPDATE"

        // Self-update feed: static file on GitHub, https only.
        const val UPDATE_URL =
            "https://raw.githubusercontent.com/koopmandylan/volume-bar/main/version.json"

        // Single prefs file + stable keys: Android keeps app data on
        // update, so settings survive every future version.
        const val PREFS = "dock_prefs"
        const val KEY_OPACITY = "opacity" // 0..100, 0 = invisible but tappable
        const val KEY_MODE = "mode" // "bar" or "buttons"
        const val KEY_HAPTICS = "haptics"
        const val KEY_THEME = "theme" // "system", "dark", "light"
        const val KEY_LAST_SEEN = "last_seen_code"
        const val DEFAULT_OPACITY = 85

        @Volatile
        var isRunning = false

        fun prefsOf(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, MODE_PRIVATE)

        fun opacityOf(context: Context): Float =
            prefsOf(context).getInt(KEY_OPACITY, DEFAULT_OPACITY)
                .coerceIn(0, 100) / 100f

        fun hapticsOf(context: Context): Boolean =
            prefsOf(context).getBoolean(KEY_HAPTICS, true)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private var params: WindowManager.LayoutParams? = null
    private lateinit var audio: AudioManager
    private var currentMode = ""
    private var stripW = 0
    private var stripH = 0

    private var isMuted = false
    private var savedMusicVolume = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE && ::windowManager.isInitialized) {
            ensureOverlay()
            if (::floatingView.isInitialized) {
                floatingView.alpha = opacityOf(this)
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val p = params ?: return
        if (!::floatingView.isInitialized) return
        clampToScreen(p)
        snapToEdge(p)
        try {
            windowManager.updateViewLayout(floatingView, p)
        } catch (_: Exception) {
        }
    }

    private fun screenSize(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics
    }

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
            .setContentText("Tap for volume slider. Long-press to hide.")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .build()
        startForeground(1, notif)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val density = resources.displayMetrics.density
        stripW = (48 * density).toInt()
        stripH = (160 * density).toInt()

        val metrics = screenSize()
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = max(metrics.widthPixels - stripW, 0)
            y = max(metrics.heightPixels / 2 - stripH / 2, 0)
        }

        ensureOverlay()
        isRunning = true
    }

    /** Builds (or rebuilds on mode change) the overlay. Keeps position. */
    private fun ensureOverlay() {
        val p = params ?: return
        val mode = prefsOf(this).getString(KEY_MODE, "bar") ?: "bar"
        if (::floatingView.isInitialized && mode == currentMode) return
        if (::floatingView.isInitialized) {
            try {
                windowManager.removeView(floatingView)
            } catch (_: Exception) {
            }
        }
        isMuted = false
        floatingView = LayoutInflater.from(this).inflate(
            if (mode == "buttons") R.layout.floating_buttons
            else R.layout.floating_overlay,
            null
        )
        floatingView.alpha = opacityOf(this)
        windowManager.addView(floatingView, p)
        currentMode = mode
        if (mode == "buttons") setupButtons() else setupBar()
    }

    private fun tick(v: View, strong: Boolean = false) {
        if (!hapticsOf(this)) return
        v.performHapticFeedback(
            if (strong) HapticFeedbackConstants.LONG_PRESS
            else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    private fun showSlider() {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_SAME,
            AudioManager.FLAG_SHOW_UI
        )
    }

    // ---------- Edge-bar mode ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBar() {
        val dock: Button = floatingView.findViewById(R.id.btnDock)
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
                tick(dock, strong = true)
                Toast.makeText(this, "Bar hidden", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }

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
                        tick(v)
                        showSlider()
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

    // ---------- Buttons mode ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupButtons() {
        val btnMute: Button = floatingView.findViewById(R.id.btnMute)

        floatingView.findViewById<Button>(R.id.btnVolUp).setOnClickListener {
            tick(it)
            isMuted = false
            btnMute.text = "MUTE"
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI
            )
        }

        floatingView.findViewById<Button>(R.id.btnVolDown).setOnClickListener {
            tick(it)
            isMuted = false
            btnMute.text = "MUTE"
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
        }

        btnMute.setOnClickListener {
            tick(it)
            if (!isMuted) {
                savedMusicVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (savedMusicVolume == 0) {
                    savedMusicVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2
                }
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
                isMuted = true
                btnMute.text = "UNMUTE"
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_UNMUTE,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                if (savedMusicVolume > 0) {
                    audio.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        savedMusicVolume,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                isMuted = false
                btnMute.text = "MUTE"
            }
        }

        floatingView.findViewById<Button>(R.id.btnClose).setOnClickListener {
            tick(it)
            stopSelf()
        }

        // Drag from the pill background; buttons keep their own taps.
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        floatingView.setOnTouchListener(object : View.OnTouchListener {
            var downRawX = 0
            var downRawY = 0
            var initX = 0
            var initY = 0
            var dragging = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val p = params ?: return false
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = e.rawX.toInt()
                        downRawY = e.rawY.toInt()
                        initX = p.x
                        initY = p.y
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX.toInt() - downRawX
                        val dy = e.rawY.toInt() - downRawY
                        if (!dragging && dx * dx + dy * dy < slop * slop) {
                            return true
                        }
                        dragging = true
                        p.x = initX + dx
                        p.y = initY + dy
                        clampToScreen(p)
                        windowManager.updateViewLayout(floatingView, p)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasDrag = dragging
                        dragging = false
                        return wasDrag
                    }
                }
                return false
            }
        })
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

    override fun onDestroy() {
        isRunning = false
        try {
            if (::floatingView.isInitialized) windowManager.removeView(floatingView)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
