package pos

import pos.data.Item
import pos.data.Customer
import pos.data.Product
import pos.data.ItemRepository
import pos.data.CustomerRepository
import pos.data.ReportsRepository
import pos.data.ProductRepository
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll


// ...existing code...

private val dobStorageFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val dobDisplayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

private fun parseDobOrNull(dateString: String): LocalDate? {
    val trimmed = dateString.trim()
    if (trimmed.isBlank()) return null
    return try {
        LocalDate.parse(trimmed, dobStorageFormatter)
    } catch (_: Exception) {
        try {
            LocalDate.parse(trimmed, dobDisplayFormatter)
        } catch (_: Exception) {
            null
        }
    }
}

private fun formatDobForDisplay(dob: String?): String {
    if (dob.isNullOrBlank()) return "-"
    val parsed = parseDobOrNull(dob) ?: return dob
    return parsed.format(dobDisplayFormatter)
}

private fun isValidDate(dateString: String): Boolean {
    if (dateString.isBlank()) return true
    return parseDobOrNull(dateString) != null
}

@Composable
fun DatePickerDialog(
    initialDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        val monthLabelFormatter = remember { DateTimeFormatter.ofPattern("MMMM") }

        var currentMonth by remember { mutableStateOf(YearMonth.from(initialDate)) }
        var selectedDate by remember { mutableStateOf(initialDate) }
        var directInput by remember { mutableStateOf(initialDate.format(dobDisplayFormatter)) }
        var inputError by remember { mutableStateOf(false) }
        var showYearDropdown by remember { mutableStateOf(false) }

        fun changeMonth(deltaMonths: Long) {
            currentMonth = currentMonth.plusMonths(deltaMonths)
            val maxDay = currentMonth.lengthOfMonth()
            if (selectedDate.year == currentMonth.year && selectedDate.monthValue == currentMonth.monthValue) {
                if (selectedDate.dayOfMonth > maxDay) {
                    selectedDate = selectedDate.withDayOfMonth(maxDay)
                }
            }
        }

        fun updateFromDirectInput() {
            val parsed = parseDobOrNull(directInput)
            if (parsed != null) {
                selectedDate = parsed
                currentMonth = YearMonth.from(parsed)
                inputError = false
            } else {
                inputError = directInput.isNotBlank()
            }
        }

        Card(elevation = 8.dp, modifier = Modifier.padding(24.dp).width(500.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Select Date of Birth", style = MaterialTheme.typography.h6)

                // Direct input field - EASIEST METHOD
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = directInput,
                        onValueChange = { 
                            directInput = it
                            inputError = false
                        },
                        label = { Text("Type Date") },
                        placeholder = { Text("10-02-1975") },
                        isError = inputError,
                        modifier = Modifier
                            .weight(1f)
                            .onKeyEvent { event ->
                                if (event.key == Key.Enter) {
                                    updateFromDirectInput()
                                    true
                                } else false
                            }
                    )
                    Button(
                        onClick = { 
                            updateFromDirectInput()
                        },
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text("Set")
                    }
                }
                if (inputError) {
                    Text("Invalid format. Use DD-MM-YYYY (e.g., 10-02-1975)", 
                        color = MaterialTheme.colors.error, 
                        style = MaterialTheme.typography.caption)
                }

                Divider()

                // Calendar navigation with year selection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { changeMonth(-12) }, modifier = Modifier.width(45.dp)) { Text("<<", fontSize = 12.sp) }
                    Button(onClick = { changeMonth(-1) }, modifier = Modifier.width(40.dp)) { Text("<", fontSize = 12.sp) }
                    
                    // Month display
                    Text(
                        currentMonth.atDay(1).format(monthLabelFormatter),
                        style = MaterialTheme.typography.subtitle1,
                        modifier = Modifier.weight(0.5f),
                        textAlign = TextAlign.Center
                    )
                    
                    // Year selector with dropdown
                    Box(modifier = Modifier.weight(0.5f)) {
                        Button(
                            onClick = { showYearDropdown = !showYearDropdown },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${currentMonth.year}", fontSize = 14.sp)
                        }
                        DropdownMenu(
                            expanded = showYearDropdown,
                            onDismissRequest = { showYearDropdown = false },
                            modifier = Modifier.height(300.dp)
                        ) {
                            val currentYear = LocalDate.now().year
                            val scrollState = rememberScrollState()
                            
                            LazyColumn {
                                items((currentYear downTo (currentYear - 100)).toList()) { year ->
                                    DropdownMenuItem(onClick = {
                                        currentMonth = YearMonth.of(year, currentMonth.monthValue)
                                        selectedDate = currentMonth.atDay(selectedDate.dayOfMonth.coerceAtMost(currentMonth.lengthOfMonth()))
                                        directInput = selectedDate.format(dobDisplayFormatter)
                                        showYearDropdown = false
                                    }) {
                                        Text(year.toString())
                                    }
                                }
                            }
                        }
                    }
                    
                    Button(onClick = { changeMonth(1) }, modifier = Modifier.width(40.dp)) { Text(">", fontSize = 12.sp) }
                    Button(onClick = { changeMonth(12) }, modifier = Modifier.width(45.dp)) { Text(">>", fontSize = 12.sp) }
                }

                // Calendar grid
                val weekDays = listOf("M", "T", "W", "T", "F", "S", "S")
                Row(modifier = Modifier.fillMaxWidth()) {
                    weekDays.forEach { label ->
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.caption
                        )
                    }
                }

                val firstDayIndex = currentMonth.atDay(1).dayOfWeek.value - 1
                val daysInMonth = currentMonth.lengthOfMonth()

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0 until 6) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0 until 7) {
                                val cellIndex = row * 7 + col
                                val dayNumber = cellIndex - firstDayIndex + 1

                                if (dayNumber in 1..daysInMonth) {
                                    val cellDate = currentMonth.atDay(dayNumber)
                                    val isSelected = cellDate == selectedDate
                                    Button(
                                        onClick = { 
                                            selectedDate = cellDate
                                            directInput = cellDate.format(dobDisplayFormatter)
                                        },
                                        modifier = Modifier.weight(1f).height(32.dp),
                                        colors = androidx.compose.material.ButtonDefaults.buttonColors(
                                            backgroundColor = if (isSelected) MaterialTheme.colors.primary else MaterialTheme.colors.surface
                                        )
                                    ) {
                                        Text(
                                            dayNumber.toString(),
                                            color = if (isSelected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                                            fontSize = 11.sp
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.weight(1f).height(32.dp))
                                }
                            }
                        }
                    }
                }

                Text("Selected: ${selectedDate.format(dobDisplayFormatter)}", 
                    style = MaterialTheme.typography.body1,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDateSelected(selectedDate) }, modifier = Modifier.weight(1f)) { Text("OK") }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                }
            }
        }
    }
}

