package it.francesconatale.cassa

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.francesconatale.cassa.data.*

import it.francesconatale.cassa.receipt.ReceiptOcrParser
import it.francesconatale.cassa.receipt.ParsedReceipt
import it.francesconatale.cassa.analytics.CashFlowHealth
import it.francesconatale.cassa.analytics.CashFlowPredictor
import it.francesconatale.cassa.analytics.CashFlowProjection
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

private val Ink = Color(0xFF172923)
private val Paper = Color(0xFFF7F3E8)
private val Moss = Color(0xFF55735B)
private val SoftMoss = Color(0xFFDDE7D7)
private val Coral = Color(0xFFE36C4F)
private val Warm = Color(0xFFF6E4AD)
private val Italian = Locale.ITALY

private fun getGreeting(): String {
    val hour = LocalTime.now().hour
    return when (hour) {
        in 5..12 -> "Buongiorno"
        in 13..17 -> "Buon pomeriggio"
        in 18..21 -> "Buonasera"
        else -> "Buonanotte"
    }
}

private fun getTodayFormatted(): String {
    return DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ITALIAN).format(LocalDate.now())
}

@Composable
fun CassaApp(
    dao: CassaDao,
    repository: TransactionRepository,
    smsDraft: it.francesconatale.cassa.sms.SmsPaymentDraft? = null,
    onClearSmsDraft: () -> Unit = {}
) {
    var page by remember { mutableIntStateOf(0) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editedTransaction by remember { mutableStateOf<TransactionEntity?>(null) }
    var isDraftFromSms by remember { mutableStateOf(false) }
    var editedAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var categoriesOpen by remember { mutableStateOf(false) }
    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val accounts by dao.observeAccounts().collectAsState(initial = emptyList())
    val categories by dao.observeCategories().collectAsState(initial = emptyList())
    val transactions by dao.observeTransactions().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val scheme = lightColorScheme(primary = Moss, secondary = Coral, background = Paper, surface = Paper, onSurface = Ink)

    LaunchedEffect(smsDraft, accounts, categories) {
        if (smsDraft != null && accounts.isNotEmpty()) {
            val isIncome = smsDraft.transactionType == "income"
            val targetType = if (isIncome) "income" else "expense"
            val bankAccount = accounts.firstOrNull { it.kind == "bank" } ?: accounts.first()
            val matchedCat = categories.firstOrNull {
                it.transactionType == targetType && it.name.equals(smsDraft.suggestedCategory, ignoreCase = true)
            } ?: categories.firstOrNull { it.transactionType == targetType }

            val now = Instant.now().toString()
            editedTransaction = TransactionEntity(
                id = UUID.randomUUID().toString(),
                transactionType = targetType,
                amountCents = smsDraft.amountCents,
                accountId = bankAccount.id,
                destinationAccountId = null,
                categoryId = matchedCat?.id,
                paymentMethodId = null,
                effectiveAt = now,
                note = smsDraft.merchant,
                revision = 0,
                createdAt = now,
                updatedAt = now
            )
            isDraftFromSms = true
            editorOpen = true
            onClearSmsDraft()
        }
    }

    LaunchedEffect(Unit) {
        repository.initializeDefaults()
    }

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = Paper,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                if (page != 3) FloatingActionButton(onClick = { editedTransaction = null; editorOpen = true }, containerColor = Coral, contentColor = Color.White) { Text("+", fontSize = 30.sp) }
            },
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    listOf("Home", "Movimenti", "Riepilogo", "Impostazioni").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = page == index,
                            onClick = { page = index },
                            icon = { Text(listOf("⌂", "≡", "◔", "⚙")[index], fontSize = 18.sp) },
                            label = { Text(label, fontSize = 10.sp) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            if (page == 0) getGreeting() else listOf("", "Movimenti", "Riepilogo", "Impostazioni")[page],
                            fontSize = 29.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                        if (page == 0) {
                            Text(
                                getTodayFormatted(),
                                fontSize = 13.sp,
                                color = Ink.copy(alpha = 0.55f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    OfflineBadge()
                }
                Spacer(Modifier.height(18.dp))
                when (page) {
                    0 -> Home(accounts, categories, transactions, selectedMonth, { selectedMonth = it })
                    1 -> Movements(accounts, categories, transactions, selectedMonth, { selectedMonth = it }) { transaction -> editedTransaction = transaction; editorOpen = true }
                    2 -> Summary(accounts, transactions, categories, selectedMonth, { selectedMonth = it })
                    else -> Settings(accounts, categories, transactions, dao, onEditAccount = { editedAccount = it }, onEditCategories = { categoriesOpen = true })
                }
            }
        }
        if (editorOpen && accounts.size >= 2) {
            MovementEditor(
                accounts = accounts,
                categories = categories,
                initial = editedTransaction,
                isDraft = isDraftFromSms,
                onClose = { editorOpen = false; editedTransaction = null; isDraftFromSms = false },
                onSave = { draft -> scope.launch { repository.saveOffline(draft) }; editorOpen = false; editedTransaction = null; isDraftFromSms = false },
                onDelete = { transaction -> scope.launch { repository.deleteOffline(transaction) }; editorOpen = false; editedTransaction = null; isDraftFromSms = false },
            )
        }
        editedAccount?.let { account ->
            AccountBalanceEditor(account, onClose = { editedAccount = null }) { cents ->
                scope.launch {
                    repository.updateOpeningBalance(account, cents)
                }
                editedAccount = null
            }
        }
        if (categoriesOpen) {
            CategoryEditor(
                categories = categories,
                onClose = { categoriesOpen = false },
                onSave = { category ->
                    scope.launch {
                        repository.saveCategory(category)
                    }
                },
                onDelete = { category ->
                    scope.launch {
                        if (!repository.deleteCategory(category)) {
                            snackbarHostState.showSnackbar("Categoria usata da uno o più movimenti: non eliminata")
                        }
                    }
                },
            )
        }

    }
}

