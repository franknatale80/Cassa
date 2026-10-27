package it.francesconatale.cassa.sms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import it.francesconatale.cassa.MainActivity
import it.francesconatale.cassa.R
import java.math.BigDecimal

class SmsPaymentReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RECORD_SMS_PAYMENT = "it.francesconatale.cassa.ACTION_RECORD_SMS_PAYMENT"
        const val EXTRA_AMOUNT_CENTS = "EXTRA_AMOUNT_CENTS"
        const val EXTRA_NOTE = "EXTRA_NOTE"
        const val EXTRA_SUGGESTED_CATEGORY = "EXTRA_SUGGESTED_CATEGORY"
        const val EXTRA_RAW_MESSAGE = "EXTRA_RAW_MESSAGE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!SmsCaptureManager.isSmsCaptureEnabled(context)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val fullBody = messages.joinToString("") { it.displayMessageBody ?: "" }
        if (fullBody.isBlank()) return

        val parsed = FinecoSmsParser.parse(fullBody) ?: return

        SmsCaptureManager.ensureNotificationChannel(context)

        val targetIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_RECORD_SMS_PAYMENT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_AMOUNT_CENTS, parsed.amountCents)
            putExtra(EXTRA_NOTE, parsed.merchant)
            putExtra(EXTRA_SUGGESTED_CATEGORY, parsed.suggestedCategory)
            putExtra(EXTRA_RAW_MESSAGE, parsed.rawMessage)
        }

        val notificationId = (System.currentTimeMillis() % 100000).toInt()

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val euro = BigDecimal(parsed.amountCents).movePointLeft(2).toPlainString().replace('.', ',') + " €"

        val notification = NotificationCompat.Builder(context, SmsCaptureManager.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Pagamento rilevato: $euro")
            .setContentText("${parsed.merchant} · Tocca per registrare su Cassa")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${parsed.merchant}\nImporto: $euro\nCategoria suggerita: ${parsed.suggestedCategory}\nTocca per confermare e salvare su Cassa."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permesso POST_NOTIFICATIONS non concesso
        }
    }
}
