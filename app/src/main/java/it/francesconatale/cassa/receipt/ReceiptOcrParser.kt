package it.francesconatale.cassa.receipt

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

data class OcrLine(
    val text: String,
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0
) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

data class ParsedReceipt(
    val amountCents: Long? = null,
    val merchant: String? = null,
    val dateIso: String? = null,
    val dateDisplay: String? = null,
    val suggestedCategory: String? = null,
    val rawText: String = "",
    val candidateAmounts: List<Long> = emptyList()
) {
    val formattedAmount: String?
        get() = amountCents?.let { formatCents(it) }

    val candidateAmountsFormatted: List<String>
        get() = candidateAmounts.map { formatCents(it) }

    val hasData: Boolean
        get() = amountCents != null || !merchant.isNullOrBlank() || dateIso != null

    companion object {
        fun formatCents(cents: Long): String {
            return BigDecimal(cents).movePointLeft(2).toPlainString().replace('.', ',')
        }
    }
}

object ReceiptOcrParser {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val moneyRegex = Regex("""(?<!\d)(?:€\s*|EUR\s*)?([0-9]{1,4}[,\.][0-9]{2})(?!\s*%)""")

    suspend fun parseFromUri(context: Context, uri: Uri): ParsedReceipt = withContext(Dispatchers.IO) {
        try {
            val image = InputImage.fromFilePath(context, uri)
            val result = suspendCancellableCoroutine<Text> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
            }
            parseText(result)
        } catch (_: Exception) {
            ParsedReceipt()
        }
    }

    fun parseText(text: Text): ParsedReceipt {
        val ocrLines = text.textBlocks.flatMap { block ->
            block.lines.map { line ->
                val box = line.boundingBox
                OcrLine(
                    text = line.text.trim(),
                    top = box?.top ?: 0,
                    bottom = box?.bottom ?: 0,
                    left = box?.left ?: 0,
                    right = box?.right ?: 0
                )
            }
        }.filter { it.text.isNotBlank() }

        return parseOcrLines(ocrLines, text.text)
    }

    fun parseRawText(rawFullText: String, rawLines: List<String> = rawFullText.lines().map { it.trim() }.filter { it.isNotBlank() }): ParsedReceipt {
        val ocrLines = rawLines.mapIndexed { idx, line ->
            OcrLine(text = line, top = idx * 30, bottom = idx * 30 + 20, left = 0, right = 300)
        }
        return parseOcrLines(ocrLines, rawFullText)
    }

    private fun parseOcrLines(lines: List<OcrLine>, rawFullText: String): ParsedReceipt {
        val stringLines = lines.map { it.text }
        val merchant = detectMerchant(stringLines)
        val (amountCents, candidates) = detectTotalAmount(lines, rawFullText)
        val (dateIso, dateDisplay) = detectDate(stringLines, rawFullText)
        val category = merchant?.let { deduceCategory(it) }

        return ParsedReceipt(
            amountCents = amountCents,
            merchant = merchant,
            dateIso = dateIso,
            dateDisplay = dateDisplay,
            suggestedCategory = category,
            rawText = rawFullText,
            candidateAmounts = candidates
        )
    }

    private fun isTaxOrMetadataLine(text: String): Boolean {
        val upper = text.uppercase(Locale.ROOT)
        if (text.contains("%")) return true
        val taxNeedles = listOf(
            "ALIQUOTA", "ALIQ.", "ALIQ ", "IMPONIBILE", "IMPONIB.", "IMPONIB ", "IMPOSTA",
            "DI CUI IVA", "TOTALE IVA", "TOT. IVA", "TOT IVA", "IVA COMPRESA", "IVA INCLUSA",
            "ESENTE", "NON SOGGETTO", "ART. ", "ART.", "SUBTOTALE", "SUB-TOTALE", "SUB TOTALE",
            "TOTALE PARZIALE", "PARZIALE", "RESTO", "PUNTI SPESA", "SALDO PUNTI", "PUNTI ACCUMULATI",
            "LOTTERIA SCONTRINI", "CODICE LOTTERIA", "CREDITO RESIDUO", "BUONO SCONTO", "COUPON",
            "OPERATORE", "CASSIERE", "REG. IMPRESE", "MATRICOLA", "RT ", "SCONTRINO N.", "DOC. COMMERCIALE N.", "CASSA N."
        )
        for (needle in taxNeedles) {
            if (upper.contains(needle)) return true
        }
        if (Regex("""\bIVA\b""").containsMatchIn(upper)) return true
        return false
    }

    private fun detectTotalAmount(lines: List<OcrLine>, rawFullText: String): Pair<Long?, List<Long>> {
        val candidates = mutableListOf<Long>()

        // Raccogli tutti gli importi validi da righe non-tasse
        for (line in lines) {
            if (isTaxOrMetadataLine(line.text)) continue
            val matches = moneyRegex.findAll(line.text)
            for (m in matches) {
                parseToCents(m.groupValues[1])?.let { cents ->
                    if (cents in 10L..500000L && !candidates.contains(cents)) {
                        candidates.add(cents)
                    }
                }
            }
        }

        // 1. Dizionario completo delle formule di Totale standard e abbreviate italiane
        val strongKeywords = listOf(
            "TOTALE COMPLESSIVO",
            "TOTALE COMPL",
            "TOTALE EURO",
            "TOTALE EUR",
            "TOTALE €",
            "TOTALE DOVUTO",
            "TOTALE DA PAGARE",
            "IMPORTO DOVUTO",
            "IMPORTO TOTALE",
            "IMPORTO PAGATO",
            "TOTALE DOCUMENTO",
            "TOTALE SCONTRINO",
            "TOTALE SERVIZIO",
            "TOTALE MERCE",
            "TOTALE NETTO",
            "NETTO A PAGARE",
            "SOMMA TOTALE",
            "SOMMANO EURO",
            "SOMMANO €",
            "CORRISPETTIVO TOTALE",
            "TOTALE GENERALE",
            "TOTALE TRANSAZIONE",
            "TOT. COMPLESSIVO",
            "TOT. COMPL",
            "TOT. EURO",
            "TOT. EUR",
            "TOT. €",
            "TOT. DOVUTO",
            "TOT. DA PAGARE"
        )

        for (i in lines.indices) {
            val line = lines[i]
            if (isTaxOrMetadataLine(line.text)) continue
            val upper = line.text.uppercase(Locale.ROOT)

            for (kw in strongKeywords) {
                if (upper.contains(kw)) {
                    val found = extractAmountFromLine(line.text)
                    if (found != null) return Pair(found, candidates)

                    val aligned = findHorizontallyAlignedAmount(line, lines)
                    if (aligned != null) return Pair(aligned, candidates)

                    for (offset in 1..2) {
                        if (i + offset < lines.size) {
                            val nextLine = lines[i + offset]
                            if (isTaxOrMetadataLine(nextLine.text)) continue
                            val nextFound = extractAmountFromLine(nextLine.text)
                            if (nextFound != null) return Pair(nextFound, candidates)
                        }
                    }
                }
            }
        }

        // 2. Keyword isolata o abbreviata "TOTALE" / "TOT." / "COMPLESSIVO"
        for (i in lines.indices) {
            val line = lines[i]
            if (isTaxOrMetadataLine(line.text)) continue
            val upper = line.text.uppercase(Locale.ROOT)

            if (Regex("""\bTOTALE\b|\bTOT\b|\bCOMPLESSIVO\b""").containsMatchIn(upper)) {
                val found = extractAmountFromLine(line.text)
                if (found != null) return Pair(found, candidates)

                val aligned = findHorizontallyAlignedAmount(line, lines)
                if (aligned != null) return Pair(aligned, candidates)

                for (offset in 1..2) {
                    if (i + offset < lines.size) {
                        val nextLine = lines[i + offset]
                        if (isTaxOrMetadataLine(nextLine.text)) continue
                        val nextFound = extractAmountFromLine(nextLine.text)
                        if (nextFound != null) return Pair(nextFound, candidates)
                    }
                }
            }
        }

        // 3. Verifica matematica tramite Pagato - Resto (es. Pagato 2,00 - Resto 0,31 = 1,69)
        val paidLine = lines.firstOrNull { it.text.uppercase(Locale.ROOT).contains("PAGATO") || it.text.uppercase(Locale.ROOT).contains("PAGAMENTO") }
        val restoLine = lines.firstOrNull { it.text.uppercase(Locale.ROOT).contains("RESTO") }
        if (paidLine != null && restoLine != null) {
            val paidVal = extractAmountFromLine(paidLine.text) ?: findHorizontallyAlignedAmount(paidLine, lines)
            val restoVal = extractAmountFromLine(restoLine.text) ?: findHorizontallyAlignedAmount(restoLine, lines)
            if (paidVal != null && restoVal != null && paidVal > restoVal) {
                val mathTotal = paidVal - restoVal
                if (candidates.contains(mathTotal)) {
                    return Pair(mathTotal, candidates)
                }
            }
        }

        // 4. Pagamenti elettronici certificati (carta / bancomat / POS)
        val electronicKeywords = listOf(
            "PAGATO ELETTRONICO",
            "PAGAMENTO ELETTRONICO",
            "PAGATO CARTE",
            "PAGATO CARTA",
            "PAGAMENTO CARTA",
            "PAGATO POS",
            "PAGAMENTO POS",
            "PAGATO BANCOMAT",
            "PAGAMENTO BANCOMAT",
            "CARTA DI CREDITO",
            "CARTE DI CREDITO",
            "BANCOMAT",
            "PAGOBANCOMAT",
            "IMPORTO TRANSAZIONE",
            "TRANSAZIONE POS"
        )
        for (i in lines.indices) {
            val line = lines[i]
            if (isTaxOrMetadataLine(line.text)) continue
            val upper = line.text.uppercase(Locale.ROOT)

            for (kw in electronicKeywords) {
                if (upper.contains(kw)) {
                    val found = extractAmountFromLine(line.text)
                    if (found != null) return Pair(found, candidates)

                    val aligned = findHorizontallyAlignedAmount(line, lines)
                    if (aligned != null) return Pair(aligned, candidates)
                }
            }
        }

        // 5. Fallback finale sui candidati validi
        if (candidates.isNotEmpty()) {
            return Pair(candidates.last(), candidates)
        }

        return Pair(null, emptyList())
    }

    private fun extractAmountFromLine(text: String): Long? {
        val matches = moneyRegex.findAll(text).toList()
        if (matches.isNotEmpty()) {
            val amount = parseToCents(matches.last().groupValues[1])
            if (amount != null && amount in 10L..500000L) {
                return amount
            }
        }
        return null
    }

    private fun findHorizontallyAlignedAmount(target: OcrLine, allLines: List<OcrLine>): Long? {
        if (target.height <= 0) return null
        val candidates = allLines.filter { other ->
            other != target &&
            !isTaxOrMetadataLine(other.text) &&
            other.left >= target.left &&
            abs(other.centerY - target.centerY) <= target.height * 1.1
        }.sortedBy { it.left }

        for (cand in candidates) {
            val amount = extractAmountFromLine(cand.text)
            if (amount != null) return amount
        }
        return null
    }

    private fun detectMerchant(lines: List<String>): String? {
        val searchLines = lines.take(12)

        val brandMatches = listOf(
            "FAMILA" to "Famila",
            "CONAD" to "Conad",
            "ESSELUNGA" to "Esselunga",
            "COOP" to "Coop",
            "LIDL" to "Lidl",
            "CARREFOUR" to "Carrefour",
            "EUROSPIN" to "Eurospin",
            "PENNY" to "Penny Market",
            "ALDI" to "Aldi",
            "SELEX" to "Selex",
            "IL GIGANTE" to "Il Gigante",
            "IN'S" to "In's Mercato",
            "TODIS" to "Todis",
            "DECO" to "Decò",
            "SOLE365" to "Sole365",
            "MD S.P.A" to "MD",
            "MD SPA" to "MD",
            "DESPAR" to "Despar",
            "EUROSPAR" to "Eurospar",
            "INTERSPAR" to "Interspar",
            "PAM PANORAMA" to "Pam",
            "TIGROS" to "Tigros",
            "CRAI" to "Crai",
            "BENNET" to "Bennet",
            "IPERCOOP" to "Ipercoop",
            "DECATHLON" to "Decathlon",
            "LEROY MERLIN" to "Leroy Merlin",
            "TECNOMAT" to "Tecnomat",
            "BRICOMAN" to "Tecnomat",
            "BRICOCENTER" to "Brico",
            "IKEA" to "Ikea",
            "MEDIAWORLD" to "MediaWorld",
            "UNIEURO" to "Unieuro",
            "EURONICS" to "Euronics",
            "EXPERT" to "Expert",
            "TRONY" to "Trony",
            "APPLE" to "Apple Store",
            "ZARA" to "Zara",
            "H&M" to "H&M",
            "OVS" to "OVS",
            "CALZEDONIA" to "Calzedonia",
            "INTIMISSIMI" to "Intimissimi",
            "PRIMARK" to "Primark",
            "MANGO" to "Mango",
            "ACQUA & SAPONE" to "Acqua & Sapone",
            "ACQUA E SAPONE" to "Acqua & Sapone",
            "TIGOTA" to "Tigotà",
            "ENI STATION" to "Eni",
            "ENI" to "Eni",
            "AGIP" to "Eni",
            "Q8" to "Q8",
            "TAMOIL" to "Tamoil",
            "ESSO" to "Esso",
            "TELEPASS" to "Telepass",
            "AUTOSTRADE" to "Autostrade",
            "MCDONALD" to "McDonald's",
            "BURGER KING" to "Burger King",
            "AUTOGRILL" to "Autogrill",
            "STARBUCKS" to "Starbucks",
            "KFC" to "KFC",
            "FARMACIA" to null,
            "PARAFARMACIA" to null,
            "RISTORANTE" to null,
            "PIZZERIA" to null,
            "TRATTORIA" to null,
            "OSTERIA" to null,
            "BAR " to null,
            "CAFFE" to null,
            "PANIFICIO" to null,
            "PASTICCERIA" to null,
            "MACELLERIA" to null,
            "TABACCHI" to null
        )

        for (line in searchLines) {
            val upper = line.uppercase(Locale.ROOT)
            for ((key, cleanName) in brandMatches) {
                if (upper.contains(key)) {
                    return cleanName ?: cleanLineAsMerchant(line)
                }
            }
        }

        val ignoreKeywords = listOf(
            "DOCUMENTO COMMERCIALE", "SCONTRINO", "VENDITA", "PRESTAZIONE", "P.IVA", "PARTITA IVA",
            "C.F.", "CODICE FISCALE", "REG. IMPRESE", "TEL", "VIA ", "PIAZZA ", "CORSO ",
            "VIALE ", "STRADA ", "CAP ", "NUMERO", "BENVENUTI", "GRAZIE", "ARRIVEDERCI",
            "RT ", "MATRICOLA", "LOTTERIA", "DATA", "ORA", "CASSA", "OPERATORE", "MISURATORE"
        )

        for (line in searchLines.take(5)) {
            val upper = line.uppercase(Locale.ROOT)
            if (line.length in 3..40 && ignoreKeywords.none { upper.contains(it) } && !line.matches(Regex("""^[\d\s\W]+$"""))) {
                return cleanLineAsMerchant(line)
            }
        }

        return null
    }

    private fun cleanLineAsMerchant(raw: String): String {
        return raw.replace(Regex("""(?i)\b(s\.?p\.?a\.?|s\.?r\.?l\.?|s\.?n\.?c\.?|s\.?a\.?s\.?)\b"""), "")
            .replace(Regex("""[^\p{L}\d\s&'\-]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(40)
    }

    private fun detectDate(lines: List<String>, rawFullText: String): Pair<String?, String?> {
        val dateRegex = Regex("""\b([0-3]?[0-9])[/\.-]([0-1]?[0-9])[/\.-](20\d{2}|\d{2})\b""")
        val timeRegex = Regex("""\b([0-2]?[0-9])[:.]([0-5][0-9])(?:[:.]([0-5][0-9]))?\b""")

        for (line in lines) {
            val dateMatch = dateRegex.find(line)
            if (dateMatch != null) {
                val day = dateMatch.groupValues[1].toIntOrNull() ?: continue
                val month = dateMatch.groupValues[2].toIntOrNull() ?: continue
                var year = dateMatch.groupValues[3].toIntOrNull() ?: continue

                if (year < 100) year += 2000
                if (day in 1..31 && month in 1..12 && year in 2020..2035) {
                    val timeMatch = timeRegex.find(line)
                    val hour = timeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 12
                    val minute = timeMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
                    val second = timeMatch?.groupValues?.getOrNull(3)?.toIntOrNull() ?: 0

                    try {
                        val dt = LocalDateTime.of(year, month, day, hour.coerceIn(0, 23), minute.coerceIn(0, 59), second.coerceIn(0, 59))
                        val zdt = dt.atZone(ZoneId.systemDefault())
                        val iso = zdt.toInstant().toString()
                        val display = String.format(Locale.ROOT, "%02d/%02d/%04d %02d:%02d", day, month, year, dt.hour, dt.minute)
                        return Pair(iso, display)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        return Pair(null, null)
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

    private fun deduceCategory(merchant: String): String {
        val upper = merchant.uppercase(Locale.ROOT)
        return when {
            containsAny(upper, "ESSO", "ENI", "AGIP", "Q8", "IP ", "TANGENZIALE", "AUTOSTRAD", "TELEPASS", "CARBURANT", "BENZIN", "PARCHEGG", "GARAGE", "TRENITALIA", "ITALO", "STAZIONE") ->
                "Auto e trasporti"

            containsAny(upper, "SUPERMERCAT", "COOP", "CONAD", "ESSELUNGA", "CARREFOUR", "LIDL", "EUROSPIN", "MD", "PAM", "DESPAR", "CRAI", "TIGROS", "IPER", "ALIMENTAR", "PANIFICIO", "MACELLERIA", "PESCHERIA") ->
                "Alimentari"

            containsAny(upper, "BAR", "CAFFE", "CAFE", "RISTORANT", "PIZZERIA", "TRATTORIA", "PUB", "OSTERIA", "MCDONALD", "BURGER KING", "SUSHI", "AUTOGRILL", "PASTICCERIA", "GELATERIA") ->
                "Ristoranti e bar"

            containsAny(upper, "FARMACIA", "PARAFARMACIA", "MEDICO", "SANITA", "CLINICA", "OTTICA", "DENTISTA") ->
                "Salute"

            containsAny(upper, "ZARA", "H&M", "OVS", "DECATHLON", "ABBIGLIAMENTO", "SCARPE", "CALZATUR", "INTIMISSIMI", "CALZEDONIA", "PRIMARK", "MANGO") ->
                "Abbigliamento"

            containsAny(upper, "LEROY", "BRICO", "TECNOMAT", "IKEA", "TIGOTA", "ACQUA & SAPONE", "ACQUA E SAPONE", "CASALINGHI", "FERRAMENTA") ->
                "Casa e utenze"

            containsAny(upper, "MEDIAWORLD", "UNIEURO", "EURONICS", "EXPERT", "TRONY", "APPLE") ->
                "Elettronica"

            else -> "Alimentari"
        }
    }

    private fun containsAny(text: String, vararg needles: String): Boolean {
        for (n in needles) {
            if (text.contains(n)) return true
        }
        return false
    }
}
