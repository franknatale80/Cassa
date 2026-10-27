package it.francesconatale.cassa.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptOcrParserTest {

    @Test
    fun parseReceiptFamilaWithVatAndMultipleItems() {
        val raw = """
            FAMILA SUPERSTORE
            MAXIDI S.R.L. VIA BOLOGNA 20
            P.IVA 01548230234
            DOCUMENTO COMMERCIALE
            LATTE FRESCO         1,49
            SACCHETTO BIO        0,20
            TOTALE COMPLESSIVO   1,69
            PAGATO CONTANTI      2,00
            RESTO                0,31
            ALIQUOTA IVA 22,00 % 0,30
            TOTALE IVA           0,30
            22/10/2026 18:30
        """.trimIndent()

        val parsed = ReceiptOcrParser.parseRawText(raw)
        assertEquals(169L, parsed.amountCents)
        assertEquals("1,69", parsed.formattedAmount)
        assertEquals("Famila", parsed.merchant)
        assertEquals("Alimentari", parsed.suggestedCategory)
        assertTrue(parsed.candidateAmounts.contains(169L))
        assertTrue(parsed.candidateAmounts.contains(149L))
        assertTrue(parsed.candidateAmounts.contains(20L))
    }

    @Test
    fun parseReceiptAbbreviatedTotCompl() {
        val raw = """
            TRATTORIA LA LANTERNA
            VIA ROMA 5 - FIRENZE
            P.IVA 09876543210
            PRIMO PIATTO         9,50
            ACQUA MINERALE       2,00
            CAFFE                1,50
            COPERTO              2,20
            TOT. COMPL. €       15,20
            PAGATO POS          15,20
            20/10/2026 13:15
        """.trimIndent()

        val parsed = ReceiptOcrParser.parseRawText(raw)
        assertEquals(1520L, parsed.amountCents)
        assertEquals("15,20", parsed.formattedAmount)
        assertEquals("Ristoranti e bar", parsed.suggestedCategory)
    }

    @Test
    fun parseReceiptMathematicalCheckFromPaidAndChange() {
        val raw = """
            BAR DELLO SPORT
            P.IVA 01122334455
            CONSUMAZIONE A       1,69
            PAGAMENTO CONTANTI   2,00
            RESTO EURO           0,31
            22/10/2026
        """.trimIndent()

        val parsed = ReceiptOcrParser.parseRawText(raw)
        // 2,00 - 0,31 = 1,69
        assertEquals(169L, parsed.amountCents)
        assertEquals("1,69", parsed.formattedAmount)
    }

    @Test
    fun parseReceiptWithVat22PercentAndSingleItem() {
        val raw = """
            PANIFICIO IL FORNO
            VIA DEI MILLE 10 - BOLOGNA
            P.IVA 01234567890
            DOCUMENTO COMMERCIALE
            PANE INTEGRALE        1,69
            TOTALE EURO           1,69
            PAGATO CONTANTI       2,00
            RESTO                 0,31
            ALIQUOTA IVA 22,00 %  0,30
            TOTALE IVA            0,30
            22/10/2026 12:45
        """.trimIndent()

        val parsed = ReceiptOcrParser.parseRawText(raw)
        assertEquals(169L, parsed.amountCents)
        assertEquals("1,69", parsed.formattedAmount)
        assertTrue(parsed.merchant?.contains("PANIFICIO", ignoreCase = true) == true)
        assertEquals("Alimentari", parsed.suggestedCategory)
    }

    @Test
    fun parseReceiptWithMultiLineTotalAnd22PercentVat() {
        val raw = """
            CONAD
            P.IVA 123456789
            LATTE FRESCO 1,69
            TOTALE
            EURO
            1,69
            PAGAMENTO POS
            1,69
            IVA 22,00%
            TOTALE IVA 22,00 % 0,30
            22/10/2026
        """.trimIndent()

        val parsed = ReceiptOcrParser.parseRawText(raw)
        assertEquals(169L, parsed.amountCents)
        assertEquals("1,69", parsed.formattedAmount)
    }

    @Test
    fun parseConadReceipt() {
        val raw = """
            CONAD CENTRO NORD SOC. COOP.
            VIA EMILIA OVEST 234 - PARMA
            P.IVA 00123456789
            DOCUMENTO COMMERCIALE
            DI VENDITA O PRESTAZIONE
            LATTE PARZ SCREM      1.49
            PANE CASERECCIO       2.10
            PASTA BARILLA         1.15
            SUBTOTALE             4.74
            TOTALE COMPLESSIVO €  4.74
            PAGAMENTO CONTANTI   10.00
            RESTO                 5.26
            22/10/2026 17:35
            RT 12345678
            ARRIVEDERCI E GRAZIE
        """.trimIndent()

        val parsed = ReceiptOcrParser.parseRawText(raw)
        assertEquals("Conad", parsed.merchant)
        assertEquals(474L, parsed.amountCents)
        assertEquals("Alimentari", parsed.suggestedCategory)
        assertNotNull(parsed.dateIso)
        assertTrue(parsed.dateDisplay?.contains("22/10/2026") == true)
        assertEquals("4,74", parsed.formattedAmount)
    }

    @Test
    fun parseEsselungaReceipt() {
        val raw = """
            ESSELUNGA S.P.A.
            VIA PISACANE 1 - MILANO
            P.IVA 01275720152
            DOCUMENTO COMMERCIALE
            BANANE BIO           1.80
            MELA FUJI            2.40
            CAFFE LAVAZZA        4.50
            TOTALE EURO          8.70
            PAGATO CARTE         8.70
            22/10/26 14:15
            GRAZIE E ARRIVEDERCI
        """.trimIndent()

        val parsed = ReceiptOcrParser.parseRawText(raw)
        assertEquals("Esselunga", parsed.merchant)
        assertEquals(870L, parsed.amountCents)
        assertEquals("Alimentari", parsed.suggestedCategory)
        assertNotNull(parsed.dateIso)
        assertTrue(parsed.dateDisplay?.contains("22/10/2026") == true)
    }

    @Test
    fun parseFarmaciaWithMultiLineTotal() {
        val raw = """
            FARMACIA SAN CARLO DEI DOTT. ROSSI
            VIA ROMA 45 - ROMA
            PARTITA IVA 09876543210
            TACHIPIRINA 1000      5.90
            ASPIRINA C            6.50
            TOTALE DOVUTO
            € 12.40
            PAGAMENTO ELETTRONICO 12.40
            DATA 18/09/2026 ORA 11:20
        """.trimIndent()

        val parsed = ReceiptOcrParser.parseRawText(raw)
        assertTrue(parsed.merchant?.startsWith("FARMACIA SAN CARLO", ignoreCase = true) == true)
        assertEquals(1240L, parsed.amountCents)
        assertEquals("Salute", parsed.suggestedCategory)
        assertNotNull(parsed.dateIso)
        assertTrue(parsed.dateDisplay?.contains("18/09/2026") == true)
    }

    @Test
    fun parseBarWithSeparateLineAmount() {
        val raw = """
            BAR CENTRALE SNC
            PIAZZA GARIBALDI 12 - NAPOLI
            2 CAFFE              2.40
            1 CORNETTO           1.30
            TOTALE
            3.70
            PAGATO CONTANTI      5.00
            RESTO                1.30
            DATA 20-10-2026 08:30
        """.trimIndent()

        val parsed = ReceiptOcrParser.parseRawText(raw)
        assertTrue(parsed.merchant?.contains("BAR CENTRALE", ignoreCase = true) == true)
        assertEquals(370L, parsed.amountCents)
        assertEquals("Ristoranti e bar", parsed.suggestedCategory)
        assertNotNull(parsed.dateIso)
        assertTrue(parsed.dateDisplay?.contains("20/10/2026") == true)
    }

    @Test
    fun parseEniStationGasoline() {
        val raw = """
            ENI STATION CORSO FRANCIA
            VIA FRANCIA 100 - TORINO
            ENI S.P.A. P.IVA 00484960588
            SENZA PIOMBO 95
            LITRI 24.50
            TOTALE COMPLESSIVO   45.00
            21/10/2026 19:42
        """.trimIndent()

        val parsed = ReceiptOcrParser.parseRawText(raw)
        assertEquals("Eni", parsed.merchant)
        assertEquals(4500L, parsed.amountCents)
        assertEquals("Auto e trasporti", parsed.suggestedCategory)
        assertNotNull(parsed.dateIso)
        assertTrue(parsed.dateDisplay?.contains("21/10/2026") == true)
    }

    @Test
    fun parseLeroyMerlinDiy() {
        val raw = """
            LEROY MERLIN ITALIA S.R.L.
            STRADA STATALE 11 - VITTUONE
            P.IVA 05638490960
            TASSELLO FISCHER 6X30 4.20
            SILICONE BIANCO       5.80
            TOTALE €             10.00
            PAGATO POS           10.00
            15/10/2026 16:10
        """.trimIndent()

        val parsed = ReceiptOcrParser.parseRawText(raw)
        assertEquals("Leroy Merlin", parsed.merchant)
        assertEquals(1000L, parsed.amountCents)
        assertEquals("Casa e utenze", parsed.suggestedCategory)
        assertNotNull(parsed.dateIso)
    }
}
