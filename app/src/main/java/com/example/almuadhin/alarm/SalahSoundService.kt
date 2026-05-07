package com.example.almuadhin.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.almuadhin.R
import com.example.almuadhin.data.SettingsRepository
import com.example.almuadhin.data.UserSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class SalahSoundService : Service() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private var salahPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    companion object {
        private const val CHANNEL_ID = "salah_channel"
        private const val NOTIFICATION_ID = 2
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings: UserSettings = runBlocking {
            settingsRepository.settingsFlow.first()
        }

        // لو الصلاة على النبي معطلة — وقف فورًا
        if (!settings.salahEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        // تشغيل كـ Foreground Service عشان ميتوقفش
        startForeground(NOTIFICATION_ID, buildNotification())

        val soundResId: Int = settings.salahSound.resId

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // طلب Audio Focus
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .build()
            focusRequest = req
            audioManager?.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }

        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        salahPlayer?.release()
        salahPlayer = MediaPlayer.create(this, soundResId)
        salahPlayer?.start()
        salahPlayer?.setOnCompletionListener {
            releaseAudioFocus()
            it.release()
            salahPlayer = null
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        // إنشاء Channel للأندرويد 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "الصلاة على النبي",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("الصلاة على النبي ﷺ")
            .setContentText("جاري تشغيل الصلاة على النبي...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseAudioFocus()
        salahPlayer?.release()
        salahPlayer = null
        super.onDestroy()
    }
}
