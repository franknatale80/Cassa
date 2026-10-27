package it.francesconatale.cassa.sms

import java.util.Locale

data class ParsedPayment(
    val amountCents: Long,
    val merchant: String,
    val suggestedCategory: String,
    val rawMessage: String
)

object FinecoSmsParser {

    private val amountRegex = Regex(
        """(?:Importo|Spesa|Pagamento|Addebito)[:\s]+(?:di\s+)?([0-9]+(?:[,\.][0-9]{1,2})?)\s*(?:EUR|€)""",
        RegexOption.IGNORE_CASE
    )

    private val amountPrefixRegex = Regex(
        """(?:EUR|€)\s*([0-9]+(?:[,\.][0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    private val merchantRegex = Regex(
        """\b(?:per|presso|su)\b[:\s]+([^.]+?)(?:\.\s*Info:|\.|\n|$)""",
        RegexOption.IGNORE_CASE
    )

    fun parse(message: String): ParsedPayment? {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return null

        // Cerca l'importo
        var amountMatch = amountRegex.find(trimmed)
        if (amountMatch == null) {
            amountMatch = amountPrefixRegex.find(trimmed)
        }
        if (amountMatch == null) return null

        val amountStr = amountMatch.groupValues[1]
        val cents = parseToCents(amountStr) ?: return null
        if (cents <= 0) return null

        // Cerca l'esercente
        val merchantMatch = merchantRegex.find(trimmed)
        val rawMerchant = merchantMatch?.groupValues?.get(1)?.trim() ?: "Spesa con carta"
        val cleanMerchant = cleanMerchantName(rawMerchant)

        val category = deduceCategory(cleanMerchant)

        return ParsedPayment(
            amountCents = cents,
            merchant = cleanMerchant,
            suggestedCategory = category,
            rawMessage = trimmed
        )
    }

    private fun parseToCents(raw: String): Long? {
        return try {
            val normalized = raw.replace(',', '.')
            val parts = normalized.split('.')
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

    private fun cleanMerchantName(name: String): String {
        return name
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)
    }

    private fun deduceCategory(merchant: String): String {
        val upper = merchant.uppercase(Locale.ROOT)
        return when {
            containsAny(upper, "ESSO", "ENI", "Q8", "IP ", "TANGENZIALE", "AUTOSTRAD", "TELEPASS", "CARBURANT", "BENZIN", "PARCHEGG", "GARAGE", "TRENITALIA", "ITALO", "STAZIONE") ->
                "Auto e trasporti"

            containsAny(upper, "SUPERMERCAT", "COOP", "CONAD", "ESSELUNGA", "CARREFOUR", "LIDL", "EUROSPIN", "MD ", "PAM", "DESPAR", "CRAI", "TIGROS", "IPER", "ALIMENTAR", "PANIFICIO", "MACELLERIA") ->
                "Alimentari"

            containsAny(upper, "BAR", "CAFFE", "CAFE", "RISTORANT", "PIZZERIA", "TRATTORIA", "PUB", "OSTERIA", "MCDONALD", "BURGER KING", "SUSHI") ->
                "Ristoranti e bar"

            containsAny(upper, "FARMACIA", "PARAFARMACIA", "MEDICO", "SANITA", "CLINICA", "OTTICA") ->
                "Salute"

            containsAny(upper, "ZARA", "H&M", "OVS", "DECATHLON", "ABBIGLIAMENTO", "SCARPE", "CALZATUR") ->
                "Abbigliamento"

            containsAny(upper, "ENEL", "PLENITUDE", "A2A", "HERA", "EDISON", "TIM ", "VODAFONE", "ILIAD", "WINDTRE", "FASTWEB") ->
                "Utenze"

            containsAny(upper, "IKEA", "LEROY MERLIN", "BRICO", "OBI ", "MONDO CONVENIENZA") ->
                "Casa"

            containsAny(upper, "CINEMA", "TEATRO", "NETFLIX", "SPOTIFY", "AMAZON", "PRIME", "STEAM", "PLAYSTATION") ->
                "Tempo libero"

            else -> "Altro"
        }
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { text.contains(it) }
    }
}