@Composable
private fun Home(accounts: List<AccountEntity>, categories: List<CategoryEntity>, transactions: List<TransactionEntity>, selectedMonth: YearMonth, onMonthChange: (YearMonth) -> Unit) {
    val balances = accounts.associate { it.id to accountBalance(it, transactions) }
    val month = monthTransactions(transactions, selectedMonth)
    val income = month.filter { it.transactionType == "income" }.sumOf { it.amountCents }
    val expenses = month.filter { it.transactionType == "expense" }.sumOf { it.amountCents }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.fillMaxWidth().background(Ink, RoundedCornerShape(28.dp)).padding(22.dp)) {
                Text("Disponibilità totale", color = Color.White.copy(alpha = .7f))
                Text(money(balances.values.sum()), color = Color.White, fontSize = 35.sp, fontWeight = FontWeight.Bold)
                Text(signedMoney(income - expenses) + " questo mese", color = Color.White, fontSize = 12.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                accounts.take(2).forEachIndexed { index, account ->
                    AccountCard(account.name, money(balances[account.id] ?: 0), if (index == 0) SoftMoss else Warm, Modifier.weight(1f))
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(22.dp)).padding(18.dp)) {
                MonthNavigator(selectedMonth, onMonthChange)
                Text("Il mese in breve", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Entrate", signedMoney(income), Moss)
                    Metric("Spese", if (expenses == 0L) money(0) else "− ${money(expenses)}", Coral)
                }
            }
        }
        item { Text("Ultimi movimenti", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        if (month.isEmpty()) item { EmptyState("Nessun movimento in questo mese.") }
        items(month.take(3), key = { it.id }) { MovementRow(it, accounts, categories) }
    }
}

@Composable
private fun AccountCard(name: String, amount: String, color: Color, modifier: Modifier) = Column(modifier.background(color, RoundedCornerShape(20.dp)).padding(16.dp)) {
    Text(name, fontSize = 12.sp)
    Text(amount, fontSize = 21.sp, fontWeight = FontWeight.Bold)
}

@Composable private fun Metric(label: String, value: String, color: Color) = Column { Text(label, fontSize = 12.sp); Text(value, color = color, fontWeight = FontWeight.Bold) }