fun main() = application {
    try {
        pos.sync.SyncServer.start(8080)
    } catch (ex: Exception) {
        println("Sync server failed to start: ${ex.message}")
    }

    Window(onCloseRequest = { exitApplication() }, title = "POS Desktop") {
        App()
    }
}

@Composable
fun App() {
    val itemRepo = remember { ItemRepository() }
    val saleRepo = remember { pos.data.SaleRepository() }
    val customerRepo = remember { CustomerRepository() }
    val productRepo = remember { ProductRepository() }
    val reportsRepo = remember { ReportsRepository() }

    var current by remember { mutableStateOf("Dashboard") }
    var products by remember { mutableStateOf(productRepo.list()) }
    var customers by remember { mutableStateOf(customerRepo.list()) }
    val itemsCount by remember { mutableStateOf(itemRepo.list().size) }

    fun refreshProducts() {
        products = productRepo.list()
    }

    fun refreshCustomers() {
        customers = customerRepo.list()
    }

    MaterialTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(current = current, onSelect = { current = it })
            Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
            Box(modifier = Modifier.fillMaxSize()) {
                when (current) {
                    "Dashboard" -> Dashboard(
                        itemsCount = itemsCount,
                        customersCount = customers.size,
                        reportsRepo = reportsRepo,
                        onNavigate = { current = it }
                    )

                    "POS" -> POSScreen(itemRepo = itemRepo, saleRepo = saleRepo, onSave = { refreshProducts() })

                    "Inventory" -> InventoryScreen(products = products, onRefresh = { refreshProducts() })

                    "Products" -> ProductsScreen(productRepo = productRepo, onUpdate = { refreshProducts() })

                    "Customers" -> CustomersScreen(customers = customers, onAdd = { newCustomer ->
                        val saved = customerRepo.upsert(newCustomer)
                        refreshCustomers()
                        saved
                    })

                    "Reports" -> ReportsScreen(reportsRepo)
                }
            }
        }
    }
}

@Composable
private fun NavigationRail(current: String, onSelect: (String) -> Unit) {
    val items = listOf("Dashboard", "POS", "Inventory", "Products", "Customers", "Reports")
    Column(modifier = Modifier.width(180.dp).padding(16.dp), horizontalAlignment = Alignment.Start) {
        items.forEach { label ->
            val isActive = label == current
            Button(onClick = { onSelect(label) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(if (isActive) "• $label" else label)
            }
        }
    }
}

@Composable
private fun Dashboard(itemsCount: Int, customersCount: Int, reportsRepo: ReportsRepository, onNavigate: (String) -> Unit) {
    val todaySales by remember { mutableStateOf(reportsRepo.dailySales()) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Overview", style = MaterialTheme.typography.h5)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(title = "Items", value = itemsCount.toString())
            StatCard(title = "Customers", value = customersCount.toString())
            StatCard(title = "Today Sales", value = todaySales.sumOf { it.total }.toString())
        }
        Button(onClick = { onNavigate("Products") }) { Text("Manage Products") }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Today Receipts", style = MaterialTheme.typography.subtitle1)
                if (todaySales.isEmpty()) {
                    Text("No sales yet today")
                } else {
                    todaySales.forEach { sale ->
                        Text("#${sale.id} • ${sale.customerName ?: "Walk-in"} • Total ${sale.total} • Paid ${sale.paid}")
                    }
                }
            }
        }
    }
}

