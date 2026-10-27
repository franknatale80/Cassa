package it.francesconatale.cassa.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BankNotificationParserTest {

    @Test
    fun testGoogleWalletSumUpNotification() {
        val title = "SumUp  *M   P SERVICE"
        val text = "4,20 € con MPS Debit Mastercard ••4843"
        val pkg = BankNotificationParser.PKG_GOOGLE_WALLET

        val parsed = BankNotificationParser.parse(pkg, title, text)
        assertNotNull(parsed)
        assertEquals(420L, parsed!!.amountCents)
        assertEquals("SumUp *M P SERVICE", parsed.merchantOrTitle)
        assertEquals("expense", parsed.transactionType)
        assertEquals("Ristoranti e bar", parsed.suggestedCategory)
    }

    @Test
    fun testBmpsStipendioNotification() {
        val title = "Accredito Emolumenti"
        val text = "Hai ricevuto l'accredito dello stipendio di 1.507,06 Euro sul tuo Conto 49795"
        val pkg = BankNotificationParser.PKG_BMPS

        val parsed = BankNotificationParser.parse(pkg, title, text)
        assertNotNull(parsed)
        assertEquals(150_706L, parsed!!.amountCents)
        assertEquals("Accredito Stipendio", parsed.merchantOrTitle)
        assertEquals("income", parsed.transactionType)
        assertEquals("Stipendio", parsed.suggestedCategory)
    }

    @Test
    fun testBmpsSmallStipendioNotification() {
        val title = "Accredito Emolumenti"
        val text = "Hai ricevuto l'accredito dello stipendio di 100,64 Euro sul tuo Conto 49795"
        val pkg = BankNotificationParser.PKG_BMPS

        val parsed = BankNotificationParser.parse(pkg, title, text)
        assertNotNull(parsed)
        assertEquals(10_064L, parsed!!.amountCents)
        assertEquals("income", parsed.transactionType)
        assertEquals("Accredito Stipendio", parsed.merchantOrTitle)
    }

    @Test
    fun testBmpsPosExpenseNotification() {
        val title = "Pagamento POS"
        val text = "Pagamento di 35,50 Euro presso ENI STATION con carta"
        val pkg = BankNotificationParser.PKG_BMPS

        val parsed = BankNotificationParser.parse(pkg, title, text)
        assertNotNull(parsed)
        assertEquals(3550L, parsed!!.amountCents)
        assertEquals("expense", parsed.transactionType)
    }

    @Test
    fun testFinecoPushNotification() {
        val title = "Fineco"
        val text = "Autorizzato pagamento di 45,00 EUR per CONAD SUPERSTORE"
        val pkg = BankNotificationParser.PKG_FINECO

        val parsed = BankNotificationParser.parse(pkg, title, text)
        assertNotNull(parsed)
        assertEquals(4500L, parsed!!.amountCents)
        assertEquals("CONAD SUPERSTORE", parsed.merchantOrTitle)
        assertEquals("expense", parsed.transactionType)
        assertEquals("Alimentari", parsed.suggestedCategory)
    }

    @Test
    fun testPayPalPushNotification() {
        val title = "PayPal"
        val text = "Hai pagato 12,99 € a Netflix"
        val pkg = BankNotificationParser.PKG_PAYPAL

        val parsed = BankNotificationParser.parse(pkg, title, text)
        assertNotNull(parsed)
        assertEquals(1299L, parsed!!.amountCents)
        assertEquals("Netflix", parsed.merchantOrTitle)
        assertEquals("expense", parsed.transactionType)
    }

    @Test
    fun testUnrelatedNotificationIgnored() {
        val parsed = BankNotificationParser.parse("org.telegram.messenger", "Maria", "Ciao come stai?")
        assertNull(parsed)
    }
}
