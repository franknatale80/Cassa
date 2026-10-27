package it.francesconatale.cassa.notification

import java.util.Locale

object BankNotificationParser {

    const val PKG_GOOGLE_WALLET = "com.google.android.apps.walletnfcrel"
    const val PKG_BMPS = "it.copergmps.rt.pf.android.sp.bmps"
    const val PKG_FINECO = "com.fineco.it"
    const val PKG_PAYPAL = "com.paypal.android.p2pmobile"

    // Regex per importi: es. "4,20 €", "1.507,06 Euro", "€ 25,50", "15,00 EUR"
    private val amountWithSymbolRegex = Regex(
        """([0-9]{1,3}(?:\.[0-9]{3})*(?:,[0-9]{1,2})|[0-9]+(?:[,\.][0-9]{1,2})?)\s*(?:€|Euro|EUR)""",
        RegexOption.IGNORE_CASE
    )

    private val amountWithPrefixRegex = Regex(
        """(?:€|Euro|EUR)\s*([0-9]{1,3}(?:\.[0-9]{3})*(?:,[0-9]{1,2})|[0-9]+(?:[,\.][0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    fun parse(packageName: String, title: String?, text: String?): ParsedBankNotification? {
        val safeTitle = title?.trim().orEmpty()
        val safeText = text?.trim().orEmpty()
        if (safeTitle.isBlank() && safeText.isBlank()) return null

        return when (packageName) {
            PKG_GOOGLE_WALLET -> parseGoogleWallet(safeTitle, safeText)
            PKG_BMPS -> parseBmps(safeTitle, safeText)
            PKG_FINECO -> parseFineco(safeTitle, safeText)
            PKG_PAYPAL -> parsePayPal(safeTitle, safeText)
            else -> parseGeneric(packageName, safeTitle, safeText)
        }
    }

    /**
     * Google Wallet / Google Pay:
     * Titolo: SumUp  *M   P SERVICE  (oppure esercente)
     * Testo: 4,20 € con MPS Debit Mastercard ••4843
     */
    private fun parseGoogleWallet(title: String, text: String): ParsedBankNotification? {
        val cleanText = text.replace('\u00A0', ' ') // normalizza non-breaking space
        val cents = extractCents(cleanText) ?: extractCents(title) ?: return null

        val merchant = if (title.isNotBlank() && !title.equals("Acquisto completato", ignoreCase = true) && !title.equals("Google Pay", ignoreCase = true)) {
            cleanMerchant(title)
        } else {
            // Estrai eventuale esercente dal testo
            "Spesa con carta"
        }

        val category = deduceCategory(merchant)

        return ParsedBankNotification(
            amountCents = cents,
            merchantOrTitle = merchant,
            transactionType = "expense",
            suggestedCategory = category,
            packageName = PKG_GOOGLE_WALLET,
            rawText = "$title - $text"
        )
    }

    /**
     * Banca Monte dei Paschi di Siena (BMPS):
     * Titolo: Accredito Emolumenti  (oppure "Pagamento POS", "Bonifico", ecc.)
     * Testo: Hai ricevuto l'accredito dello stipendio di 1.507,06 Euro sul tuo Conto 49795
     */
    private fun parseBmps(title: String, text: String): ParsedBankNotification? {
        val cleanText = text.replace('\u00A0', ' ')
        val cents = extractCents(cleanText) ?: extractCents(title) ?: return null

        val combined = "$title $cleanText".uppercase(Locale.ITALY)
        val isIncome = combined.contains("ACCREDITO") || combined.contains("STIPENDIO") || combined.contains("EMOLUMENTI") || combined.contains("BONIFICO A TUO FAVORE")

        val transactionType = if (isIncome) "income" else "expense"

        val merchantOrNote = when {
            combined.contains("STIPENDIO") || combined.contains("EMOLUMENTI") -> "Accredito Stipendio"
            title.isNotBlank() && !title.equals("MPS", ignoreCase = true) -> cleanMerchant(title)
            else -> if (isIncome) "Entrata MPS" else "Spesa MPS"
        }

        val category = if (isIncome) "Stipendio" else deduceCategory(merchantOrNote)

        return ParsedBankNotification(
            amountCents = cents,
            merchantOrTitle = merchantOrNote,
            transactionType = transactionType,
            suggestedCategory = category,
            packageName = PKG_BMPS,
            rawText = "$title - $text"
        )
    }

    /**
     * Fineco Push:
     * es. "Autorizzato pagamento di 25,00 EUR per ENI STATION"
     */
    private fun parseFineco(title: String, text: String): ParsedBankNotification? {
        val combined = "$title $text".replace('\u00A0', ' ')
        val cents = extractCents(combined) ?: return null

        val isIncome = combined.uppercase(Locale.ITALY).contains("BONIFICO A TUO FAVORE") || combined.uppercase(Locale.ITALY).contains("ACCREDITO")
        val type = if (isIncome) "income" else "expense"

        val merchantRegex = Regex("""\b(?:per|presso|su)\b[:\s]+([^.]+?)(?:\.\s*Info:|\.|\n|$)""", RegexOption.IGNORE_CASE)
        val merchantMatch = merchantRegex.find(combined)
        val rawMerchant = merchantMatch?.groupValues?.get(1)?.trim() ?: if (title.isNotBlank() && !title.contains("Fineco", true)) title else "Pagamento Fineco"
        val cleanMerchant = cleanMerchant(rawMerchant)
        val category = if (isIncome) "Entrate" else deduceCategory(cleanMerchant)

        return ParsedBankNotification(
            amountCents = cents,
            merchantOrTitle = cleanMerchant,
            transactionType = type,
            suggestedCategory = category,
            packageName = PKG_FINECO,
            rawText = combined
        )
    }

    /**
     * PayPal Push:
     * Titolo: "Hai inviato un pagamento di 15,00 € EUR a Mario Rossi"
     * oppure Titolo: "PayPal", Testo: "Hai pagato 12,50 € a Spotify"
     */
    private fun parsePayPal(title: String, text: String): ParsedBankNotification? {
        val combined = "$title $text".replace('\u00A0', ' ')
        val cents = extractCents(combined) ?: return null

        val isIncome = combined.uppercase(Locale.ITALY).contains("RICEVUTO") || combined.uppercase(Locale.ITALY).contains("TI HA INVIATO")
        val type = if (isIncome) "income" else "expense"

        val toRegex = Regex("""\b(?:a|presso|per)\b[:\s]+([^.]+?)(?:\.|\n|$)""", RegexOption.IGNORE_CASE)
        val match = toRegex.find(combined)
        val merchant = match?.groupValues?.get(1)?.trim() ?: "PayPal"
        val clean = cleanMerchant(merchant)
        val category = if (isIncome) "Entrate" else deduceCategory(clean)

        return ParsedBankNotification(
            amountCents = cents,
            merchantOrTitle = clean,
            transactionType = type,
            suggestedCategory = category,
            packageName = PKG_PAYPAL,
            rawText = combined
        )
    }

    /**
     * Parser generico per notifiche con importo monetario chiaro
     */
    private fun parseGeneric(pkg: String, title: String, text: String): ParsedBankNotification? {
        val combined = "$title $text".replace('\u00A0', ' ')
        val cents = extractCents(combined) ?: return null

        val upper = combined.uppercase(Locale.ITALY)
        val isIncome = upper.contains("ACCREDITO") || upper.contains("STIPENDIO") || upper.contains("RICEVUTO")
        val type = if (isIncome) "income" else "expense"

        val merchant = if (title.isNotBlank()) cleanMerchant(title) else "Pagamento bancario"
        val category = if (isIncome) "Entrate" else deduceCategory(merchant)

        return ParsedBankNotification(
            amountCents = cents,
            merchantOrTitle = merchant,
            transactionType = type,
            suggestedCategory = category,
            packageName = pkg,
            rawText = combined
        )
    }

    fun extractCents(raw: String): Long? {
        var match = amountWithSymbolRegex.find(raw)
        if (match == null) {
            match = amountWithPrefixRegex.find(raw)
        }
        val matchStr = match?.groupValues?.get(1) ?: return null

        return parseItalianAmountToCents(matchStr)
    }

    private fun parseItalianAmountToCents(amountStr: String): Long? {
        return try {
            // Rimuove i punti delle migliaia (es. "1.507,06" -> "1507,06")
            val clean = amountStr.replace(".", "").replace(',', '.')
            val parts = clean.split('.')
            val whole = parts[0].toLong()
            val decimal = if (parts.size > 1) {
                parts[1].padEnd(2, '0').take(2).toLong()
            } else {
                0L
            }
            whole * 100L + decimal
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanMerchant(name: String): String {
        return name
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)
    }

    fun deduceCategory(merchantOrNote: String): String {
        val upper = merchantOrNote.uppercase(Locale.ITALY)
        return when {
            containsAny(upper, "ESSO", "ENI", "Q8", "IP ", "TANGENZIALE", "AUTOSTRAD", "TELEPASS", "CARBURANT", "BENZIN", "PARCHEGG", "GARAGE", "TRENITALIA", "ITALO", "STAZIONE") ->
                "Auto e trasporti"

            containsAny(upper, "SUPERMERCAT", "COOP", "CONAD", "ESSELUNGA", "CARREFOUR", "LIDL", "EUROSPIN", "MD ", "PAM", "DESPAR", "CRAI", "TIGROS", "IPER", "ALIMENTAR", "PANIFICIO", "MACELLERIA", "FAMILA", "PENNY") ->
                "Alimentari"

            containsAny(upper, "BAR", "CAFFE", "CAFE", "RISTORANT", "PIZZERIA", "TRATTORIA", "PUB", "OSTERIA", "MCDONALD", "BURGER KING", "SUSHI", "SUMUP") ->
                "Ristoranti e bar"

            containsAny(upper, "FARMACIA", "PARAFARMACIA", "MEDICO", "SANITA", "CLINICA", "OTTICA") ->
                "Salute"

            containsAny(upper, "ZARA", "H&M", "OVS", "DECATHLON", "ABBIGLIAMENTO", "SCARPE", "CALZATUR") ->
                "Abbigliamento"

            containsAny(upper, "ENEL", "PLENITUDE", "A2A", "HERA", "EDISON", "TIM ", "VODAFONE", "ILIAD", "WINDTRE", "FASTWEB") ->
                "Casa e utenze"

            containsAny(upper, "MEDIAWORLD", "UNIEURO", "EURONICS", "EXPERT", "TRONY", "APPLE", "AMAZON") ->
                "Elettronica"

            containsAny(upper, "STIPENDIO", "EMOLUMENTI", "PENSIONE") ->
                "Stipendio"

            else -> "Varie"
        }
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { text.contains(it) }
    }
}
