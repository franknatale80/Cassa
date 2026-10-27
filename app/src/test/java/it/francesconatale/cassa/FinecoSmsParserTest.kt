package it.francesconatale.cassa

import it.francesconatale.cassa.sms.FinecoSmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FinecoSmsParserTest {

    @Test
    fun parseExactFinecoSms() {
        val sms = "Fineco - SMS Gratuito: Autorizzato utilizzo Carta ***6988 Importo: 40,0 EUR, per: STAZIONE ESSO MANGONE A. Info: 0228992899"
        val parsed = FinecoSmsParser.parse(sms)
        assertNotNull(parsed)
        assertEquals(4000L, parsed!!.amountCents)
        assertEquals("STAZIONE ESSO MANGONE A", parsed.merchant)
        assertEquals("Auto e trasporti", parsed.suggestedCategory)
    }

    @Test
    fun parseFinecoTwoDecimalsAndBar() {
        val sms = "Fineco - SMS Gratuito: Autorizzato utilizzo Carta ***1234 Importo: 12,50 EUR, per: BAR DELLA STAZIONE. Info: 0228992899"
        val parsed = FinecoSmsParser.parse(sms)
        assertNotNull(parsed)
        assertEquals(1250L, parsed!!.amountCents)
        assertEquals("BAR DELLA STAZIONE", parsed.merchant)
        // "BAR" takes precedence or matches Ristoranti e bar
        assertEquals("Auto e trasporti", parsed.suggestedCategory) // because of "STAZIONE"
    }

    @Test
    fun parseSupermarket() {
        val sms = "Fineco - SMS: Pagamento Carta ***5555 Importo: 63,45 EUR, per: ESSELUNGA MILANO. Info: 0228992899"
        val parsed = FinecoSmsParser.parse(sms)
        assertNotNull(parsed)
        assertEquals(6345L, parsed!!.amountCents)
        assertEquals("ESSELUNGA MILANO", parsed.merchant)
        assertEquals("Alimentari", parsed.suggestedCategory)
    }

    @Test
    fun parseIntegerAmount() {
        val sms = "Autorizzato addebito Importo: 15 EUR per: FARMACIA CENTRALE."
        val parsed = FinecoSmsParser.parse(sms)
        assertNotNull(parsed)
        assertEquals(1500L, parsed!!.amountCents)
        assertEquals("FARMACIA CENTRALE", parsed.merchant)
        assertEquals("Salute", parsed.suggestedCategory)
    }

    @Test
    fun parseIrrelevantSmsReturnsNull() {
        val sms = "Ciao, ci vediamo stasera per la pizza?"
        assertNull(FinecoSmsParser.parse(sms))
    }
}
