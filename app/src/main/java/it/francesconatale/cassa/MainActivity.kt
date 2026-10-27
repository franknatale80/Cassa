package it.francesconatale.cassa

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import it.francesconatale.cassa.data.CassaDatabase
import it.francesconatale.cassa.data.TransactionJsonEncoder
import it.francesconatale.cassa.data.TransactionRepository
import it.francesconatale.cassa.security.BiometricSecurityManager
import it.francesconatale.cassa.security.LockScreen

class MainActivity : FragmentActivity() {
    private val isUnlocked = mutableStateOf(true)
    private var promptShowing = false
    private val pendingSmsDraft = mutableStateOf<it.francesconatale.cassa.sms.SmsPaymentDraft?>(null)

    companion object {
        private var isWaitingForActivityResult = false
        private var lastActivityLaunchTime = 0L

        fun markActivityLaunching() {
            isWaitingForActivityResult = true
            lastActivityLaunchTime = System.currentTimeMillis()
        }

        fun markActivityResultHandled() {
            isWaitingForActivityResult = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val database = CassaDatabase.getInstance(applicationContext)
        val repository = TransactionRepository(database, TransactionJsonEncoder())

        val biometricEnabled = BiometricSecurityManager.isBiometricEnabled(this)
        isUnlocked.value = !biometricEnabled

        setContent {
            Box(Modifier.fillMaxSize()) {
                CassaApp(
                    dao = database.cassaDao(),
                    repository = repository,
                    smsDraft = pendingSmsDraft.value,
                    onClearSmsDraft = { pendingSmsDraft.value = null }
                )
                if (!isUnlocked.value && BiometricSecurityManager.isBiometricEnabled(this@MainActivity)) {
                    LockScreen(onUnlockClick = { requestBiometricUnlock() })
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent == null) return
        when (intent.action) {
            it.francesconatale.cassa.sms.SmsPaymentReceiver.ACTION_RECORD_SMS_PAYMENT -> {
                val amount = intent.getLongExtra(it.francesconatale.cassa.sms.SmsPaymentReceiver.EXTRA_AMOUNT_CENTS, 0L)
                val note = intent.getStringExtra(it.francesconatale.cassa.sms.SmsPaymentReceiver.EXTRA_NOTE).orEmpty()
                val category = intent.getStringExtra(it.francesconatale.cassa.sms.SmsPaymentReceiver.EXTRA_SUGGESTED_CATEGORY).orEmpty()
                val raw = intent.getStringExtra(it.francesconatale.cassa.sms.SmsPaymentReceiver.EXTRA_RAW_MESSAGE).orEmpty()
                if (amount > 0) {
                    pendingSmsDraft.value = it.francesconatale.cassa.sms.SmsPaymentDraft(amount, note, category, raw, "expense")
                }
            }
            it.francesconatale.cassa.notification.BankNotificationListenerService.ACTION_RECORD_PUSH_PAYMENT -> {
                val amount = intent.getLongExtra(it.francesconatale.cassa.notification.BankNotificationListenerService.EXTRA_AMOUNT_CENTS, 0L)
                val note = intent.getStringExtra(it.francesconatale.cassa.notification.BankNotificationListenerService.EXTRA_NOTE).orEmpty()
                val category = intent.getStringExtra(it.francesconatale.cassa.notification.BankNotificationListenerService.EXTRA_SUGGESTED_CATEGORY).orEmpty()
                val txType = intent.getStringExtra(it.francesconatale.cassa.notification.BankNotificationListenerService.EXTRA_TRANSACTION_TYPE) ?: "expense"
                val raw = intent.getStringExtra(it.francesconatale.cassa.notification.BankNotificationListenerService.EXTRA_RAW_MESSAGE).orEmpty()
                if (amount > 0) {
                    pendingSmsDraft.value = it.francesconatale.cassa.sms.SmsPaymentDraft(amount, note, category, raw, txType)
                }
            }
        }
    }

    private fun requestBiometricUnlock() {
        if (promptShowing) return
        promptShowing = true
        BiometricSecurityManager.authenticate(
            activity = this,
            title = "Sblocca Cassa",
            subtitle = "Usa l'impronta digitale per accedere",
            onSuccess = {
                promptShowing = false
                isUnlocked.value = true
            },
            onError = { _, _ ->
                promptShowing = false
            }
        )
    }

    override fun onResume() {
        super.onResume()
        val elapsed = System.currentTimeMillis() - lastActivityLaunchTime
        if (isWaitingForActivityResult && elapsed > 180_000) {
            isWaitingForActivityResult = false
        }
        if (BiometricSecurityManager.isBiometricEnabled(this)) {
            if (!isUnlocked.value) {
                requestBiometricUnlock()
            }
        } else {
            isUnlocked.value = true
        }
    }

    override fun onStop() {
        if (BiometricSecurityManager.isBiometricEnabled(this)) {
            val elapsed = System.currentTimeMillis() - lastActivityLaunchTime
            if (isWaitingForActivityResult && elapsed < 180_000) {
                // Attesa completamento Activity esterna (fotocamera, galleria, visualizzatore)
            } else {
                isUnlocked.value = false
            }
        }
        super.onStop()
    }
}
