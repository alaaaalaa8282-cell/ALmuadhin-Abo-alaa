package com.AbuAlaa.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.AbuAlaa.R
import com.AbuAlaa.data.AdhanSound
import com.AbuAlaa.ui.screens.AzanFullscreenActivity

class AzanSoundService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val EXTRA_ADHAN_SOUND = "azan_sound"
        const val EXTRA_PRAYER_NAME = "prayer_name"
        const val EXTRA_NOTIF_ID    = "notif_id"
        const val EXTRA_VOLUME      = "azan_volume"
        const val ACTION_STOP       = "com.AbuAlaa.STOP_AZAN"
        const val NOTIF_ID          = 8001
        const val CHANNEL_ID        = "azan_service_channel"
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stopSelf()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                mediaPlayer?.setVolume(0.2f, 0.2f)
            AudioManager.AUDIOFOCUS_GAIN ->
                mediaPlayer?.setVolume(1f, 1f)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val adhanSoundName = intent?.getStringExtra(EXTRA_ADHAN_SOUND) ?: AdhanSound.MAKKAH.name
        val prayerName     = intent?.getStringExtra(EXTRA_PRAYER_NAME) ?: "الصلاة"
        val notifId        = intent?.getIntExtra(EXTRA_NOTIF_ID, 1001) ?: 1001
        val volume         = intent?.getFloatExtra(EXTRA_VOLUME, 1f) ?: 1f

        val adhanSound = try { AdhanSound.valueOf(adhanSoundName) }
                         catch (e: Exception) { AdhanSound.MAKKAH }

        // WakeLock عشان الأذان ما يتوقفش
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "com.AbuAlaa:AzanWakeLock"
        ).also { it.acquire(15 * 60 * 1000L) }

        startForeground(NOTIF_ID, buildNotification(prayerName, notifId))

        // فتح شاشة الأذان
        startActivity(
            Intent(this, AzanFullscreenActivity::class.java).apply {
                putExtra("prayer_name", prayerName)
                putExtra("notif_id", notifId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
            }
        )

        // AudioFocus
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val granted = requestFocus()
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            releaseWakeLock()
            stopSelf()
            return START_NOT_STICKY
        }

        // تشغيل الأذان
        val logVolume = if (volume == 0f) 0f
            else (1 - (Math.log((1 + (1 - volume) * 99).toDouble()) / Math.log(100.0))).toFloat()

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(this, adhanSound.resId)?.apply {
            setVolume(logVolume, logVolume)
            setOnCompletionListener {
                sendBroadcast(Intent("com.AbuAlaa.ATHAN_COMPLETE").setPackage(packageName))
                AzanMediaPlayer.player = null
                releaseAll()
                stopSelf()
            }
            setOnErrorListener { _, _, _ ->
                releaseAll()
                stopSelf()
                true
            }
            start()
            AzanMediaPlayer.player = this
        }

        return START_NOT_STICKY
    }

    private fun requestFocus(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            audioManager?.requestAudioFocus(req) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
    }

    private fun buildNotification(prayerName: String, notifId: Int): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, AzanSoundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("أذان $prayerName")
            .setContentText("اضغط إيقاف لوقف الأذان")
            .setSmallIcon(R.drawable.ic_notification)
            .addAction(0, "إيقاف", stopPi)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "خدمة الأذان",
                        NotificationManager.IMPORTANCE_HIGH).apply {
                        setSound(null, null)
                        enableVibration(false)
                    }
                )
            }
        }
    }

    private fun releaseAll() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(focusListener)
        }
        releaseWakeLock()
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        AzanMediaPlayer.player = null
        releaseAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
