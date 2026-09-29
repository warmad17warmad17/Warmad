package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.model.CategoryEntity
import com.example.data.model.ExpenseEntity
import com.example.data.model.ProductEntity
import com.example.data.model.StoreSettingsEntity
import com.example.data.model.TransactionEntity
import com.example.data.model.TransactionItemEntity
import com.example.data.repository.TokoRepository
import com.example.util.BackupData
import com.example.util.BackupManager
import com.example.util.CurrencyFormatter
import com.example.util.DateFormatter
import com.example.util.NotificationHelper
import com.example.util.NotificationPreferences
import com.example.util.NotificationSoundItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CartItem(
    val product: ProductEntity,
    val quantity: Int
) {
    val subtotal: Double get() = product.hargaJual * quantity
    val subtotalCost: Double get() = product.hargaBeli * quantity
}

enum class PeriodFilter(val label: String) {
    HARI_INI("Hari Ini"),
    MINGGU_INI("Minggu Ini"),
    BULAN_INI("Bulan Ini"),
    SEMUA("Semua")
}

class TokoViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: TokoRepository
    val notificationPrefs = NotificationPreferences(application)

    private val _isNotificationEnabled = MutableStateFlow(notificationPrefs.isNotificationEnabled)
    val isNotificationEnabled: StateFlow<Boolean> = _isNotificationEnabled.asStateFlow()

    private val _selectedNotificationSoundTitle = MutableStateFlow(notificationPrefs.soundTitle)
    val selectedNotificationSoundTitle: StateFlow<String> = _selectedNotificationSoundTitle.asStateFlow()

    private val _selectedNotificationSoundUri = MutableStateFlow(notificationPrefs.soundUri)
    val selectedNotificationSoundUri: StateFlow<String> = _selectedNotificationSoundUri.asStateFlow()

    private val _availableNotificationSounds = MutableStateFlow<List<NotificationSoundItem>>(emptyList())
    val availableNotificationSounds: StateFlow<List<NotificationSoundItem>> = _availableNotificationSounds.asStateFlow()

    init {
        val db = AppDatabase.getDatabase(application, viewModelScope)
        repository = TokoRepository(
            db.categoryDao(),
            db.productDao(),
            db.transactionDao(),
            db.expenseDao(),
            db.storeSettingsDao()
        )

        // Automatically sync HPP for any existing transactions on startup
        viewModelScope.launch {
            repository.syncTransactionsHpp()
        }

        // Muat daftar suara notifikasi internal ponsel
        viewModelScope.launch(Dispatchers.IO) {
            val sounds = NotificationHelper.getDeviceNotificationSounds(application)
            _availableNotificationSounds.value = sounds
        }

        // Sembunyikan gambar dan file media aplikasi dari Galeri ponsel
        viewModelScope.launch(Dispatchers.IO) {
            com.example.util.NoMediaHelper.hideAppImagesFromGallery(application)
        }
    }

    // Settings
    val storeSettings: StateFlow<StoreSettingsEntity> = repository.storeSettings
        .map { it ?: StoreSettingsEntity() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StoreSettingsEntity()
        )

    // Categories
    val allCategories: StateFlow<List<CategoryEntity>> = repository.allCategories
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Products
    val allProducts: StateFlow<List<ProductEntity>> = repository.allProducts
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Transactions
    val allTransactions: StateFlow<List<TransactionEntity>> = repository.allTransactions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Expenses
    val allExpenses: StateFlow<List<ExpenseEntity>> = repository.allExpenses
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // UI Events & Toasts
    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    // -------------------------------------------------------------
    // CASHIER & CART STATE
    // -------------------------------------------------------------
    private val _cart = MutableStateFlow<List<CartItem>>(emptyList())
    val cart: StateFlow<List<CartItem>> = _cart.asStateFlow()

    val cartTotal: StateFlow<Double> = _cart.map { cartList ->
        cartList.sumOf { it.subtotal }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)

    val cartTotalCost: StateFlow<Double> = _cart.map { cartList ->
        cartList.sumOf { it.subtotalCost }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)

    // Fast QR Input
    private val _qrInputText = MutableStateFlow("")
    val qrInputText: StateFlow<String> = _qrInputText.asStateFlow()

    fun updateQrInputText(text: String) {
        _qrInputText.value = text
        // Fast instant barcode detection
        if (text.isNotBlank()) {
            checkAndAutoAddQr(text.trim())
        }
    }

    /**
     * Checks if the scanned/typed QR matches any product in stock.
     * If matched, automatically adds to cart and clears input text!
     */
    fun checkAndAutoAddQr(code: String): Boolean {
        if (code.isBlank()) return false
        val cleanCode = code.trim()
        val products = allProducts.value
        val match = products.firstOrNull { it.qrCode.equals(cleanCode, ignoreCase = true) }
        if (match != null) {
            if (match.stok <= 0) {
                emitMessage("Stok '${match.name}' habis!")
                _qrInputText.value = ""
                return false
            }
            addToCart(match)
            emitMessage("✓ '${match.name}' ditambahkan")
            _qrInputText.value = "" // Auto-clear so cashier doesn't need to manually delete
            return true
        }
        return false
    }

    fun submitQrInputManual() {
        val code = _qrInputText.value.trim()
        if (code.isBlank()) return
        val success = checkAndAutoAddQr(code)
        if (!success) {
            emitMessage("Produk dengan kode '$code' tidak ditemukan!")
            _qrInputText.value = ""
        }
    }

    fun addToCart(product: ProductEntity) {
        val currentCart = _cart.value.toMutableList()
        val existingIndex = currentCart.indexOfFirst { it.product.id == product.id }
        if (existingIndex >= 0) {
            val existing = currentCart[existingIndex]
            if (existing.quantity >= product.stok) {
                emitMessage("Jumlah melebihi stok yang tersedia (${product.stok})")
                return
            }
            currentCart[existingIndex] = existing.copy(quantity = existing.quantity + 1)
        } else {
            if (product.stok <= 0) {
                emitMessage("Stok '${product.name}' habis!")
                return
            }
            currentCart.add(CartItem(product = product, quantity = 1))
        }
        _cart.value = currentCart
    }

    fun updateCartQuantity(productId: Long, newQuantity: Int) {
        val currentCart = _cart.value.toMutableList()
        val index = currentCart.indexOfFirst { it.product.id == productId }
        if (index >= 0) {
            if (newQuantity <= 0) {
                currentCart.removeAt(index)
            } else {
                val item = currentCart[index]
                if (newQuantity > item.product.stok) {
                    emitMessage("Maksimal stok tersedia: ${item.product.stok}")
                    currentCart[index] = item.copy(quantity = item.product.stok)
                } else {
                    currentCart[index] = item.copy(quantity = newQuantity)
                }
            }
            _cart.value = currentCart
        }
    }

    fun removeFromCart(productId: Long) {
        val currentCart = _cart.value.toMutableList()
        currentCart.removeAll { it.product.id == productId }
        _cart.value = currentCart
        emitMessage("Pesanan produk telah dihapus dari keranjang")
    }

    fun clearCart() {
        _cart.value = emptyList()
    }

    // -------------------------------------------------------------
    // PAYMENT & CHECKOUT STATE
    // -------------------------------------------------------------
    private val _paymentType = MutableStateFlow("TUNAI") // "TUNAI" or "NON_TUNAI"
    val paymentType: StateFlow<String> = _paymentType.asStateFlow()

    private val _paidAmountText = MutableStateFlow("")
    val paidAmountText: StateFlow<String> = _paidAmountText.asStateFlow()

    fun setPaymentType(type: String) {
        _paymentType.value = type
        if (type == "NON_TUNAI") {
            // Non tunai is exactly equal to the total bill
            val total = cartTotal.value
            _paidAmountText.value = total.toLong().toString()
        }
    }

    fun updatePaidAmountText(text: String) {
        _paidAmountText.value = text.filter { it.isDigit() }
    }

    /**
     * Fast nominal buttons:
     * Clicking 10 sets 10000, 20 sets 20000, 30 sets 30000, 50 sets 50000, 100 sets 100000
     * As specified: "Uang Pas 10 20 30 50 100 dimana nominal tersebut ketika di klik akan di tambahkan 000"
     */
    fun onQuickNominalClick(nominalCode: String) {
        if (nominalCode.equals("PAS", ignoreCase = true)) {
            val total = cartTotal.value
            _paidAmountText.value = total.toLong().toString()
        } else {
            // e.g. "10" -> "10000", "20" -> "20000", etc.
            val amountWithThousands = "${nominalCode}000"
            _paidAmountText.value = amountWithThousands
        }
    }

    // Receipt Dialog state after transaction
    private val _showReceiptDialog = MutableStateFlow(false)
    val showReceiptDialog: StateFlow<Boolean> = _showReceiptDialog.asStateFlow()

    private val _currentReceiptTransaction = MutableStateFlow<TransactionEntity?>(null)
    val currentReceiptTransaction: StateFlow<TransactionEntity?> = _currentReceiptTransaction.asStateFlow()

    private val _currentReceiptItems = MutableStateFlow<List<TransactionItemEntity>>(emptyList())
    val currentReceiptItems: StateFlow<List<TransactionItemEntity>> = _currentReceiptItems.asStateFlow()

    fun dismissReceiptDialog() {
        _showReceiptDialog.value = false
        _currentReceiptTransaction.value = null
        _currentReceiptItems.value = emptyList()
    }

    fun showReceiptForTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            val items = repository.getItemsForTransactionSync(transaction.id)
            _currentReceiptTransaction.value = transaction
            _currentReceiptItems.value = items
            _showReceiptDialog.value = true
        }
    }

    fun completeCheckout(notes: String = "") {
        val cartList = _cart.value
        if (cartList.isEmpty()) {
            emitMessage("Keranjang belanja masih kosong!")
            return
        }

        val total = cartList.sumOf { it.subtotal }
        val totalCost = cartList.sumOf { it.subtotalCost }
        val isNonTunai = _paymentType.value == "NON_TUNAI"

        val paid = if (isNonTunai) {
            total
        } else {
            CurrencyFormatter.parseAmount(_paidAmountText.value)
        }

        if (!isNonTunai && paid < total) {
            emitMessage("Uang pembayaran kurang ${CurrencyFormatter.formatRupiah(total - paid)}")
            return
        }

        val change = if (isNonTunai) 0.0 else (paid - total).coerceAtLeast(0.0)

        viewModelScope.launch {
            val invoiceNo = "TM-${SimpleDateFormat("yyMMddHHmmss", Locale.getDefault()).format(Date())}"
            val tx = TransactionEntity(
                invoiceNumber = invoiceNo,
                timestamp = System.currentTimeMillis(),
                totalAmount = total,
                totalCost = totalCost,
                paymentType = _paymentType.value,
                paidAmount = paid,
                changeAmount = change,
                notes = notes
            )

            val txItems = cartList.map { cartItem ->
                TransactionItemEntity(
                    transactionId = 0, // will be assigned by repository
                    productId = cartItem.product.id,
                    productName = cartItem.product.name,
                    qrCode = cartItem.product.qrCode,
                    quantity = cartItem.quantity,
                    unitPrice = cartItem.product.hargaJual,
                    unitCost = cartItem.product.hargaBeli,
                    subtotal = cartItem.subtotal
                )
            }

            val txId = repository.processSale(tx, txItems)
            val savedTx = tx.copy(id = txId)

            _cart.value = emptyList()
            _paidAmountText.value = ""
            _paymentType.value = "TUNAI"

            // Show receipt immediately
            _currentReceiptTransaction.value = savedTx
            _currentReceiptItems.value = txItems.map { it.copy(transactionId = txId) }
            _showReceiptDialog.value = true

            // Periksa stok terbaru setelah transaksi berhasil dikurangi
            checkAndNotifyLowStock(force = true)

            emitMessage("Transaksi berhasil disimpan!")
        }
    }

    // -------------------------------------------------------------
    // PRODUCT & CATALOG MANAGEMENT
    // -------------------------------------------------------------
    private val _productSearchQuery = MutableStateFlow("")
    val productSearchQuery: StateFlow<String> = _productSearchQuery.asStateFlow()

    private val _selectedProductCategoryFilter = MutableStateFlow<Long?>(null)
    val selectedProductCategoryFilter: StateFlow<Long?> = _selectedProductCategoryFilter.asStateFlow()

    private val _lowStockFilterActive = MutableStateFlow(false)
    val lowStockFilterActive: StateFlow<Boolean> = _lowStockFilterActive.asStateFlow()

    fun setProductSearchQuery(query: String) {
        _productSearchQuery.value = query
    }

    fun setSelectedCategoryFilter(catId: Long?) {
        _selectedProductCategoryFilter.value = catId
    }

    fun toggleLowStockFilter() {
        _lowStockFilterActive.value = !_lowStockFilterActive.value
    }

    fun setLowStockFilter(active: Boolean) {
        _lowStockFilterActive.value = active
    }

    // Filtered Products
    val filteredProducts: StateFlow<List<ProductEntity>> = combine(
        allProducts,
        _productSearchQuery,
        _selectedProductCategoryFilter,
        _lowStockFilterActive
    ) { products, query, catFilter, lowStockOnly ->
        products.filter { prod ->
            val matchQuery = query.isBlank() ||
                    prod.name.contains(query, ignoreCase = true) ||
                    prod.qrCode.contains(query, ignoreCase = true) ||
                    prod.categoryName.contains(query, ignoreCase = true)

            val matchCategory = catFilter == null || prod.categoryId == catFilter
            val matchLowStock = !lowStockOnly || prod.stok <= prod.minimumStokAlert

            matchQuery && matchCategory && matchLowStock
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Product valuation based on buy price: Sum(stok * hargaBeli)
    val totalInventoryValuation: StateFlow<Double> = allProducts.combine(allProducts) { prods, _ ->
        prods.sumOf { it.stok * it.hargaBeli }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val lowStockProductCount: StateFlow<Int> = allProducts.combine(allProducts) { prods, _ ->
        prods.count { it.stok <= it.minimumStokAlert }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        // Pantau perubahan data stok produk terbaru dan beri notifikasi jika ada stok menipis
        viewModelScope.launch {
            allProducts.collectLatest { products ->
                if (products.isNotEmpty()) {
                    checkAndNotifyLowStock(products = products, force = false)
                }
            }
        }
    }

    fun addProduct(
        name: String,
        categoryId: Long,
        categoryName: String,
        qrCode: String,
        hargaBeli: Double,
        hargaJual: Double,
        stok: Int,
        minimumStokAlert: Int
    ) {
        viewModelScope.launch {
            val product = ProductEntity(
                name = name.trim(),
                categoryId = categoryId,
                categoryName = categoryName,
                qrCode = qrCode.trim(),
                hargaBeli = hargaBeli,
                hargaJual = hargaJual,
                stok = stok,
                minimumStokAlert = minimumStokAlert
            )
            repository.addProduct(product)
            emitMessage("Produk '${product.name}' berhasil ditambahkan")
            checkAndNotifyLowStock(force = true)
        }
    }

    fun updateProduct(product: ProductEntity) {
        viewModelScope.launch {
            repository.updateProduct(product)
            emitMessage("Produk '${product.name}' berhasil diperbarui")
            checkAndNotifyLowStock(force = true)
        }
    }

    fun deleteProduct(product: ProductEntity) {
        viewModelScope.launch {
            repository.deleteProduct(product)
            emitMessage("Produk '${product.name}' dihapus")
        }
    }

    fun addCategory(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.addCategory(name.trim())
            emitMessage("Katalog '$name' berhasil ditambahkan")
        }
    }

    fun deleteCategory(category: CategoryEntity) {
        viewModelScope.launch {
            val result = repository.deleteCategory(category)
            if (result.isSuccess) {
                emitMessage("Katalog '${category.name}' berhasil dihapus")
            } else {
                emitMessage(result.exceptionOrNull()?.message ?: "Gagal menghapus katalog")
            }
        }
    }

    // -------------------------------------------------------------
    // EXPENSES & CAPITAL (MODAL & PENGELUARAN)
    // -------------------------------------------------------------
    fun addExpense(title: String, category: String, amount: Double, notes: String = "") {
        viewModelScope.launch {
            val exp = ExpenseEntity(
                title = title.trim(),
                category = category.trim(),
                amount = amount,
                timestamp = System.currentTimeMillis(),
                notes = notes.trim()
            )
            repository.addExpense(exp)
            emitMessage("Pengeluaran sebesar ${CurrencyFormatter.formatRupiah(amount)} disimpan")
        }
    }

    fun updateExpense(expense: ExpenseEntity) {
        viewModelScope.launch {
            repository.updateExpense(expense)
            emitMessage("Pengeluaran diperbarui")
        }
    }

    fun deleteExpense(expense: ExpenseEntity) {
        viewModelScope.launch {
            repository.deleteExpense(expense)
            emitMessage("Pengeluaran dihapus")
        }
    }

    fun updateInitialCashCapital(amount: Double) {
        viewModelScope.launch {
            val current = storeSettings.value
            val updated = current.copy(initialCashCapital = amount)
            repository.updateStoreSettings(updated)
            emitMessage("Modal kas awal toko diperbarui: ${CurrencyFormatter.formatRupiah(amount)}")
        }
    }

    // -------------------------------------------------------------
    // FINANCIAL REPORTS & RECONCILIATION
    // -------------------------------------------------------------
    private val _selectedPeriod = MutableStateFlow(PeriodFilter.HARI_INI)
    val selectedPeriod: StateFlow<PeriodFilter> = _selectedPeriod.asStateFlow()

    fun setSelectedPeriod(period: PeriodFilter) {
        _selectedPeriod.value = period
    }

    val periodTransactions: StateFlow<List<TransactionEntity>> = combine(
        allTransactions,
        _selectedPeriod
    ) { transactions, period ->
        val startTime = when (period) {
            PeriodFilter.HARI_INI -> DateFormatter.getStartOfToday()
            PeriodFilter.MINGGU_INI -> DateFormatter.getStartOfWeek()
            PeriodFilter.BULAN_INI -> DateFormatter.getStartOfMonth()
            PeriodFilter.SEMUA -> 0L
        }
        transactions.filter { it.timestamp >= startTime }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val periodExpenses: StateFlow<List<ExpenseEntity>> = combine(
        allExpenses,
        _selectedPeriod
    ) { expenses, period ->
        val startTime = when (period) {
            PeriodFilter.HARI_INI -> DateFormatter.getStartOfToday()
            PeriodFilter.MINGGU_INI -> DateFormatter.getStartOfWeek()
            PeriodFilter.BULAN_INI -> DateFormatter.getStartOfMonth()
            PeriodFilter.SEMUA -> 0L
        }
        expenses.filter { it.timestamp >= startTime }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun syncHppManually() {
        viewModelScope.launch {
            val count = repository.syncTransactionsHpp()
            if (count > 0) {
                emitMessage("Berhasil menyinkronkan HPP untuk $count transaksi!")
            } else {
                emitMessage("HPP semua transaksi sudah sinkron dengan harga beli produk")
            }
        }
    }

    // -------------------------------------------------------------
    // STORE & RECEIPT SETTINGS
    // -------------------------------------------------------------
    fun updateStoreName(newName: String) {
        val trimmed = newName.trim().ifBlank { "TOKO SUBUR" }
        viewModelScope.launch {
            val current = storeSettings.value
            val updated = current.copy(storeName = trimmed)
            repository.updateStoreSettings(updated)
            emitMessage("Nama toko berhasil diubah menjadi: $trimmed")
        }
    }

    fun updateReceiptSettings(
        storeName: String,
        storeAddress: String,
        storePhone: String,
        receiptFooter: String
    ) {
        viewModelScope.launch {
            val current = storeSettings.value
            val cleanName = storeName.trim().ifBlank { "TOKO SUBUR" }
            val updated = current.copy(
                storeName = cleanName,
                storeAddress = storeAddress.trim(),
                storePhone = storePhone.trim(),
                receiptFooter = receiptFooter.trim()
            )
            repository.updateStoreSettings(updated)
            emitMessage("Nama toko dan pengaturan struk berhasil disimpan!")
        }
    }

    fun enforceHideImagesFromGallery() {
        viewModelScope.launch(Dispatchers.IO) {
            val success = com.example.util.NoMediaHelper.hideAppImagesFromGallery(getApplication())
            if (success) {
                emitMessage("Gambar aplikasi berhasil disembunyikan dari galeri ponsel!")
            } else {
                emitMessage("Perlindungan .nomedia telah diperbarui di folder penyimpanan.")
            }
        }
    }

    fun addQuickNominal(nominal: String) {
        val clean = nominal.filter { it.isDigit() }
        if (clean.isBlank()) return
        viewModelScope.launch {
            val current = storeSettings.value
            val existing = current.quickNominals.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (!existing.contains(clean)) {
                existing.add(clean)
                val sorted = existing.sortedBy { it.toLongOrNull() ?: 0L }
                val updated = current.copy(quickNominals = sorted.joinToString(","))
                repository.updateStoreSettings(updated)
                emitMessage("Nominal '$clean' (${clean}.000) ditambahkan")
            } else {
                emitMessage("Nominal '$clean' sudah ada")
            }
        }
    }

    fun removeQuickNominal(nominal: String) {
        viewModelScope.launch {
            val current = storeSettings.value
            val existing = current.quickNominals.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (existing.remove(nominal)) {
                val updated = current.copy(quickNominals = existing.joinToString(","))
                repository.updateStoreSettings(updated)
                emitMessage("Nominal '$nominal' dihapus")
            }
        }
    }

    // -------------------------------------------------------------
    // BACKUP & RESTORE (.JSON TO LOCAL PHONE STORAGE)
    // -------------------------------------------------------------
    fun exportBackupToJsonUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val settings = storeSettings.value
                val categories = repository.getAllCategoriesSync()
                val products = repository.getAllProductsSync()
                val transactions = repository.getAllTransactionsSync()
                val transactionItems = repository.getItemsForTransactionSync(0) // or all items
                val allTxItems = mutableListOf<TransactionItemEntity>()
                for (tx in transactions) {
                    allTxItems.addAll(repository.getItemsForTransactionSync(tx.id))
                }
                val expenses = repository.getAllExpensesSync()

                val jsonContent = BackupManager.exportToJson(
                    settings = settings,
                    categories = categories,
                    products = products,
                    transactions = transactions,
                    transactionItems = allTxItems,
                    expenses = expenses
                )

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    OutputStreamWriter(outputStream).use { writer ->
                        writer.write(jsonContent)
                        writer.flush()
                    }
                }
                emitMessage("✓ Berhasil mencadangkan data ke file JSON!")
            } catch (e: Exception) {
                emitMessage("Gagal mencadangkan data: ${e.localizedMessage}")
            }
        }
    }

    fun exportAppApkToUri(context: Context, destinationUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sourceApkPath = context.applicationInfo.sourceDir
                val sourceFile = java.io.File(sourceApkPath)
                if (sourceFile.exists()) {
                    context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    emitMessage("✓ Berkas TokoSubur.apk berhasil disimpan ke penyimpanan ponsel!")
                } else {
                    emitMessage("Gagal menemukan berkas APK aplikasi di sistem.")
                }
            } catch (e: Exception) {
                emitMessage("Gagal mengekspor APK: ${e.localizedMessage}")
            }
        }
    }

    fun importBackupFromJsonUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val stringBuilder = StringBuilder()
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            stringBuilder.append(line).append("\n")
                            line = reader.readLine()
                        }
                    }
                }

                val backupData = BackupManager.parseJson(stringBuilder.toString())

                repository.restoreDatabase(
                    settings = backupData.storeSettings,
                    categories = backupData.categories,
                    products = backupData.products,
                    transactions = backupData.transactions,
                    transactionItems = backupData.transactionItems,
                    expenses = backupData.expenses
                )

                emitMessage("✓ Berhasil memulihkan ${backupData.products.size} produk dan data transaksi!")
            } catch (e: Exception) {
                emitMessage("Gagal memulihkan data: Format file tidak sesuai!")
            }
        }
    }

    // -------------------------------------------------------------
    // NOTIFIKASI SUARA STOK MENIPIS & PENYIMPANAN INTERNAL SUARA
    // -------------------------------------------------------------
    fun setNotificationEnabled(enabled: Boolean) {
        notificationPrefs.isNotificationEnabled = enabled
        _isNotificationEnabled.value = enabled
        if (!enabled) {
            NotificationHelper.cancelLowStockNotification(getApplication())
            emitMessage("Notifikasi stok menipis dinonaktifkan")
        } else {
            notificationPrefs.resetSignature()
            checkAndNotifyLowStock(force = true)
            emitMessage("Notifikasi stok menipis diaktifkan")
        }
    }

    fun setSelectedNotificationSound(title: String, uriString: String) {
        notificationPrefs.soundTitle = title
        notificationPrefs.soundUri = uriString
        _selectedNotificationSoundTitle.value = title
        _selectedNotificationSoundUri.value = uriString
        notificationPrefs.resetSignature()
        emitMessage("Suara notifikasi dipilih: $title")
    }

    fun refreshDeviceNotificationSounds() {
        viewModelScope.launch(Dispatchers.IO) {
            val sounds = NotificationHelper.getDeviceNotificationSounds(getApplication())
            _availableNotificationSounds.value = sounds
        }
    }

    fun playPreviewSound(uriString: String) {
        NotificationHelper.playPreviewSound(getApplication(), uriString)
    }

    fun stopPreviewSound() {
        NotificationHelper.stopPreviewSound()
    }

    fun triggerManualLowStockCheck() {
        checkAndNotifyLowStock(force = true)
        val lowStockItems = allProducts.value.filter { it.stok <= it.minimumStokAlert }
        if (lowStockItems.isNotEmpty()) {
            emitMessage("Notifikasi suara dikirim untuk ${lowStockItems.size} produk dengan stok menipis")
        } else {
            emitMessage("Semua produk masih memiliki stok aman!")
        }
    }

    fun checkAndNotifyLowStock(products: List<ProductEntity> = allProducts.value, force: Boolean = false) {
        if (!notificationPrefs.isNotificationEnabled) return

        val lowStockItems = products.filter { it.stok <= it.minimumStokAlert }
        if (lowStockItems.isEmpty()) {
            NotificationHelper.cancelLowStockNotification(getApplication())
            return
        }

        // Tanda tangan unik berdasarkan ID dan sisa stok untuk menghindari spam berulang tanpa perubahan
        val currentSignature = lowStockItems.sortedBy { it.id }.joinToString(";") { "${it.id}:${it.stok}" }
        if (!force && currentSignature == notificationPrefs.lastNotifiedSignature) {
            return
        }

        notificationPrefs.lastNotifiedSignature = currentSignature
        NotificationHelper.showLowStockNotification(
            context = getApplication(),
            lowStockProducts = lowStockItems,
            soundUriString = notificationPrefs.soundUri
        )
    }

    fun testSoundNotification() {
        val currentLowStock = allProducts.value.filter { it.stok <= it.minimumStokAlert }
        val testItems = if (currentLowStock.isNotEmpty()) {
            currentLowStock
        } else {
            listOf(
                ProductEntity(
                    name = "Contoh Produk Menipis",
                    categoryId = 1,
                    categoryName = "Sembako",
                    qrCode = "0000",
                    hargaBeli = 10000.0,
                    hargaJual = 12000.0,
                    stok = 2,
                    minimumStokAlert = 5
                )
            )
        }

        NotificationHelper.showLowStockNotification(
            context = getApplication(),
            lowStockProducts = testItems,
            soundUriString = notificationPrefs.soundUri
        )
        emitMessage("🔔 Notifikasi suara berhasil dikirim!")
    }

    override fun onCleared() {
        super.onCleared()
        NotificationHelper.stopPreviewSound()
    }

    private fun emitMessage(msg: String) {
        viewModelScope.launch {
            _userMessage.emit(msg)
        }
    }
}