@Composable
private fun Movements(
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    transactions: List<TransactionEntity>,
    selectedMonth: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    onEdit: (TransactionEntity) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("all") }
    var selectedAccountId by remember { mutableStateOf<String?>(null) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var searchAllTime by remember { mutableStateOf(false) }

    val accountsMap = remember(accounts) { accounts.associateBy { it.id } }
    val categoriesMap = remember(categories) { categories.associateBy { it.id } }

    val filteredTransactions = remember(
        transactions,
        selectedMonth,
        searchQuery,
        selectedType,
        selectedAccountId,
        selectedCategoryId,
        searchAllTime,
    ) {
        transactions.filter { tx ->
            if (!searchAllTime) {
                val matchesMonth = runCatching {
                    YearMonth.from(ZonedDateTime.parse(tx.effectiveAt)) == selectedMonth
                }.getOrDefault(false)
                if (!matchesMonth) return@filter false
            }

            if (selectedType != "all" && tx.transactionType != selectedType) {
                return@filter false
            }

            if (selectedAccountId != null && tx.accountId != selectedAccountId && tx.destinationAccountId != selectedAccountId) {
                return@filter false
            }

            if (selectedCategoryId != null && tx.categoryId != selectedCategoryId) {
                return@filter false
            }

            if (searchQuery.isNotBlank()) {
                val q = searchQuery.trim().lowercase(Italian)
                val note = (tx.note ?: "").lowercase(Italian)
                val catName = (categoriesMap[tx.categoryId]?.name ?: "").lowercase(Italian)
                val accName = (accountsMap[tx.accountId]?.name ?: "").lowercase(Italian)
                val destAccName = (tx.destinationAccountId?.let { accountsMap[it]?.name } ?: "").lowercase(Italian)
                val amountStr = (tx.amountCents / 100.0).toString()

                val matches = note.contains(q) || catName.contains(q) || accName.contains(q) || destAccName.contains(q) || amountStr.contains(q)
                if (!matches) return@filter false
            }

            true
        }
    }

    val hasActiveFilter = searchQuery.isNotBlank() || selectedType != "all" || selectedAccountId != null || selectedCategoryId != null || searchAllTime

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Cerca per nota, categoria, conto…", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Moss,
                    unfocusedBorderColor = Ink.copy(alpha = 0.15f),
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White
                ),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Text("✕", fontSize = 14.sp, color = Ink.copy(alpha = 0.5f))
                        }
                    } else {
                        Text("🔍", fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
                    }
                }
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = searchAllTime,
                        onClick = { searchAllTime = !searchAllTime },
                        label = { Text(if (searchAllTime) "Tutto lo storico" else "Solo questo mese", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = selectedType == "all",
                        onClick = { selectedType = "all" },
                        label = { Text("Tutti", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = selectedType == "expense",
                        onClick = { selectedType = if (selectedType == "expense") "all" else "expense" },
                        label = { Text("Spese", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = selectedType == "income",
                        onClick = { selectedType = if (selectedType == "income") "all" else "income" },
                        label = { Text("Entrate", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = selectedType == "transfer",
                        onClick = { selectedType = if (selectedType == "transfer") "all" else "transfer" },
                        label = { Text("Giroconti", fontSize = 12.sp) }
                    )
                }

                if (accounts.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedAccountId == null,
                            onClick = { selectedAccountId = null },
                            label = { Text("Tutti i conti", fontSize = 11.sp) }
                        )
                        accounts.forEach { acc ->
                            FilterChip(
                                selected = selectedAccountId == acc.id,
                                onClick = { selectedAccountId = if (selectedAccountId == acc.id) null else acc.id },
                                label = { Text(acc.name, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                if (categories.isNotEmpty() && selectedType != "transfer") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedCategoryId == null,
                            onClick = { selectedCategoryId = null },
                            label = { Text("Tutte le categorie", fontSize = 11.sp) }
                        )
                        categories.filter { selectedType == "all" || it.transactionType == selectedType }.forEach { cat ->
                            FilterChip(
                                selected = selectedCategoryId == cat.id,
                                onClick = { selectedCategoryId = if (selectedCategoryId == cat.id) null else cat.id },
                                label = { Text(cat.name, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }

        if (!searchAllTime) {
            item {
                MonthNavigator(selectedMonth, onMonthChange)
            }
        }

        if (hasActiveFilter) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${filteredTransactions.size} movimenti trovati",
                        fontSize = 12.sp,
                        color = Ink.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(
                        onClick = {
                            searchQuery = ""
                            selectedType = "all"
                            selectedAccountId = null
                            selectedCategoryId = null
                            searchAllTime = false
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text("Azzera filtri", fontSize = 11.sp, color = Coral)
                    }
                }
            }
        }

        if (filteredTransactions.isEmpty()) {
            item {
                EmptyState(
                    if (hasActiveFilter) "Nessun movimento corrisponde ai filtri selezionati."
                    else "Nessun movimento in questo mese."
                )
            }
        }

        items(filteredTransactions, key = { it.id }) { MovementRow(it, accounts, categories) { onEdit(it) } }
    }
}

@Composable
private fun MovementRow(item: TransactionEntity, accounts: List<AccountEntity>, categories: List<CategoryEntity>, onClick: (() -> Unit)? = null) {
    val category = categories.firstOrNull { it.id == item.categoryId }?.name
    val source = accounts.firstOrNull { it.id == item.accountId }?.name.orEmpty()
    val destination = accounts.firstOrNull { it.id == item.destinationAccountId }?.name
    val title = item.note?.takeIf { it.isNotBlank() } ?: category ?: "Trasferimento"
    val hasReceipt = item.receiptUrl != null || item.localReceiptPath != null
    val detail = if (item.transactionType == "transfer") "$source → $destination" else listOfNotNull(category, source).joinToString(" · ")
    val amount = when (item.transactionType) { "income" -> "+ ${money(item.amountCents)}"; "expense" -> "− ${money(item.amountCents)}"; else -> money(item.amountCents) }
    val color = when (item.transactionType) { "income" -> Moss; "expense" -> Coral; else -> Ink.copy(alpha = .7f) }
    Row(Modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(if (item.transactionType == "income") SoftMoss else Color(0xFFF9DED5), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Text(title.take(1).uppercase(), fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold)
                if (hasReceipt) {
                    Spacer(Modifier.width(6.dp))
                    Text("📎", fontSize = 12.sp)
                }
            }
            Text(detail, fontSize = 11.sp, color = Ink.copy(alpha = .6f))
        }
        Text(amount, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Summary(
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    selectedMonth: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
) {
    val month = monthTransactions(transactions, selectedMonth)
    val income = month.filter { it.transactionType == "income" }.sumOf { it.amountCents }
    val expenses = month.filter { it.transactionType == "expense" }.sumOf { it.amountCents }
    val balance = income - expenses

    val prevMonth = selectedMonth.minusMonths(1)
    val prevMonthTxs = monthTransactions(transactions, prevMonth)
    val prevIncome = prevMonthTxs.filter { it.transactionType == "income" }.sumOf { it.amountCents }
    val prevExpenses = prevMonthTxs.filter { it.transactionType == "expense" }.sumOf { it.amountCents }

    val projection = remember(accounts, transactions, selectedMonth) {
        CashFlowPredictor.calculate(accounts, transactions, selectedMonth)
    }

    var breakdownType by remember { mutableStateOf("expense") }

    val breakdownItems = remember(month, categories, breakdownType) {
        val targetTxs = month.filter { it.transactionType == breakdownType }
        val totalAmount = targetTxs.sumOf { it.amountCents }
        val grouped = targetTxs.groupBy { it.categoryId }
        val categoriesMap = categories.associateBy { it.id }

        grouped.map { (catId, txs) ->
            val sum = txs.sumOf { it.amountCents }
            val percent = if (totalAmount > 0) ((sum.toDouble() / totalAmount.toDouble()) * 100).toInt() else 0
            val name = catId?.let { categoriesMap[it]?.name } ?: "Senza categoria"
            CategoryBreakdownItem(
                categoryId = catId,
                name = name,
                amountCents = sum,
                percentage = percent,
                count = txs.size
            )
        }.sortedByDescending { it.amountCents }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            MonthNavigator(selectedMonth, onMonthChange)
        }

        item {
            CashFlowCard(projection)
        }

        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (balance >= 0) SoftMoss else Color(0xFFFDE8E4),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Risultato del mese", fontSize = 12.sp, color = Ink.copy(alpha = 0.7f), fontWeight = FontWeight.Medium)
                        Text(
                            text = if (balance >= 0) "Attivo" else "Disavanzo",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (balance >= 0) Moss else Coral
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = signedMoney(balance),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (balance >= 0) Moss else Coral
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Entrate", fontSize = 12.sp, color = Ink.copy(alpha = 0.7f))
                        Text(money(income), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Moss)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Spese", fontSize = 12.sp, color = Ink.copy(alpha = 0.7f))
                        Text(money(expenses), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Coral)
                    }
                }
            }
        }

        if (prevExpenses > 0 || prevIncome > 0) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Confronto con ${monthLabel(prevMonth)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                        val expenseDiff = expenses - prevExpenses
                        val expenseDiffText = if (expenseDiff < 0) {
                            "Spese diminuite di ${money(-expenseDiff)}"
                        } else if (expenseDiff > 0) {
                            "Spese aumentate di ${money(expenseDiff)}"
                        } else {
                            "Spese invariate"
                        }
                        val expenseDiffColor = if (expenseDiff <= 0) Moss else Coral

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Spese totali:", fontSize = 12.sp, color = Ink.copy(alpha = 0.7f))
                            Text(expenseDiffText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = expenseDiffColor)
                        }

                        val incomeDiff = income - prevIncome
                        val incomeDiffText = if (incomeDiff > 0) {
                            "Entrate aumentate di ${money(incomeDiff)}"
                        } else if (incomeDiff < 0) {
                            "Entrate diminuite di ${money(-incomeDiff)}"
                        } else {
                            "Entrate invariate"
                        }
                        val incomeDiffColor = if (incomeDiff >= 0) Moss else Coral

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Entrate totali:", fontSize = 12.sp, color = Ink.copy(alpha = 0.7f))
                            Text(incomeDiffText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = incomeDiffColor)
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Spaccato categorie", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = breakdownType == "expense",
                        onClick = { breakdownType = "expense" },
                        label = { Text("Spese", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = breakdownType == "income",
                        onClick = { breakdownType = "income" },
                        label = { Text("Entrate", fontSize = 11.sp) }
                    )
                }
            }
        }

        if (breakdownItems.isEmpty()) {
            item {
                EmptyState(if (breakdownType == "expense") "Nessuna spesa registrata in questo mese." else "Nessuna entrata registrata in questo mese.")
            }
        } else {
            items(breakdownItems, key = { it.categoryId ?: "none" }) { item ->
                CategoryBreakdownCard(item, breakdownType)
            }
        }

        item {
            Text(
                "I trasferimenti tra i tuoi conti non sono conteggiati come spese o entrate.",
                fontSize = 11.sp,
                color = Ink.copy(alpha = 0.55f),
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun CashFlowCard(projection: CashFlowProjection) {
    val healthColor = when (projection.health) {
        CashFlowHealth.HEALTHY -> Moss
        CashFlowHealth.WARNING -> Color(0xFFD9822B)
        CashFlowHealth.CRITICAL -> Coral
    }
    val healthBg = when (projection.health) {
        CashFlowHealth.HEALTHY -> SoftMoss
        CashFlowHealth.WARNING -> Color(0xFFFDF1DC)
        CashFlowHealth.CRITICAL -> Color(0xFFFDE8E4)
    }
    val healthLabel = when (projection.health) {
        CashFlowHealth.HEALTHY -> "🟢 Sostenibile"
        CashFlowHealth.WARNING -> "🟡 Attenzione"
        CashFlowHealth.CRITICAL -> "🔴 Rischio cassa"
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Intestazione con Icona, Titolo e Badge di salute
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🔮", fontSize = 16.sp)
                    Text(
                        text = if (projection.isCurrentMonth) "Proiezione Cash Flow" else "Consuntivo Cash Flow",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Ink
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = healthBg
                ) {
                    Text(
                        text = healthLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = healthColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Metrica Principale: Saldo a Fine Mese
            Column {
                Text(
                    text = if (projection.isCurrentMonth) {
                        "Saldo stimato a fine mese (${projection.daysInMonth} ${monthLabel(projection.targetMonth)})"
                    } else {
                        "Spesa totale del mese"
                    },
                    fontSize = 12.sp,
                    color = Ink.copy(alpha = 0.65f)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (projection.isCurrentMonth) {
                        money(projection.projectedEndOfMonthBalanceCents)
                    } else {
                        money(projection.actualExpensesCents)
                    },
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (projection.projectedEndOfMonthBalanceCents >= 0) Ink else Coral
                )
            }

            // Griglia con 2 box secondari: Risultato Stimato e Ritmo di Spesa
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Box 1: Risultato Netto Stimato
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Paper,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = if (projection.isCurrentMonth) "Risparmio stimato" else "Risultato netto",
                            fontSize = 11.sp,
                            color = Ink.copy(alpha = 0.65f)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = signedMoney(projection.projectedNetSavingsCents),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (projection.projectedNetSavingsCents >= 0) Moss else Coral
                        )
                    }
                }

                // Box 2: Ritmo Giornaliero (Burn Rate)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Paper,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "Ritmo giornaliero",
                            fontSize = 11.sp,
                            color = Ink.copy(alpha = 0.65f)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${money(projection.dailyBurnRateCents)} / g",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                        if (projection.burnRateDeltaPercent != null) {
                            val isHigher = projection.burnRateDeltaPercent > 0
                            val deltaColor = if (isHigher) Coral else Moss
                            val deltaPrefix = if (isHigher) "+" else ""
                            Text(
                                text = "$deltaPrefix${projection.burnRateDeltaPercent}% vs prec.",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = deltaColor
                            )
                        }
                    }
                }
            }

            // Box 3: Autonomia Finanziaria (Runway) se mese corrente e disponibile
            if (projection.isCurrentMonth && projection.runwayMonths != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (projection.runwayMonths >= 2.0) SoftMoss.copy(alpha = 0.5f) else Color(0xFFFDF1DC)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⏳ Autonomia cassa (Runway)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Ink.copy(alpha = 0.8f)
                        )
                        Text(
                            text = "~${String.format(Italian, "%.1f", projection.runwayMonths)} mesi (${projection.runwayDays} gg)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (projection.runwayMonths >= 2.0) Moss else Color(0xFFD9822B)
                        )
                    }
                }
            }

            // Barra di Progresso del Mese
            if (projection.isCurrentMonth) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val progress = (projection.daysElapsed.toFloat() / projection.daysInMonth.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = Moss,
                        trackColor = Paper
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Giorno ${projection.daysElapsed} di ${projection.daysInMonth}",
                            fontSize = 10.sp,
                            color = Ink.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "${projection.daysRemaining} giorni rimasti",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

private data class CategoryBreakdownItem(
    val categoryId: String?,
    val name: String,
    val amountCents: Long,
    val percentage: Int,
    val count: Int
)

@Composable
private fun CategoryBreakdownCard(item: CategoryBreakdownItem, type: String) {
    val barColor = if (type == "expense") Coral else Moss
    val countLabel = if (item.count == 1) "1 movimento" else "${item.count} movimenti"

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("(${item.percentage}%)", fontSize = 12.sp, color = Ink.copy(alpha = 0.55f))
                }
                Text(
                    text = money(item.amountCents),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (type == "expense") Coral else Moss
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(Color(0xFFF0EBE1), RoundedCornerShape(4.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = (item.percentage.coerceIn(1, 100) / 100f))
                        .fillMaxHeight()
                        .background(barColor, RoundedCornerShape(4.dp))
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(countLabel, fontSize = 11.sp, color = Ink.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun Settings(
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    transactions: List<TransactionEntity>,
    dao: CassaDao,
    onEditAccount: (AccountEntity) -> Unit,
    onEditCategories: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val count = BackupManager.exportToJson(context, uri, accounts, categories, transactions)
            if (count.isSuccess) {
                android.widget.Toast.makeText(context, "Backup salvato (${count.getOrNull()} movimenti)", android.widget.Toast.LENGTH_LONG).show()
            } else {
                android.widget.Toast.makeText(context, "Errore: ${count.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val count = BackupManager.importFromJson(context, uri, dao)
                if (count.isSuccess) {
                    android.widget.Toast.makeText(context, "Ripristino completato (${count.getOrNull()} movimenti)", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    android.widget.Toast.makeText(context, "Errore: ${count.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    var biometricEnabled by remember {
        mutableStateOf(it.francesconatale.cassa.security.BiometricSecurityManager.isBiometricEnabled(context))
    }
    val canUseBiometric = remember {
        it.francesconatale.cassa.security.BiometricSecurityManager.canAuthenticate(context)
    }
    var smsCaptureEnabled by remember {
        mutableStateOf(it.francesconatale.cassa.sms.SmsCaptureManager.isSmsCaptureEnabled(context))
    }
    var pushCaptureEnabled by remember {
        mutableStateOf(it.francesconatale.cassa.notification.NotificationCaptureManager.isNotificationCaptureEnabled(context))
    }
    var pushListenerGranted by remember {
        mutableStateOf(it.francesconatale.cassa.notification.NotificationCaptureManager.isNotificationAccessGranted(context))
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                pushListenerGranted = it.francesconatale.cassa.notification.NotificationCaptureManager.isNotificationAccessGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val smsPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[android.Manifest.permission.RECEIVE_SMS] == true
        if (granted) {
            it.francesconatale.cassa.sms.SmsCaptureManager.setSmsCaptureEnabled(context, true)
            it.francesconatale.cassa.sms.SmsCaptureManager.ensureNotificationChannel(context)
            smsCaptureEnabled = true
        } else {
            it.francesconatale.cassa.sms.SmsCaptureManager.setSmsCaptureEnabled(context, false)
            smsCaptureEnabled = false
        }
    }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("0.6.6")
    }
    val versionCode = remember {
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
        }.getOrDefault(16L)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        accounts.forEach { account ->
            Surface(onClick = { onEditAccount(account) }, shape = RoundedCornerShape(16.dp), color = Color.White) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(account.name, fontWeight = FontWeight.SemiBold)
                    Text(money(account.openingBalanceCents), color = Moss, fontWeight = FontWeight.Bold)
                }
            }
        }
        Surface(onClick = onEditCategories, shape = RoundedCornerShape(16.dp), color = Color.White) {
            Text("${categories.size} categorie", Modifier.fillMaxWidth().padding(18.dp), fontWeight = FontWeight.SemiBold)
        }
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(Moss, RoundedCornerShape(50)))
                    Text("Modalità Standalone (100% Offline)", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                Text("I tuoi dati finanziari restano esclusivamente su questo smartphone. Nessun server o connessione Internet richiesta.", fontSize = 11.sp, color = Ink.copy(alpha = .55f))
            }
        }
        Surface(
            onClick = {
                val dateStr = LocalDate.now().toString()
                exportLauncher.launch("cassa-backup-$dateStr.json")
            },
            shape = RoundedCornerShape(16.dp),
            color = Color.White
        ) {
            Row(
                Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Esporta Backup (JSON)", fontWeight = FontWeight.SemiBold)
                    Text("Salva conti, categorie e movimenti per sicurezza o per cambiare telefono", fontSize = 11.sp, color = Ink.copy(alpha = .55f))
                }
                Text("⤓", fontWeight = FontWeight.Bold, color = Moss, fontSize = 20.sp)
            }
        }
        Surface(
            onClick = {
                importLauncher.launch(arrayOf("application/json", "*/*"))
            },
            shape = RoundedCornerShape(16.dp),
            color = Color.White
        ) {
            Row(
                Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Ripristina da Backup (JSON)", fontWeight = FontWeight.SemiBold)
                    Text("Importa un archivio JSON precedentemente esportato", fontSize = 11.sp, color = Ink.copy(alpha = .55f))
                }
                Text("⤒", fontWeight = FontWeight.Bold, color = Coral, fontSize = 20.sp)
            }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
            Text("${transactions.size} movimenti salvati", Modifier.fillMaxWidth().padding(18.dp), fontWeight = FontWeight.SemiBold)
        }
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Blocco impronta digitale", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (canUseBiometric) {
                            if (biometricEnabled) "Attivo (richiesto all'apertura)" else "Disattivato"
                        } else {
                            "Non disponibile o non configurato sul dispositivo"
                        },
                        fontSize = 11.sp,
                        color = Ink.copy(alpha = .55f)
                    )
                }
                Switch(
                    checked = biometricEnabled,
                    enabled = canUseBiometric,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Coral,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFDCD6C9)
                    ),
                    onCheckedChange = { targetState ->
                        if (targetState) {
                            val activity = context as? androidx.fragment.app.FragmentActivity
                            if (activity != null) {
                                it.francesconatale.cassa.security.BiometricSecurityManager.authenticate(
                                    activity = activity,
                                    title = "Attiva blocco biometrico",
                                    subtitle = "Conferma la tua impronta per abilitare la protezione",
                                    onSuccess = {
                                        it.francesconatale.cassa.security.BiometricSecurityManager.setBiometricEnabled(context, true)
                                        biometricEnabled = true
                                    }
                                )
                            }
                        } else {
                            it.francesconatale.cassa.security.BiometricSecurityManager.setBiometricEnabled(context, false)
                            biometricEnabled = false
                        }
                    }
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Pagamenti da SMS", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (smsCaptureEnabled) "Attivo (notifica rapida all'arrivo dell'SMS)" else "Disattivato",
                        fontSize = 11.sp,
                        color = Ink.copy(alpha = .55f)
                    )
                }
                Switch(
                    checked = smsCaptureEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Coral,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFDCD6C9)
                    ),
                    onCheckedChange = { targetState ->
                        if (targetState) {
                            val perms = mutableListOf(android.Manifest.permission.RECEIVE_SMS)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                            smsPermissionLauncher.launch(perms.toTypedArray())
                        } else {
                            it.francesconatale.cassa.sms.SmsCaptureManager.setSmsCaptureEnabled(context, false)
                            smsCaptureEnabled = false
                        }
                    }
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Text("Notifiche Push Bancarie", fontWeight = FontWeight.SemiBold)
                    val statusText = if (!pushCaptureEnabled) {
                        "Disattivato"
                    } else if (!pushListenerGranted) {
                        "Permesso di sistema mancante"
                    } else {
                        "Attivo (Google Wallet, MPS, Fineco, PayPal)"
                    }
                    Text(
                        statusText,
                        fontSize = 11.sp,
                        color = if (pushCaptureEnabled && !pushListenerGranted) Coral else Ink.copy(alpha = .55f)
                    )
                    if (pushCaptureEnabled && !pushListenerGranted) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                context.startActivity(it.francesconatale.cassa.notification.NotificationCaptureManager.getNotificationAccessSettingsIntent())
                            },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                        ) {
                            Text("Autorizza accesso notifiche ↗", fontSize = 11.sp, color = Moss, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Switch(
                    checked = pushCaptureEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Coral,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFDCD6C9)
                    ),
                    onCheckedChange = { targetState ->
                        if (targetState) {
                            it.francesconatale.cassa.notification.NotificationCaptureManager.setNotificationCaptureEnabled(context, true)
                            it.francesconatale.cassa.notification.NotificationCaptureManager.ensureNotificationChannel(context)
                            pushCaptureEnabled = true
                            if (!it.francesconatale.cassa.notification.NotificationCaptureManager.isNotificationAccessGranted(context)) {
                                context.startActivity(it.francesconatale.cassa.notification.NotificationCaptureManager.getNotificationAccessSettingsIntent())
                            }
                        } else {
                            it.francesconatale.cassa.notification.NotificationCaptureManager.setNotificationCaptureEnabled(context, false)
                            pushCaptureEnabled = false
                        }
                    }
                )
            }
        }
        Surface(
            onClick = { CsvExporter.exportAndShare(context, transactions, accounts, categories) },
            shape = RoundedCornerShape(16.dp),
            color = Color.White
        ) {
            Row(
                Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Esporta CSV", fontWeight = FontWeight.SemiBold)
                    Text("Condividi o salva tutti i movimenti in formato tabellare", fontSize = 11.sp, color = Ink.copy(alpha = .55f))
                }
                Text("↗", fontWeight = FontWeight.Bold, color = Moss, fontSize = 18.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Cassa v$versionName (build $versionCode)",
            fontSize = 12.sp,
            color = Ink.copy(alpha = .45f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountBalanceEditor(account: AccountEntity, onClose: () -> Unit, onSave: (Long) -> Unit) {
    var value by remember(account.id) { mutableStateOf(BigDecimal(account.openingBalanceCents).movePointLeft(2).toPlainString().replace('.', ',')) }
    val cents = signedAmountToCents(value)
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Paper) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(account.name, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("Saldo iniziale", color = Ink.copy(alpha = .65f))
            OutlinedTextField(
                value = value,
                onValueChange = { candidate -> if (candidate.matches(Regex("-?\\d{0,9}([,.]\\d{0,2})?"))) value = candidate },
                label = { Text("Importo") },
                suffix = { Text("EUR") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Il saldo iniziale è la base del conto. Entrate, spese e trasferimenti saranno calcolati a partire da questo valore.", fontSize = 12.sp, color = Ink.copy(alpha = .65f))
            Button(
                enabled = cents != null,
                onClick = { onSave(cents!!) },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
            ) { Text("Salva saldo") }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MovementEditor(
    accounts: List<AccountEntity>, categories: List<CategoryEntity>, initial: TransactionEntity?,
    isDraft: Boolean = false,
    onClose: () -> Unit, onSave: (TransactionEntity) -> Unit, onDelete: (TransactionEntity) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val txId = remember(initial?.id) { initial?.id ?: UUID.randomUUID().toString() }
    val initialType = listOf("expense", "income", "transfer").indexOf(initial?.transactionType).coerceAtLeast(0)
    var type by rememberSaveable(initial?.id) { mutableIntStateOf(initialType) }
    var amount by rememberSaveable(initial?.id) { mutableStateOf(initial?.let { BigDecimal(it.amountCents).movePointLeft(2).toPlainString().replace('.', ',') } ?: "") }
    var note by rememberSaveable(initial?.id) { mutableStateOf(initial?.note.orEmpty()) }
    var accountId by rememberSaveable(initial?.id) { mutableStateOf(initial?.accountId ?: accounts.first().id) }
    var destinationId by rememberSaveable(initial?.id) { mutableStateOf(initial?.destinationAccountId ?: accounts.first { it.id != accountId }.id) }
    var effectiveAt by rememberSaveable(initial?.id) { mutableStateOf(initial?.effectiveAt ?: Instant.now().toString()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var localReceiptPath by rememberSaveable(initial?.id) { mutableStateOf(initial?.localReceiptPath) }
    var receiptUrl by rememberSaveable(initial?.id) { mutableStateOf(initial?.receiptUrl) }
    var receiptPaperlessId by rememberSaveable(initial?.id) { mutableStateOf(initial?.receiptPaperlessId) }
    var tempCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var isOcrScanning by rememberSaveable { mutableStateOf(false) }
    var ocrBannerText by rememberSaveable { mutableStateOf<String?>(null) }
    var candidateAmounts by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }

    val typeName = listOf("expense", "income", "transfer")[type]
    val availableCategories = categories.filter { it.transactionType == typeName }
    var categoryId by rememberSaveable(type, initial?.id) { mutableStateOf(initial?.categoryId?.takeIf { id -> availableCategories.any { it.id == id } } ?: availableCategories.firstOrNull()?.id) }

    fun processReceiptImage(file: File) {
        localReceiptPath = file.absolutePath
        scope.launch {
            isOcrScanning = true
            ocrBannerText = null
            candidateAmounts = emptyList()
            try {
                val parsed = ReceiptOcrParser.parseFromUri(context, Uri.fromFile(file))
                if (parsed.hasData) {
                    candidateAmounts = parsed.candidateAmountsFormatted
                    val highlights = mutableListOf<String>()

                    if (parsed.formattedAmount != null) {
                        amount = parsed.formattedAmount!!
                        highlights.add("€ ${parsed.formattedAmount}")
                    }

                    if (!parsed.merchant.isNullOrBlank() && (note.isBlank() || note.length < parsed.merchant!!.length)) {
                        note = parsed.merchant!!
                        highlights.add(parsed.merchant!!)
                    }

                    if (parsed.suggestedCategory != null) {
                        val matchedCat = availableCategories.firstOrNull {
                            it.name.equals(parsed.suggestedCategory, ignoreCase = true)
                        } ?: availableCategories.firstOrNull {
                            it.name.contains(parsed.suggestedCategory!!, ignoreCase = true) ||
                            parsed.suggestedCategory!!.contains(it.name, ignoreCase = true)
                        }
                        if (matchedCat != null) {
                            categoryId = matchedCat.id
                        }
                    }

                    if (parsed.dateIso != null && initial == null) {
                        effectiveAt = parsed.dateIso
                        if (parsed.dateDisplay != null) {
                            highlights.add(parsed.dateDisplay!!)
                        }
                    }

                    if (highlights.isNotEmpty()) {
                        ocrBannerText = "✨ Dati scontrino rilevati: " + highlights.joinToString(" • ")
                    }
                }
            } catch (_: Exception) {
            } finally {
                isOcrScanning = false
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        MainActivity.markActivityResultHandled()
        if (success && tempCameraUri != null) {
            val receiptsDir = File(context.filesDir, "receipts").apply { mkdirs() }
            val destFile = File(receiptsDir, "${txId}.jpg")
            context.contentResolver.openInputStream(tempCameraUri!!)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            processReceiptImage(destFile)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        MainActivity.markActivityResultHandled()
        if (uri != null) {
            val receiptsDir = File(context.filesDir, "receipts").apply { mkdirs() }
            val destFile = File(receiptsDir, "${txId}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            processReceiptImage(destFile)
        }
    }

    fun openReceipt() {
        try {
            if (localReceiptPath != null && File(localReceiptPath!!).exists()) {
                val file = File(localReceiptPath!!)
                val uri = FileProvider.getUriForFile(
                    context,
                    "it.francesconatale.cassa.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                MainActivity.markActivityLaunching()
                context.startActivity(intent)
            } else if (!receiptUrl.isNullOrBlank()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(receiptUrl))
                MainActivity.markActivityLaunching()
                context.startActivity(intent)
            }
        } catch (_: Exception) {
        }
    }

    val cents = amountToCents(amount)

    ModalBottomSheet(onDismissRequest = onClose, containerColor = Paper) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(if (isDraft) "Registra movimento rilevato" else if (initial == null) "Nuovo movimento" else "Modifica movimento", fontSize = 25.sp, fontWeight = FontWeight.Bold) }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Spesa", "Entrata", "Trasferimento").forEachIndexed { index, label ->
                        SegmentedButton(selected = type == index, onClick = { type = index }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(label, fontSize = 11.sp) }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { value -> if (value.matches(Regex("\\d{0,7}([,.]\\d{0,2})?"))) amount = value },
                    label = { Text("Importo") }, suffix = { Text("EUR") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (candidateAmounts.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("💡 Trovati:", fontSize = 11.sp, color = Ink.copy(alpha = 0.6f))
                        candidateAmounts.forEach { cand ->
                            FilterChip(
                                selected = amount == cand,
                                onClick = { amount = cand },
                                label = { Text("€ $cand", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Moss.copy(alpha = 0.15f),
                                    selectedLabelColor = Moss
                                )
                            )
                        }
                    }
                }
            }
            item { SelectorRow(if (type == 2) "Da" else "Conto", accounts.map { SelectorItem(it.id, it.name) }, accountId) { selected -> accountId = selected; if (destinationId == selected) destinationId = accounts.first { it.id != selected }.id } }
            if (type == 2) item { SelectorRow("Verso", accounts.filter { it.id != accountId }.map { SelectorItem(it.id, it.name) }, destinationId) { destinationId = it } }
            if (type != 2) item { SelectorRow("Categoria", availableCategories.map { SelectorItem(it.id, it.name) }, categoryId.orEmpty()) { categoryId = it } }
            item { OutlinedTextField(value = note, onValueChange = { note = it.take(80) }, label = { Text("Nota facoltativa") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Scontrino / Ricevuta (Paperless)", fontSize = 12.sp, color = Ink.copy(alpha = .65f), fontWeight = FontWeight.Bold)
                        if (localReceiptPath != null || receiptUrl != null) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("📎", fontSize = 16.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (receiptUrl != null) "Scontrino sincronizzato" else "Scontrino pronto (offline)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (receiptUrl != null) Moss else Coral
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { openReceipt() }) { Text("Vedi", fontSize = 12.sp) }
                                    if (localReceiptPath != null) {
                                        TextButton(onClick = { File(localReceiptPath!!).takeIf { it.exists() }?.let { processReceiptImage(it) } }) {
                                            Text("Riscansiona", fontSize = 12.sp, color = Moss)
                                        }
                                    }
                                    TextButton(onClick = {
                                        localReceiptPath?.let { File(it).delete() }
                                        localReceiptPath = null
                                        receiptUrl = null
                                        receiptPaperlessId = null
                                        ocrBannerText = null
                                    }) { Text("Rimuovi", fontSize = 12.sp, color = Coral) }
                                }
                            }
                            if (isOcrScanning) {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = Moss
                                    )
                                    Text(
                                        "Riconoscimento scontrino con AI on-device...",
                                        fontSize = 11.sp,
                                        color = Ink.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            if (ocrBannerText != null) {
                                Surface(
                                    color = Moss.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            ocrBannerText!!,
                                            fontSize = 11.sp,
                                            color = Moss,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = { ocrBannerText = null },
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier.height(24.dp)
                                        ) {
                                            Text("✕", fontSize = 11.sp, color = Ink.copy(alpha = 0.5f))
                                        }
                                    }
                                }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        MainActivity.markActivityLaunching()
                                        val camDir = File(context.cacheDir, "camera").apply { mkdirs() }
                                        val camFile = File(camDir, "temp_${txId}.jpg")
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "it.francesconatale.cassa.fileprovider",
                                            camFile
                                        )
                                        tempCameraUri = uri
                                        cameraLauncher.launch(uri)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("📷 Scatta", fontSize = 12.sp) }
                                OutlinedButton(
                                    onClick = {
                                        MainActivity.markActivityLaunching()
                                        galleryLauncher.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("🖼️ Galleria", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    enabled = cents != null && (type == 2 || categoryId != null),
                    onClick = {
                        val now = Instant.now().toString()
                        onSave(TransactionEntity(
                            id = txId,
                            transactionType = typeName,
                            amountCents = cents!!,
                            accountId = accountId,
                            destinationAccountId = if (type == 2) destinationId else null,
                            categoryId = if (type == 2) null else categoryId,
                            effectiveAt = effectiveAt,
                            note = note.trim().ifBlank { null },
                            receiptPaperlessId = receiptPaperlessId,
                            receiptUrl = receiptUrl,
                            localReceiptPath = localReceiptPath,
                            revision = initial?.revision ?: 0,
                            createdAt = initial?.createdAt ?: now,
                            updatedAt = now,
                        ))
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Coral),
                ) { Text("Salva ${listOf("spesa", "entrata", "trasferimento")[type]}") }
            }
            if (initial != null && !isDraft) item {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = Coral)) { Text("Elimina movimento") }
            }
            item { Text("Salvato subito sul telefono, anche senza rete.", fontSize = 11.sp, color = Ink.copy(alpha = .55f), modifier = Modifier.padding(bottom = 20.dp).fillMaxWidth()) }
        }
    }
    if (confirmDelete && initial != null && !isDraft) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminare il movimento?") },
            text = { Text("Il saldo verrà ricalcolato. L'operazione sarà sincronizzata con il server quando disponibile.") },
            confirmButton = { TextButton(onClick = { onDelete(initial) }) { Text("Elimina", color = Coral) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryEditor(
    categories: List<CategoryEntity>, onClose: () -> Unit,
    onSave: (CategoryEntity) -> Unit, onDelete: (CategoryEntity) -> Unit,
) {
    var selected by remember { mutableStateOf<CategoryEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun load(category: CategoryEntity?) {
        selected = category
        name = category?.name.orEmpty()
        type = if (category?.transactionType == "income") 1 else 0
    }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Paper) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Categorie", fontSize = 25.sp, fontWeight = FontWeight.Bold) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { load(null) }, label = { Text("+ Nuova") })
                    categories.forEach { category -> FilterChip(selected = selected?.id == category.id, onClick = { load(category) }, label = { Text(category.name) }) }
                }
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Spesa", "Entrata").forEachIndexed { index, label ->
                        SegmentedButton(selected = type == index, onClick = { type = index }, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
                    }
                }
            }
            item { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Nome categoria") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val current = selected
                        onSave(CategoryEntity(current?.id ?: UUID.randomUUID().toString(), name.trim(), if (type == 0) "expense" else "income", current?.sortOrder ?: (categories.maxOfOrNull { it.sortOrder } ?: 0) + 10, true, current?.revision ?: 0))
                        load(null)
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Coral),
                ) { Text(if (selected == null) "Aggiungi categoria" else "Salva categoria") }
            }
            if (selected != null) item { TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = Coral)) { Text("Elimina categoria") } }
            item { Text("Le categorie già usate non possono essere eliminate.", fontSize = 11.sp, color = Ink.copy(alpha = .55f), modifier = Modifier.padding(bottom = 20.dp)) }
        }
    }
    if (confirmDelete && selected != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminare la categoria?") },
            text = { Text("L'eliminazione sarà consentita soltanto se nessun movimento la utilizza.") },
            confirmButton = { TextButton(onClick = { onDelete(selected!!); confirmDelete = false; load(null) }) { Text("Elimina", color = Coral) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } },
        )
    }
}

