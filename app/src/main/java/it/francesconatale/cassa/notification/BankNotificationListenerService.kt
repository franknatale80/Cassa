package it.francesconatale.cassa.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import it.francesconatale.cassa.MainActivity
import it.francesconatale.cassa.R
import java.math.BigDecimal

class BankNotificationListenerService : NotificationListenerService() {

    companion object {
        const val ACTION_RECORD_PUSH_PAYMENT = "it.francesconatale.cassa.ACTION_RECORD_PUSH_PAYMENT"
        const val EXTRA_AMOUNT_CENTS = "EXTRA_AMOUNT_CENTS"
        const val EXTRA_NOTE = "EXTRA_NOTE"
        const val EXTRA_TRANSACTION_TYPE = "EXTRA_TRANSACTION_TYPE"
        const val EXTRA_SUGGESTED_CATEGORY = "EXTRA_SUGGESTED_CATEGORY"
        const val EXTRA_RAW_MESSAGE = "EXTRA_RAW_MESSAGE"

        // Cache degli ultimi hash per evitare notifiche duplicate ravvicinate
        private val recentNotificationHashes = LinkedHashMap<String, Long>()
        private const val DEDUPLICATION_WINDOW_MS = 15_000L // 15 secondi
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val context = applicationContext
        if (!NotificationCaptureManager.isNotificationCaptureEnabled(context)) return

        val pkg = sbn.packageName ?: return
        // Ignora le notifiche emesse da Cassa stessa
        if (pkg == context.packageName) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Estrai titolo e testo (gestendo anche BigText e SubText)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        val parsed = BankNotificationParser.parse(pkg, title, text) ?: return

        // Deduplicazione rapida: evita popup duplicati se l'app bancaria aggiorna la notifica più volte
        val hashKey = "${parsed.packageName}_${parsed.amountCents}_${parsed.merchantOrTitle}_${parsed.transactionType}"
        val now = System.currentTimeMillis()
        synchronized(recentNotificationHashes) {
            val lastSeen = recentNotificationHashes[hashKey]
            if (lastSeen != null && (now - lastSeen) < DEDUPLICATION_WINDOW_MS) {
                return
            }
            recentNotificationHashes[hashKey] = now
            if (recentNotificationHashes.size > 50) {
                val oldest = recentNotificationHashes.keys.first()
                recentNotificationHashes.remove(oldest)
            }
        }

        NotificationCaptureManager.ensureNotificationChannel(context)

        val targetIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_RECORD_PUSH_PAYMENT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_AMOUNT_CENTS, parsed.amountCents)
            putExtra(EXTRA_NOTE, parsed.merchantOrTitle)
            putExtra(EXTRA_TRANSACTION_TYPE, parsed.transactionType)
            putExtra(EXTRA_SUGGESTED_CATEGORY, parsed.suggestedCategory)
            putExtra(EXTRA_RAW_MESSAGE, parsed.rawText)
        }

        val notificationId = (System.currentTimeMillis() % 100000).toInt()

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val euro = BigDecimal(parsed.amountCents).movePointLeft(2).toPlainString().replace('.', ',') + " €"
        val isExpense = parsed.transactionType == "expense"
        val titlePrefix = if (isExpense) "Pagamento rilevato: " else "Entrata rilevata: "
        val sign = if (isExpense) "− $euro" else "+ $euro"

        val summaryNotification = NotificationCompat.Builder(context, NotificationCaptureManager.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$titlePrefix$euro")
            .setContentText("${parsed.merchantOrTitle} · Tocca per registrare su Cassa")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "${parsed.merchantOrTitle}\nImporto: $sign\nCategoria suggerita: ${parsed.suggestedCategory}\nTocca per confermare e salvare su Cassa."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, summaryNotification)
        } catch (_: SecurityException) {
            // Permesso POST_NOTIFICATIONS non concesso
        }
    }
}
