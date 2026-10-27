package it.francesconatale.cassa.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object CsvExporter {
    fun exportAndShare(
        context: Context,
        transactions: List<TransactionEntity>,
        accounts: List<AccountEntity>,
        categories: List<CategoryEntity>
    ) {
        val accountsMap = accounts.associateBy { it.id }
        val categoriesMap = categories.associateBy { it.id }

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val exportFile = File(exportDir, "cassa_movimenti_$today.csv")

        val symbols = DecimalFormatSymbols(Locale.ITALY).apply {
            decimalSeparator = ','
            groupingSeparator = '.'
        }
        val decimalFormat = DecimalFormat("0.00", symbols)

        val csvBuilder = StringBuilder()
        // Byte Order Mark (BOM) UTF-8 per compatibilità Excel su Windows/Mac
        csvBuilder.append("\uFEFF")
        csvBuilder.append("ID;Data;Ora;Tipo;Conto;Conto Destinazione;Categoria;Importo (€);Nota\n")

        val sorted = transactions.sortedByDescending { it.effectiveAt }
        for (item in sorted) {
            val (dateStr, timeStr) = runCatching {
                val zdt = ZonedDateTime.parse(item.effectiveAt)
                Pair(
                    zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                    zdt.format(DateTimeFormatter.ofPattern("HH:mm"))
                )
            }.getOrDefault(Pair(item.effectiveAt, ""))

            val typeStr = when (item.transactionType) {
                "income" -> "Entrata"
                "expense" -> "Spesa"
                "transfer" -> "Giroconto"
                else -> item.transactionType
            }

            val accountName = accountsMap[item.accountId]?.name ?: item.accountId
            val destAccountName = item.destinationAccountId?.let { accountsMap[it]?.name ?: it } ?: ""
            val categoryName = item.categoryId?.let { categoriesMap[it]?.name ?: it } ?: ""
            val amountFormatted = decimalFormat.format(BigDecimal(item.amountCents).movePointLeft(2))
            val noteClean = (item.note ?: "").replace("\"", "\"\"")

            csvBuilder.append(escape(item.id)).append(";")
            csvBuilder.append(escape(dateStr)).append(";")
            csvBuilder.append(escape(timeStr)).append(";")
            csvBuilder.append(escape(typeStr)).append(";")
            csvBuilder.append(escape(accountName)).append(";")
            csvBuilder.append(escape(destAccountName)).append(";")
            csvBuilder.append(escape(categoryName)).append(";")
            csvBuilder.append(escape(amountFormatted)).append(";")
            csvBuilder.append("\"").append(noteClean).append("\"\n")
        }

        exportFile.writeText(csvBuilder.toString(), Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            context,
            "it.francesconatale.cassa.fileprovider",
            exportFile
        )

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Esportazione movimenti Cassa ($today)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(sendIntent, "Esporta movimenti CSV").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun escape(value: String): String {
        return if (value.contains(";") || value.contains("\"") || value.contains("\n")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