private data class SelectorItem(val id: String, val name: String)

@Composable
private fun SelectorRow(label: String, values: List<SelectorItem>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = Ink.copy(alpha = .65f))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { item -> FilterChip(selected = item.id == selected, onClick = { onSelect(item.id) }, label = { Text(item.name) }) }
        }
    }
}

@Composable private fun EmptyState(text: String) = Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) { Text(text, Modifier.fillMaxWidth().padding(18.dp), color = Ink.copy(alpha = .65f)) }

private fun accountBalance(account: AccountEntity, transactions: List<TransactionEntity>): Long = account.openingBalanceCents + transactions.sumOf {
    when {
        it.transactionType == "income" && it.accountId == account.id -> it.amountCents
        it.transactionType == "expense" && it.accountId == account.id -> -it.amountCents
        it.transactionType == "transfer" && it.accountId == account.id -> -it.amountCents
        it.transactionType == "transfer" && it.destinationAccountId == account.id -> it.amountCents
        else -> 0
    }
}

private fun monthTransactions(values: List<TransactionEntity>, selectedMonth: YearMonth): List<TransactionEntity> =
    values.filter { runCatching { YearMonth.from(ZonedDateTime.parse(it.effectiveAt)) == selectedMonth }.getOrDefault(false) }

@Composable
private fun MonthNavigator(selectedMonth: YearMonth, onMonthChange: (YearMonth) -> Unit) {
    val current = YearMonth.now()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onMonthChange(selectedMonth.minusMonths(1)) }) { Text("‹", fontSize = 26.sp) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(monthLabel(selectedMonth).uppercase(Italian), color = Moss, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (selectedMonth != current) TextButton(onClick = { onMonthChange(current) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Oggi", fontSize = 11.sp) }
        }
        TextButton(onClick = { onMonthChange(selectedMonth.plusMonths(1)) }, enabled = selectedMonth < current) { Text("›", fontSize = 26.sp) }
    }
}

private fun amountToCents(value: String): Long? = runCatching { BigDecimal(value.replace(',', '.')).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact().takeIf { it > 0 } }.getOrNull()
private fun signedAmountToCents(value: String): Long? = runCatching { BigDecimal(value.replace(',', '.')).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact() }.getOrNull()
private fun money(cents: Long): String = NumberFormat.getCurrencyInstance(Italian).format(BigDecimal(cents).movePointLeft(2)).trim()
private fun signedMoney(cents: Long): String = when { cents > 0 -> "+ ${money(cents)}"; cents < 0 -> "− ${money(-cents)}"; else -> money(0) }
private fun monthLabel(value: YearMonth): String = "${value.month.getDisplayName(TextStyle.FULL, Italian).replaceFirstChar { it.titlecase(Italian) }} ${value.year}"

@Composable
private fun OfflineBadge() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SoftMoss.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(Moss, RoundedCornerShape(50))
            )
            Text(
                text = "Offline",
                fontSize = 11.sp,
                color = Moss,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
