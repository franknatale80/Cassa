package it.francesconatale.cassa.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object SmsCaptureManager {

    private const val PREFS_NAME = "cassa_sms_prefs"
    private const val KEY_ENABLED = "sms_capture_enabled"
    const val CHANNEL_ID = "cassa_payment_detected"

    fun isSmsCaptureEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, true)
    }

    fun setSmsCaptureEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pagamenti rilevati",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifiche per la registrazione rapida dei pagamenti ricevuti via SMS"
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }
}