@Composable
private fun InventoryScreen(products: List<Product>, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) {
        onRefresh()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Inventory", style = MaterialTheme.typography.h5)
            Button(onClick = { onRefresh() }) {
                Text("Refresh")
            }
        }
        
        // Total stock value summary
        val totalStockValue = products.sumOf { it.buyPrice * it.stock }
        val totalSellValue = products.sumOf { it.sellPrice * it.stock }
        Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.1f)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total Stock Value (Buy):", style = MaterialTheme.typography.subtitle1)
                    Text("${String.format("%.2f", totalStockValue)}", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.primary)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total Stock Value (Sell):", style = MaterialTheme.typography.subtitle1)
                    Text("${String.format("%.2f", totalSellValue)}", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.secondary)
                }
            }
        }
        
        // Header row
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Code", modifier = Modifier.weight(0.15f), style = MaterialTheme.typography.subtitle2)
            Text("Description", modifier = Modifier.weight(0.35f), style = MaterialTheme.typography.subtitle2)
            Text("Buy Price", modifier = Modifier.weight(0.15f), style = MaterialTheme.typography.subtitle2, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Text("Sell Price", modifier = Modifier.weight(0.15f), style = MaterialTheme.typography.subtitle2, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Text("Stock", modifier = Modifier.weight(0.1f), style = MaterialTheme.typography.subtitle2, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Text("Value", modifier = Modifier.weight(0.1f), style = MaterialTheme.typography.subtitle2, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Divider()
        
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(products) { product ->
                Card { 
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(product.code, modifier = Modifier.weight(0.15f))
                        Text(product.description ?: "N/A", modifier = Modifier.weight(0.35f))
                        Text(String.format("%.2f", product.buyPrice), modifier = Modifier.weight(0.15f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        Text(String.format("%.2f", product.sellPrice), modifier = Modifier.weight(0.15f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        Text(String.format("%.2f", product.stock), modifier = Modifier.weight(0.1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        Text(
                            String.format("%.2f", product.sellPrice * product.stock),
                            modifier = Modifier.weight(0.1f),
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.primary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomersScreen(customers: List<Customer>, onAdd: (Customer) -> Customer) {
    var phone by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var dobError by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Active") }
    var creditLimit by remember { mutableStateOf("0.0") }
    var selectedPhone by remember { mutableStateOf<String?>(null) }
    var isEditing by remember { mutableStateOf(false) }

    // Focus requesters for Tab navigation
    val phoneFocus = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    val addressFocus = remember { FocusRequester() }
    val dobFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val creditLimitFocus = remember { FocusRequester() }

    fun clearForm() {
        phone = ""
        name = ""
        address = ""
        dob = ""
        dobError = false
        email = ""
        status = "Active"
        creditLimit = "0.0"
        selectedPhone = null
        isEditing = false
    }

    fun loadCustomerForEdit(customer: Customer) {
        phone = customer.phone
        name = customer.name
        address = customer.address ?: ""
        dob = customer.dob ?: ""
        email = customer.email ?: ""
        status = customer.status
        creditLimit = customer.creditLimit.toString()
        selectedPhone = customer.phone
        isEditing = true
        dobError = !isValidDate(dob) && dob.isNotBlank()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Customers", style = MaterialTheme.typography.h5)
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Mobile (ID)") },
                        enabled = !isEditing,
                        modifier = Modifier
                            .weight(0.25f)
                            .focusRequester(phoneFocus)
                            .onKeyEvent { event ->
                                if (event.key == Key.Tab) {
                                    nameFocus.requestFocus()
                                    true
                                } else false
                            }
                    )
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        modifier = Modifier
                            .weight(0.35f)
                            .focusRequester(nameFocus)
                            .onKeyEvent { event ->
                                if (event.key == Key.Tab) {
                                    addressFocus.requestFocus()
                                    true
                                } else false
                            }
                    )
                    TextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Address") },
                        modifier = Modifier
                            .weight(0.4f)
                            .focusRequester(addressFocus)
                            .onKeyEvent { event ->
                                if (event.key == Key.Tab) {
                                    dobFocus.requestFocus()
                                    true
                                } else false
                            }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(0.25f)) {
                        // Date picker for DOB
                        var showDatePicker by remember { mutableStateOf(false) }
                        val dobFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
                        val parsedDob = dob.takeIf { it.isNotBlank() }?.let { parseDobOrNull(it) }
                        Button(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Select Date of Birth")
                        }
                        if (parsedDob != null) {
                            Text("Selected: ${parsedDob.format(dobFormatter)}", style = MaterialTheme.typography.body2)
                        } else if (dob.isNotBlank()) {
                            Text("Invalid date format", color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
                        }
                        if (showDatePicker) {
                            DatePickerDialog(
                                initialDate = parsedDob ?: LocalDate.now().minusYears(30),
                                onDateSelected = { date ->
                                    dob = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                                    dobError = false
                                    showDatePicker = false
                                },
                                onDismiss = { showDatePicker = false }
                            )
                        }
                    }
                    TextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        modifier = Modifier
                            .weight(0.35f)
                            .focusRequester(emailFocus)
                            .onKeyEvent { event ->
                                if (event.key == Key.Tab) {
                                    creditLimitFocus.requestFocus()
                                    true
                                } else false
                            }
                    )
                    TextField(
                        value = creditLimit,
                        onValueChange = { creditLimit = it },
                        label = { Text("Credit Limit") },
                        modifier = Modifier
                            .weight(0.2f)
                            .focusRequester(creditLimitFocus)
                            .onKeyEvent { event ->
                                if (event.key == Key.Tab) {
                                    creditLimitFocus.freeFocus()
                                    true
                                } else false
                            }
                    )
                    // Status dropdown
                    var expanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.weight(0.2f)) {
                        Button(onClick = { expanded = true }) { Text(status) }
                        androidx.compose.material.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(onClick = { status = "Active"; expanded = false }) { Text("Active") }
                            DropdownMenuItem(onClick = { status = "Disactive"; expanded = false }) { Text("Disactive") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        if (phone.isNotBlank() && name.isNotBlank() && (dob.isBlank() || isValidDate(dob))) {
                            onAdd(
                                Customer(
                                    phone = phone.trim(),
                                    name = name.trim(),
                                    address = address.trim().ifBlank { null },
                                    dob = dob.trim().ifBlank { null },
                                    email = email.trim().ifBlank { null },
                                    status = status,
                                    creditLimit = creditLimit.toDoubleOrNull() ?: 0.0
                                )
                            )
                            clearForm()
                        }
                    }, modifier = Modifier.weight(0.35f)) { 
                        Text(if (isEditing) "Update Customer" else "Add Customer") 
                    }
                    
                    if (isEditing) {
                        Button(onClick = {
                            if (phone.isNotBlank()) {
                                onAdd(
                                    Customer(
                                        phone = phone.trim(),
                                        name = name.trim(),
                                        address = address.trim().ifBlank { null },
                                        dob = dob.trim().ifBlank { null },
                                        email = email.trim().ifBlank { null },
                                        status = "Disactive",
                                        creditLimit = creditLimit.toDoubleOrNull() ?: 0.0
                                    )
                                )
                                clearForm()
                            }
                        }, modifier = Modifier.weight(0.25f)) { 
                            Text("Deactivate") 
                        }
                        
                        Button(onClick = { clearForm() }, modifier = Modifier.weight(0.2f)) { 
                            Text("Clear") 
                        }
                    }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(customers) { customer ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (customer.phone == selectedPhone) 
                                androidx.compose.material.MaterialTheme.colors.primary.copy(alpha = 0.1f) 
                            else 
                                androidx.compose.material.MaterialTheme.colors.surface
                        )
                        .clickable { loadCustomerForEdit(customer) }
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(0.7f)) {
                            Text("${customer.name} (${customer.phone})", style = MaterialTheme.typography.body1)
                            Text("Address: ${customer.address ?: "-"}", style = MaterialTheme.typography.caption)
                            Text("DOB: ${formatDobForDisplay(customer.dob)}", style = MaterialTheme.typography.caption)
                            Text("Email: ${customer.email ?: "-"}", style = MaterialTheme.typography.caption)
                            Text("Status: ${customer.status}", style = if (customer.status == "Disactive") MaterialTheme.typography.caption else MaterialTheme.typography.body2)
                        }
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(0.3f)) {
                            Text("Credit Limit:", style = MaterialTheme.typography.caption)
                            Text("${customer.creditLimit}", style = MaterialTheme.typography.body1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportsScreen(reportsRepo: ReportsRepository) {
    val sales by remember { mutableStateOf(reportsRepo.dailySales()) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Reports", style = MaterialTheme.typography.h5)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sales) { sale ->
                Card { Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("Receipt #${sale.id}"); Text(sale.customerName ?: "Walk-in") }
                    Column(horizontalAlignment = Alignment.End) { Text("Total: ${sale.total}"); Text("Paid: ${sale.paid}") }
                } }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String) {
    Card(modifier = Modifier.width(180.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.subtitle1)
            Text(value, style = MaterialTheme.typography.h5)
        }
    }
}

@Composable
private fun ProductsScreen(productRepo: ProductRepository, onUpdate: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var uom by remember { mutableStateOf("pcs") }
    var buyPrice by remember { mutableStateOf("0.0") }
    var sellPrice by remember { mutableStateOf("0.0") }
    var defaultNumber by remember { mutableStateOf("0.0") }
    var stock by remember { mutableStateOf("0.0") }
    var reorder by remember { mutableStateOf("0.0") }
    var products by remember { mutableStateOf(productRepo.list()) }

    // Focus requesters for Tab navigation
    val codeFocus = remember { FocusRequester() }
    val descriptionFocus = remember { FocusRequester() }
    val uomFocus = remember { FocusRequester() }
    val buyPriceFocus = remember { FocusRequester() }
    val sellPriceFocus = remember { FocusRequester() }
    val defaultNumberFocus = remember { FocusRequester() }
    val stockFocus = remember { FocusRequester() }
    val reorderFocus = remember { FocusRequester() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Products", style = MaterialTheme.typography.h5)

        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Code") },
                        modifier = Modifier
                            .weight(0.25f)
                            .focusRequester(codeFocus)
                            .onKeyEvent { event ->
                                if (event.key == Key.Tab) {
                                    descriptionFocus.requestFocus()
                                    true
                                } else false
                            }
                    )
                    TextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        modifier = Modifier
                            .weight(0.60f)
                            .focusRequester(descriptionFocus)
                            .onKeyEvent { event ->
                                if (event.key == Key.Tab) {
                                    uomFocus.requestFocus()
                                    true
                                } else false
                            }
                    )
                    TextField(
                        value = uom,
                        onValueChange = { uom = it },
                        label = { Text("UOM") },
                        modifier = Modifier
                            .weight(0.15f)
                            .focusRequester(uomFocus)
                            .onKeyEvent { event ->
                                if (event.key == Key.Tab) {
                                    buyPriceFocus.requestFocus()
                                    true
                                } else false
                            }
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = buyPrice,
                        onValueChange = { buyPrice = it },
                        label = { Text("Buy Price") },
                        modifier = Modifier
                            .weight(0.33f)
                            .focusRequester(buyPriceFocus)
                            .onFocusChanged { if (it.isFocused && buyPrice == "0.0") buyPrice = "" }
                            .onKeyEvent { event ->
                                if (event.key == Key.Tab) {
                                    sellPriceFocus.requestFocus()
                                    true
                                } else false
                            }
                    )
                    TextField(
                        value = sellPrice,
                        onValueChange = { sellPrice = it },
                        label = { Text("Sell Price") },
                        modifier = Modifier
                            .weight(0.33f)
                            .focusRequester(sellPriceFocus)
                            .onFocusChanged { if (it.isFocused && sellPrice == "0.0") sellPrice = "" }
                            .onKeyEvent { event ->
                                if (event.key == Key.Tab) {
                                    defaultNumberFocus.requestFocus()
                                    true
                                } else false
                            }
                    )
                    TextField(
                        value = defaultNumber,
                        onValueChange = { defaultNumber = it },
                        label = { Text("Default #") },
                        modifier = Modifier
                            .weight(0.34f)
                            .focusRequester(defaultNumberFocus)
                            .onFocusChanged { if (it.isFocused && defaultNumber == "0.0") defaultNumber = "" }
                            .onKeyEvent { event ->
                                if (event.key == Key.Tab) {
                                    stockFocus.requestFocus()
                                    true
                                } else false
                            }
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = stock,
                        onValueChange = { stock = it },
                        label = { Text("Stock") },
                        modifier = Modifier
                            .weight(0.33f)
                            .focusRequester(stockFocus)
                            .onFocusChanged { if (it.isFocused && stock == "0.0") stock = "" }
                            .onKeyEvent { event ->
                                if (event.key == Key.Tab) {
                                    reorderFocus.requestFocus()
                                    true
                                } else false
                            }
                    )
                    TextField(
                        value = reorder,
                        onValueChange = { reorder = it },
                        label = { Text("Reorder Level") },
                        modifier = Modifier
                            .weight(0.33f)
                            .focusRequester(reorderFocus)
                            .onFocusChanged { if (it.isFocused && reorder == "0.0") reorder = "" }
                            .onKeyEvent { event ->
                                if (event.key == Key.Tab) {
                                    reorderFocus.freeFocus()
                                    true
                                } else false
                            }
                    )
                    Spacer(modifier = Modifier.weight(0.34f))
                    Button(onClick = {
                        if (code.isNotBlank()) {
                            val prod = Product(code = code.trim(), description = description.trim().ifBlank { null }, uom = uom.trim(), buyPrice = buyPrice.toDoubleOrNull() ?: 0.0, sellPrice = sellPrice.toDoubleOrNull() ?: 0.0, defaultNumber = defaultNumber.toDoubleOrNull() ?: 0.0, stock = stock.toDoubleOrNull() ?: 0.0, reorderLevel = reorder.toDoubleOrNull() ?: 0.0)
                            productRepo.upsert(prod)
                            products = productRepo.list()
                            onUpdate()
                            code = ""; description = ""; uom = "pcs"; buyPrice = "0.0"; sellPrice = "0.0"; defaultNumber = "0.0"; stock = "0.0"; reorder = "0.0"
                        }
                    }, modifier = Modifier.align(Alignment.CenterVertically)) { Text("Save Product") }
                }
            }
        }

        // Products list
        Card(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(products) { p ->
                    Card { Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(p.code); Text(p.description ?: "-") }
                        Column(horizontalAlignment = Alignment.End) { Text("Sell: ${p.sellPrice}"); Text("Stock: ${p.stock}") }
                    } }
                }
            }
        }
    }
}

@Composable
private fun POSScreen(itemRepo: pos.data.ItemRepository, saleRepo: pos.data.SaleRepository, onSave: () -> Unit) {
    POSScreenWithDependencies(itemRepo, saleRepo, onSave)
}

@Composable
private fun POSScreenWithDependencies(
    itemRepo: pos.data.ItemRepository,
    saleRepo: pos.data.SaleRepository,
    onSave: () -> Unit
) {
    val customerRepo = remember { pos.data.CustomerRepository() }
    val productRepo = remember { pos.data.ProductRepository() }
    val reportsRepo = remember { ReportsRepository() }

    var customerPhone by remember { mutableStateOf("") }
    var customerName by remember { mutableStateOf("Walk-in Customer") }
    var productCode by remember { mutableStateOf("") }
    var productDescription by remember { mutableStateOf("") }
    var productPrice by remember { mutableStateOf("") }
    var productStock by remember { mutableStateOf("") }
    var productQty by remember { mutableStateOf("1") }
    var cart by remember { mutableStateOf(mapOf<String, Pair<pos.data.Product, Double>>()) }
    var selectedCustomer by remember { mutableStateOf<pos.data.Customer?>(null) }
    var customerError by remember { mutableStateOf("") }
    var productError by remember { mutableStateOf("") }
    var lastSavedInvoiceId by remember { mutableStateOf(0L) }
    var showInvoiceHistory by remember { mutableStateOf(false) }
    var barcodeBuffer by remember { mutableStateOf("") }
    var lastBarcodeTime by remember { mutableStateOf(System.currentTimeMillis()) }

    val customerPhoneFocus = remember { FocusRequester() }
    val productCodeFocus = remember { FocusRequester() }

    fun lookupCustomer() {
        if (customerPhone.isNotBlank()) {
            val customer = customerRepo.findByPhone(customerPhone.trim())
            if (customer != null) {
                selectedCustomer = customer
                customerName = customer.name
                customerError = ""
            } else {
                customerError = "Customer ID not found"
                selectedCustomer = null
                customerName = "Walk-in Customer"
            }
        } else {
            selectedCustomer = null
            customerName = "Walk-in Customer"
            customerError = ""
        }
    }

    fun lookupProduct(code: String = productCode): pos.data.Product? {
        if (code.isNotBlank()) {
            val product = productRepo.findByCode(code.trim())
            if (product != null) {
                productDescription = product.description ?: ""
                productPrice = String.format("%.2f", product.sellPrice)
                productStock = product.stock.toString()
                val defaultQty = if (product.defaultNumber > 0) product.defaultNumber else 1.0
                productQty = String.format("%.0f", defaultQty)
                productError = ""
                return product
            } else {
                productDescription = ""
                productPrice = ""
                productStock = ""
                productError = "Product not found: $code"
            }
        }
        return null
    }

    fun addProductToCart(product: pos.data.Product, qty: Double) {
        val code = product.code
        cart = if (cart.containsKey(code)) {
            val (existing, existingQty) = cart[code]!!
            cart + (code to (existing to (existingQty + qty)))
        } else {
            cart + (code to (product to qty))
        }
        productCode = ""
        productDescription = ""
        productPrice = ""
        productStock = ""
        productQty = "1"
        productError = ""
        barcodeBuffer = ""
    }

    fun clearInvoice() {
        cart = mapOf()
        customerPhone = ""
        customerName = "Walk-in Customer"
        selectedCustomer = null
        customerError = ""
        productError = ""
        productCode = ""
        productDescription = ""
        productPrice = ""
        productStock = ""
        productQty = "1"
        barcodeBuffer = ""
    }

    fun saveAndPrintInvoice() {
        if (cart.isNotEmpty()) {
            val items = cart.values.map { (product, qty) ->
                pos.utils.InvoiceItem(
                    code = product.code,
                    description = product.description ?: "",
                    qty = qty,
                    price = product.sellPrice,
                    total = product.sellPrice * qty
                )
            }
            val subtotal = items.sumOf { it.total }
            val saleId = saleRepo.createSale(
                selectedCustomer?.phone,
                cart.values.map { (product, qty) ->
                    pos.data.SaleLine(
                        itemId = product.id ?: 0,
                        quantity = qty,
                        price = product.sellPrice
                    )
                }.toList(),
                subtotal,
                "CASH"
            )

            val invoiceData = pos.utils.InvoiceData(
                invoiceNo = saleId,
                customerName = customerName,
                customerPhone = selectedCustomer?.phone,
                date = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                items = items,
                subtotal = subtotal,
                tax = 0.0,
                total = subtotal,
                paidAmount = subtotal,
                paymentMethod = "CASH"
            )

            val pdfPath = pos.utils.PdfGenerator.generateInvoice(
                invoiceData,
                pos.utils.PdfGenerator.generateInvoicePath(saleId)
            )

            lastSavedInvoiceId = saleId
            println("Invoice #$saleId saved to: $pdfPath")

            // Auto-print after save
            try {
                val runtime = Runtime.getRuntime()
                runtime.exec(arrayOf("cmd", "/c", "start", pdfPath))
            } catch (e: Exception) {
                println("Error opening PDF: ${e.message}")
            }

            clearInvoice()
            onSave()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .onKeyEvent { event ->
                // Barcode scanner support - accumulate characters rapidly
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBarcodeTime > 100) {
                    barcodeBuffer = ""
                }
                lastBarcodeTime = currentTime

                if (event.key == Key.Enter && barcodeBuffer.isNotBlank()) {
                    val scannedCode = barcodeBuffer.trim()
                    productCode = scannedCode
                    val product = lookupProduct(scannedCode)
                    if (product != null) {
                        val qty = product.defaultNumber.takeIf { it > 0 } ?: 1.0
                        addProductToCart(product, qty)
                    }
                    barcodeBuffer = ""
                    true
                } else if (event.key.toString().length == 1) {
                    barcodeBuffer += event.key.toString()
                    false
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header with Invoice History toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Point of Sale - Invoice", style = MaterialTheme.typography.h5)
            Button(onClick = { showInvoiceHistory = !showInvoiceHistory }) {
                Text(if (showInvoiceHistory) "Hide History" else "Show History")
            }
        }

        if (showInvoiceHistory) {
            // Invoice History View
            InvoiceHistoryView(reportsRepo = reportsRepo, onClose = { showInvoiceHistory = false })
        } else {
            // Customer Section - Compact Design
            Card(modifier = Modifier.fillMaxWidth(), elevation = 4.dp) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Customer:", style = MaterialTheme.typography.subtitle2, modifier = Modifier.width(80.dp))
                    TextField(
                        value = customerPhone,
                        onValueChange = { customerPhone = it; customerError = "" },
                        label = { Text("Mobile") },
                        placeholder = { Text("Walk-in") },
                        modifier = Modifier
                            .weight(0.3f)
                            .focusRequester(customerPhoneFocus)
                            .onFocusChanged { focus -> if (!focus.isFocused) lookupCustomer() }
                            .onKeyEvent { event ->
                                if (event.key == Key.Enter || event.key == Key.Tab) {
                                    lookupCustomer()
                                    productCodeFocus.requestFocus()
                                    true
                                } else false
                            }
                    )
                    Column(modifier = Modifier.weight(0.7f)) {
                        Text(customerName, style = MaterialTheme.typography.h6)
                        if (customerError.isNotBlank()) {
                            Text(customerError, color = androidx.compose.material.MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
                        }
                    }
                }
            }

            // Main Content - Product Entry & Cart
            Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Left Panel - Product Entry
                Card(modifier = Modifier.weight(0.3f).fillMaxHeight(), elevation = 4.dp) {
                    Column(modifier = Modifier.padding(12.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Add Product", style = MaterialTheme.typography.h6)
                        Divider()
                        
                        TextField(
                            value = productCode,
                            onValueChange = { 
                                productCode = it
                                productError = ""
                            },
                            label = { Text("Code / Barcode") },
                            placeholder = { Text("Scan or type") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(productCodeFocus)
                                .onFocusChanged { focus -> 
                                    if (!focus.isFocused && productCode.isNotBlank()) {
                                        lookupProduct()
                                    }
                                }
                                .onKeyEvent { event ->
                                    if (event.key == Key.Enter) {
                                        val product = lookupProduct()
                                        if (product != null) {
                                            val qty = productQty.toDoubleOrNull() ?: product.defaultNumber.takeIf { it > 0 } ?: 1.0
                                            addProductToCart(product, qty)
                                            productCodeFocus.requestFocus()
                                        }
                                        true
                                    } else false
                                }
                        )
                        
                        if (productDescription.isNotBlank()) {
                            Card(modifier = Modifier.fillMaxWidth(), backgroundColor = androidx.compose.material.MaterialTheme.colors.primary.copy(alpha = 0.1f)) {
                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Description:", style = MaterialTheme.typography.caption)
                                    Text(productDescription, style = MaterialTheme.typography.body1)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column {
                                            Text("Price:", style = MaterialTheme.typography.caption)
                                            Text(productPrice, style = MaterialTheme.typography.h6)
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("Stock:", style = MaterialTheme.typography.caption)
                                            Text(productStock, style = MaterialTheme.typography.h6)
                                        }
                                    }
                                }
                            }
                        }
                        
                        if (productError.isNotBlank()) {
                            Card(modifier = Modifier.fillMaxWidth(), backgroundColor = androidx.compose.material.MaterialTheme.colors.error.copy(alpha = 0.1f)) {
                                Text(
                                    productError,
                                    color = androidx.compose.material.MaterialTheme.colors.error,
                                    style = MaterialTheme.typography.body2,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                        
                        TextField(
                            value = productQty,
                            onValueChange = { productQty = it },
                            label = { Text("Quantity") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Button(
                            onClick = {
                                if (productCode.isNotBlank()) {
                                    val product = productRepo.findByCode(productCode.trim())
                                    if (product != null) {
                                        val qty = productQty.toDoubleOrNull() ?: 1.0
                                        addProductToCart(product, qty)
                                        productCodeFocus.requestFocus()
                                    } else {
                                        productError = "Product not found"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Add to Cart")
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Summary Section
                        Divider()
                        val subtotal = cart.values.sumOf { (product, qty) -> product.sellPrice * qty }
                        val itemCount = cart.values.sumOf { it.second }
                        
                        Text("Summary", style = MaterialTheme.typography.h6)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Items:", style = MaterialTheme.typography.body2)
                            Text(String.format("%.0f", itemCount), style = MaterialTheme.typography.body1)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Subtotal:", style = MaterialTheme.typography.body2)
                            Text(String.format("%.2f", subtotal), style = MaterialTheme.typography.h6)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Tax:", style = MaterialTheme.typography.body2)
                            Text("0.00", style = MaterialTheme.typography.body1)
                        }
                        Divider()
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("TOTAL:", style = MaterialTheme.typography.h6)
                            Text(String.format("%.2f", subtotal), style = MaterialTheme.typography.h5)
                        }

                        Divider()
                        
                        // Action Buttons
                        Button(
                            onClick = { clearInvoice() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material.ButtonDefaults.buttonColors(
                                backgroundColor = androidx.compose.material.MaterialTheme.colors.secondary
                            )
                        ) {
                            Text("Clear All")
                        }
                        
                        Button(
                            onClick = { saveAndPrintInvoice() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = cart.isNotEmpty(),
                            colors = androidx.compose.material.ButtonDefaults.buttonColors(
                                backgroundColor = androidx.compose.material.MaterialTheme.colors.primary
                            )
                        ) {
                            Text("Save & Print", style = MaterialTheme.typography.button)
                        }

                        if (lastSavedInvoiceId > 0) {
                            Button(
                                onClick = {
                                    val pdfPath = pos.utils.PdfGenerator.generateInvoicePath(lastSavedInvoiceId)
                                    try {
                                        val runtime = Runtime.getRuntime()
                                        runtime.exec(arrayOf("cmd", "/c", "start", pdfPath))
                                    } catch (e: Exception) {
                                        println("Error opening PDF: ${e.message}")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Reprint Last (#$lastSavedInvoiceId)")
                            }
                        }
                    }
                }

                // Right Panel - Cart Items
                Card(modifier = Modifier.weight(0.7f).fillMaxHeight(), elevation = 4.dp) {
                    Column(modifier = Modifier.padding(12.dp).fillMaxHeight()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Cart Items", style = MaterialTheme.typography.h6)
                            Text("${cart.size} products", style = MaterialTheme.typography.caption)
                        }
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        // Header Row
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Code", modifier = Modifier.weight(0.15f), style = MaterialTheme.typography.caption)
                            Text("Description", modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.caption)
                            Text("Qty", modifier = Modifier.weight(0.15f), textAlign = androidx.compose.ui.text.style.TextAlign.End, style = MaterialTheme.typography.caption)
                            Text("Price", modifier = Modifier.weight(0.15f), textAlign = androidx.compose.ui.text.style.TextAlign.End, style = MaterialTheme.typography.caption)
                            Text("Total", modifier = Modifier.weight(0.15f), textAlign = androidx.compose.ui.text.style.TextAlign.End, style = MaterialTheme.typography.caption)
                        }
                        Divider()
                        
                        // Cart Items List
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(cart.toList()) { (code, pair) ->
                                val (product, qty) = pair
                                val total = product.sellPrice * qty
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    elevation = 2.dp,
                                    backgroundColor = androidx.compose.material.MaterialTheme.colors.surface
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            product.code,
                                            modifier = Modifier.weight(0.15f),
                                            style = MaterialTheme.typography.body2
                                        )
                                        Text(
                                            product.description ?: "N/A",
                                            modifier = Modifier.weight(0.4f),
                                            style = MaterialTheme.typography.body1
                                        )
                                        Text(
                                            String.format("%.2f", qty),
                                            modifier = Modifier.weight(0.15f),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                            style = MaterialTheme.typography.body2
                                        )
                                        Text(
                                            String.format("%.2f", product.sellPrice),
                                            modifier = Modifier.weight(0.15f),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                            style = MaterialTheme.typography.body2
                                        )
                                        Row(
                                            modifier = Modifier.weight(0.15f),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                String.format("%.2f", total),
                                                style = MaterialTheme.typography.body1
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Button(
                                                onClick = { cart = cart - code },
                                                modifier = Modifier.width(40.dp).height(32.dp),
                                                colors = androidx.compose.material.ButtonDefaults.buttonColors(
                                                    backgroundColor = androidx.compose.material.MaterialTheme.colors.error
                                                )
                                            ) {
                                                Text("×", fontSize = 16.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InvoiceHistoryView(reportsRepo: ReportsRepository, onClose: () -> Unit) {
    val sales = remember { reportsRepo.dailySales() }
    
    Card(modifier = Modifier.fillMaxSize(), elevation = 4.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Invoice History - Today", style = MaterialTheme.typography.h6)
                Button(onClick = onClose) { Text("Close") }
            }
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            if (sales.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No invoices found for today", style = MaterialTheme.typography.body1)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sales) { sale ->
                        Card(elevation = 2.dp) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(0.6f)) {
                                    Text("Invoice #${sale.id}", style = MaterialTheme.typography.h6)
                                    Text(sale.customerName ?: "Walk-in Customer", style = MaterialTheme.typography.body2)
                                }
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(0.4f)) {
                                    Text("Total: ${String.format("%.2f", sale.total)}", style = MaterialTheme.typography.body1)
                                    Text("Paid: ${String.format("%.2f", sale.paid)}", style = MaterialTheme.typography.caption)
                                    Button(
                                        onClick = {
                                            val pdfPath = pos.utils.PdfGenerator.generateInvoicePath(sale.id)
                                            try {
                                                val runtime = Runtime.getRuntime()
                                                runtime.exec(arrayOf("cmd", "/c", "start", pdfPath))
                                            } catch (e: Exception) {
                                                println("Error opening PDF: ${e.message}")
                                            }
                                        },
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        Text("Print", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}