package com.example.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.data.dao.CategoryDao
import com.example.data.dao.ExpenseDao
import com.example.data.dao.ProductDao
import com.example.data.dao.StoreSettingsDao
import com.example.data.dao.TransactionDao
import com.example.data.model.CategoryEntity
import com.example.data.model.ExpenseEntity
import com.example.data.model.ProductEntity
import com.example.data.model.StoreSettingsEntity
import com.example.data.model.TransactionEntity
import com.example.data.model.TransactionItemEntity
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

enum class SyncStatus {
    OFFLINE,
    SYNCING,
    SYNCED,
    ERROR
}

class CloudSyncManager(
    private val context: Context,
    private val productDao: ProductDao,
    private val categoryDao: CategoryDao,
    private val transactionDao: TransactionDao,
    private val expenseDao: ExpenseDao,
    private val storeSettingsDao: StoreSettingsDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("cloud_sync_prefs", Context.MODE_PRIVATE)

    private val _syncStatus = MutableStateFlow(SyncStatus.OFFLINE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _lastSyncTime = MutableStateFlow(prefs.getLong("last_sync_timestamp", 0L))
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    private val _syncMessage = MutableStateFlow("Menunggu login akun Google untuk sinkronisasi.")
    val syncMessage: StateFlow<String> = _syncMessage.asStateFlow()

    private val _isRealtimeSyncEnabled = MutableStateFlow(prefs.getBoolean("realtime_sync_enabled", true))
    val isRealtimeSyncEnabled: StateFlow<Boolean> = _isRealtimeSyncEnabled.asStateFlow()

    private var activeStoreId: String? = null
    private val listeners = mutableListOf<ListenerRegistration>()

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun getFirestore(): FirebaseFirestore? {
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val customProjectId = prefs.getString("custom_project_id", null) ?: "toko-subur-pos"
                val customApiKey = prefs.getString("custom_api_key", null) ?: "AIzaSyDUMMYKEYFOROFFLINEPOS123456789"
                val options = FirebaseOptions.Builder()
                    .setApplicationId(context.packageName)
                    .setProjectId(customProjectId)
                    .setApiKey(customApiKey)
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
            FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            Log.w("CloudSyncManager", "Firestore initialization: ${e.message}")
            null
        }
    }

    fun setRealtimeSyncEnabled(enabled: Boolean) {
        _isRealtimeSyncEnabled.value = enabled
        prefs.edit().putBoolean("realtime_sync_enabled", enabled).apply()
        if (enabled) {
            activeStoreId?.let { startRealtimeSync(it) }
        } else {
            stopRealtimeSync()
        }
    }

    /**
     * Connects synchronization to the store assigned to the logged-in Google Account.
     * All devices logged in with the same Google Account will share this store ID.
     */
    fun onUserLoggedIn(user: GoogleUser) {
        val sanitizedEmail = user.email.trim().lowercase().replace("@", "_at_").replace(".", "_")
        val storeId = "store_$sanitizedEmail"
        activeStoreId = storeId

        if (_isRealtimeSyncEnabled.value) {
            startRealtimeSync(storeId)
        }
    }

    fun onUserLoggedOut() {
        stopRealtimeSync()
        activeStoreId = null
        _syncStatus.value = SyncStatus.OFFLINE
        _syncMessage.value = "Akun Google telah keluar. Sinkronisasi dihentikan."
    }

    fun startRealtimeSync(storeId: String) {
        stopRealtimeSync()
        activeStoreId = storeId

        if (!isOnline()) {
            _syncStatus.value = SyncStatus.OFFLINE
            _syncMessage.value = "Perangkat sedang offline. Perubahan disimpan secara lokal."
            return
        }

        val firestore = getFirestore()
        if (firestore == null) {
            _syncStatus.value = SyncStatus.OFFLINE
            _syncMessage.value = "Layanan Cloud Firestore belum siap. Mode lokal aktif."
            return
        }

        _syncStatus.value = SyncStatus.SYNCING
        _syncMessage.value = "Menghubungkan sinkronisasi real-time..."

        try {
            val storeDoc = firestore.collection("stores").document(storeId)

            // 1. Listen for Products changes from other devices
            val productListener = storeDoc.collection("products")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e("CloudSync", "Product listen error: ${error.message}")
                        _syncStatus.value = SyncStatus.ERROR
                        _syncMessage.value = "Gagal menyinkronkan produk: ${error.message}"
                        return@addSnapshotListener
                    }
                    snapshot?.let { snap ->
                        scope.launch {
                            for (doc in snap.documents) {
                                val isDeleted = doc.getBoolean("isDeleted") ?: false
                                val id = doc.getLong("id") ?: doc.id.toLongOrNull() ?: continue
                                if (isDeleted) {
                                    productDao.deleteProductById(id)
                                } else {
                                    val name = doc.getString("name") ?: continue
                                    val catId = doc.getLong("categoryId") ?: 1L
                                    val catName = doc.getString("categoryName") ?: "Umum"
                                    val qrCode = doc.getString("qrCode") ?: ""
                                    val beli = doc.getDouble("hargaBeli") ?: 0.0
                                    val jual = doc.getDouble("hargaJual") ?: 0.0
                                    val stok = doc.getLong("stok")?.toInt() ?: 0
                                    val minAlert = doc.getLong("minimumStokAlert")?.toInt() ?: 5

                                    val product = ProductEntity(
                                        id = id,
                                        name = name,
                                        categoryId = catId,
                                        categoryName = catName,
                                        qrCode = qrCode,
                                        hargaBeli = beli,
                                        hargaJual = jual,
                                        stok = stok,
                                        minimumStokAlert = minAlert
                                    )
                                    productDao.insertProduct(product)
                                }
                            }
                            updateSyncTimestamp("Produk tersinkron real-time")
                        }
                    }
                }
            listeners.add(productListener)

            // 2. Listen for Categories changes
            val categoryListener = storeDoc.collection("categories")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    snapshot?.let { snap ->
                        scope.launch {
                            for (doc in snap.documents) {
                                val isDeleted = doc.getBoolean("isDeleted") ?: false
                                val id = doc.getLong("id") ?: doc.id.toLongOrNull() ?: continue
                                if (isDeleted) {
                                    categoryDao.deleteCategoryById(id)
                                } else {
                                    val name = doc.getString("name") ?: continue
                                    categoryDao.insertCategory(CategoryEntity(id = id, name = name))
                                }
                            }
                        }
                    }
                }
            listeners.add(categoryListener)

            // 3. Listen for Transactions changes
            val txListener = storeDoc.collection("transactions")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    snapshot?.let { snap ->
                        scope.launch {
                            for (doc in snap.documents) {
                                val id = doc.getLong("id") ?: doc.id.toLongOrNull() ?: continue
                                val invoice = doc.getString("invoiceNumber") ?: "TM-${id}"
                                val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                                val total = doc.getDouble("totalAmount") ?: 0.0
                                val cost = doc.getDouble("totalCost") ?: 0.0
                                val payType = doc.getString("paymentType") ?: "TUNAI"
                                val paid = doc.getDouble("paidAmount") ?: total
                                val change = doc.getDouble("changeAmount") ?: 0.0
                                val notes = doc.getString("notes") ?: ""

                                val tx = TransactionEntity(
                                    id = id,
                                    invoiceNumber = invoice,
                                    timestamp = timestamp,
                                    totalAmount = total,
                                    totalCost = cost,
                                    paymentType = payType,
                                    paidAmount = paid,
                                    changeAmount = change,
                                    notes = notes
                                )
                                transactionDao.insertTransaction(tx)
                            }
                            updateSyncTimestamp("Transaksi tersinkron real-time")
                        }
                    }
                }
            listeners.add(txListener)

            // 4. Listen for Expenses changes
            val expenseListener = storeDoc.collection("expenses")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    snapshot?.let { snap ->
                        scope.launch {
                            for (doc in snap.documents) {
                                val isDeleted = doc.getBoolean("isDeleted") ?: false
                                val id = doc.getLong("id") ?: doc.id.toLongOrNull() ?: continue
                                if (isDeleted) {
                                    expenseDao.deleteExpenseById(id)
                                } else {
                                    val title = doc.getString("title") ?: continue
                                    val category = doc.getString("category") ?: "Operasional"
                                    val amount = doc.getDouble("amount") ?: 0.0
                                    val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                                    val notes = doc.getString("notes") ?: ""

                                    val expense = ExpenseEntity(
                                        id = id,
                                        title = title,
                                        category = category,
                                        amount = amount,
                                        timestamp = timestamp,
                                        notes = notes
                                    )
                                    expenseDao.insertExpense(expense)
                                }
                            }
                        }
                    }
                }
            listeners.add(expenseListener)

            // 5. Listen for Settings changes (Store Profile & Modal Kas)
            val settingsListener = storeDoc.collection("settings").document("profile")
                .addSnapshotListener { doc, error ->
                    if (error != null) return@addSnapshotListener
                    doc?.let { d ->
                        if (d.exists()) {
                            scope.launch {
                                val current = storeSettingsDao.getSettingsSync() ?: StoreSettingsEntity()
                                val storeName = d.getString("storeName") ?: current.storeName
                                val address = d.getString("storeAddress") ?: current.storeAddress
                                val phone = d.getString("storePhone") ?: current.storePhone
                                val footer = d.getString("receiptFooter") ?: current.receiptFooter
                                val capital = d.getDouble("initialCashCapital") ?: current.initialCashCapital
                                val nominals = d.getString("quickNominals") ?: current.quickNominals

                                val updated = current.copy(
                                    storeName = storeName,
                                    storeAddress = address,
                                    storePhone = phone,
                                    receiptFooter = footer,
                                    initialCashCapital = capital,
                                    quickNominals = nominals
                                )
                                storeSettingsDao.insertOrUpdateSettings(updated)
                            }
                        }
                    }
                }
            listeners.add(settingsListener)

            _syncStatus.value = SyncStatus.SYNCED
            _syncMessage.value = "🟢 Sinkronisasi Real-Time Aktif (Multi-Ponsel Terhubung)"
        } catch (e: Exception) {
            Log.e("CloudSync", "Error setting up listeners: ${e.message}", e)
            _syncStatus.value = SyncStatus.ERROR
            _syncMessage.value = "Kendala koneksi cloud: ${e.message}"
        }
    }

    fun stopRealtimeSync() {
        for (listener in listeners) {
            listener.remove()
        }
        listeners.clear()
    }

    private fun updateSyncTimestamp(message: String) {
        val now = System.currentTimeMillis()
        _lastSyncTime.value = now
        _syncStatus.value = SyncStatus.SYNCED
        _syncMessage.value = message
        prefs.edit().putLong("last_sync_timestamp", now).apply()
    }

    // -------------------------------------------------------------
    // PUSH LOCAL CHANGES TO CLOUD
    // -------------------------------------------------------------
    fun pushProduct(product: ProductEntity) {
        val storeId = activeStoreId ?: return
        if (!_isRealtimeSyncEnabled.value || !isOnline()) return

        scope.launch {
            try {
                val firestore = getFirestore() ?: return@launch
                val data = hashMapOf(
                    "id" to product.id,
                    "name" to product.name,
                    "categoryId" to product.categoryId,
                    "categoryName" to product.categoryName,
                    "qrCode" to product.qrCode,
                    "hargaBeli" to product.hargaBeli,
                    "hargaJual" to product.hargaJual,
                    "stok" to product.stok,
                    "minimumStokAlert" to product.minimumStokAlert,
                    "updatedAt" to System.currentTimeMillis(),
                    "isDeleted" to false
                )
                firestore.collection("stores").document(storeId)
                    .collection("products").document(product.id.toString())
                    .set(data, SetOptions.merge())
            } catch (e: Exception) {
                Log.w("CloudSync", "pushProduct failed: ${e.message}")
            }
        }
    }

    fun pushDeleteProduct(productId: Long) {
        val storeId = activeStoreId ?: return
        if (!_isRealtimeSyncEnabled.value || !isOnline()) return

        scope.launch {
            try {
                val firestore = getFirestore() ?: return@launch
                val data = hashMapOf("isDeleted" to true, "updatedAt" to System.currentTimeMillis())
                firestore.collection("stores").document(storeId)
                    .collection("products").document(productId.toString())
                    .set(data, SetOptions.merge())
            } catch (e: Exception) {
                Log.w("CloudSync", "pushDeleteProduct failed: ${e.message}")
            }
        }
    }

    fun pushCategory(category: CategoryEntity) {
        val storeId = activeStoreId ?: return
        if (!_isRealtimeSyncEnabled.value || !isOnline()) return

        scope.launch {
            try {
                val firestore = getFirestore() ?: return@launch
                val data = hashMapOf(
                    "id" to category.id,
                    "name" to category.name,
                    "updatedAt" to System.currentTimeMillis(),
                    "isDeleted" to false
                )
                firestore.collection("stores").document(storeId)
                    .collection("categories").document(category.id.toString())
                    .set(data, SetOptions.merge())
            } catch (e: Exception) {
                Log.w("CloudSync", "pushCategory failed: ${e.message}")
            }
        }
    }

    fun pushDeleteCategory(categoryId: Long) {
        val storeId = activeStoreId ?: return
        if (!_isRealtimeSyncEnabled.value || !isOnline()) return

        scope.launch {
            try {
                val firestore = getFirestore() ?: return@launch
                val data = hashMapOf("isDeleted" to true, "updatedAt" to System.currentTimeMillis())
                firestore.collection("stores").document(storeId)
                    .collection("categories").document(categoryId.toString())
                    .set(data, SetOptions.merge())
            } catch (e: Exception) {
                Log.w("CloudSync", "pushDeleteCategory failed: ${e.message}")
            }
        }
    }

    fun pushTransaction(tx: TransactionEntity, items: List<TransactionItemEntity>) {
        val storeId = activeStoreId ?: return
        if (!_isRealtimeSyncEnabled.value || !isOnline()) return

        scope.launch {
            try {
                val firestore = getFirestore() ?: return@launch
                val txData = hashMapOf(
                    "id" to tx.id,
                    "invoiceNumber" to tx.invoiceNumber,
                    "timestamp" to tx.timestamp,
                    "totalAmount" to tx.totalAmount,
                    "totalCost" to tx.totalCost,
                    "paymentType" to tx.paymentType,
                    "paidAmount" to tx.paidAmount,
                    "changeAmount" to tx.changeAmount,
                    "notes" to tx.notes,
                    "syncedAt" to System.currentTimeMillis()
                )
                val txDoc = firestore.collection("stores").document(storeId)
                    .collection("transactions").document(tx.id.toString())

                txDoc.set(txData, SetOptions.merge())

                // Also push transaction items
                for (item in items) {
                    val itemData = hashMapOf(
                        "id" to item.id,
                        "transactionId" to tx.id,
                        "productId" to item.productId,
                        "productName" to item.productName,
                        "qrCode" to item.qrCode,
                        "quantity" to item.quantity,
                        "unitPrice" to item.unitPrice,
                        "unitCost" to item.unitCost,
                        "subtotal" to item.subtotal
                    )
                    txDoc.collection("items").document(item.id.toString())
                        .set(itemData, SetOptions.merge())
                }
            } catch (e: Exception) {
                Log.w("CloudSync", "pushTransaction failed: ${e.message}")
            }
        }
    }

    fun pushExpense(expense: ExpenseEntity) {
        val storeId = activeStoreId ?: return
        if (!_isRealtimeSyncEnabled.value || !isOnline()) return

        scope.launch {
            try {
                val firestore = getFirestore() ?: return@launch
                val data = hashMapOf(
                    "id" to expense.id,
                    "title" to expense.title,
                    "category" to expense.category,
                    "amount" to expense.amount,
                    "timestamp" to expense.timestamp,
                    "notes" to expense.notes,
                    "updatedAt" to System.currentTimeMillis(),
                    "isDeleted" to false
                )
                firestore.collection("stores").document(storeId)
                    .collection("expenses").document(expense.id.toString())
                    .set(data, SetOptions.merge())
            } catch (e: Exception) {
                Log.w("CloudSync", "pushExpense failed: ${e.message}")
            }
        }
    }

    fun pushDeleteExpense(expenseId: Long) {
        val storeId = activeStoreId ?: return
        if (!_isRealtimeSyncEnabled.value || !isOnline()) return

        scope.launch {
            try {
                val firestore = getFirestore() ?: return@launch
                val data = hashMapOf("isDeleted" to true, "updatedAt" to System.currentTimeMillis())
                firestore.collection("stores").document(storeId)
                    .collection("expenses").document(expenseId.toString())
                    .set(data, SetOptions.merge())
            } catch (e: Exception) {
                Log.w("CloudSync", "pushDeleteExpense failed: ${e.message}")
            }
        }
    }

    fun pushSettings(settings: StoreSettingsEntity) {
        val storeId = activeStoreId ?: return
        if (!_isRealtimeSyncEnabled.value || !isOnline()) return

        scope.launch {
            try {
                val firestore = getFirestore() ?: return@launch
                val data = hashMapOf(
                    "storeName" to settings.storeName,
                    "storeAddress" to settings.storeAddress,
                    "storePhone" to settings.storePhone,
                    "receiptFooter" to settings.receiptFooter,
                    "initialCashCapital" to settings.initialCashCapital,
                    "quickNominals" to settings.quickNominals,
                    "updatedAt" to System.currentTimeMillis()
                )
                firestore.collection("stores").document(storeId)
                    .collection("settings").document("profile")
                    .set(data, SetOptions.merge())
            } catch (e: Exception) {
                Log.w("CloudSync", "pushSettings failed: ${e.message}")
            }
        }
    }

    /**
     * Uploads ALL current local data to the Cloud Store.
     * Useful when first signing in with a Google account to seed the online database.
     */
    suspend fun syncAllLocalToCloud(): Result<String> = withContext(Dispatchers.IO) {
        val storeId = activeStoreId ?: return@withContext Result.failure(IllegalStateException("Silakan masuk dengan akun Google terlebih dahulu."))
        if (!isOnline()) return@withContext Result.failure(IllegalStateException("Koneksi internet tidak tersedia."))

        val firestore = getFirestore() ?: return@withContext Result.failure(IllegalStateException("Layanan Firestore belum siap."))

        _syncStatus.value = SyncStatus.SYNCING
        _syncMessage.value = "Mengunggah seluruh data lokal ke cloud..."

        try {
            val storeDoc = firestore.collection("stores").document(storeId)

            val categories = categoryDao.getAllCategoriesSync()
            for (cat in categories) {
                storeDoc.collection("categories").document(cat.id.toString()).set(
                    hashMapOf("id" to cat.id, "name" to cat.name, "updatedAt" to System.currentTimeMillis(), "isDeleted" to false)
                ).await()
            }

            val products = productDao.getAllProductsSync()
            for (p in products) {
                storeDoc.collection("products").document(p.id.toString()).set(
                    hashMapOf(
                        "id" to p.id,
                        "name" to p.name,
                        "categoryId" to p.categoryId,
                        "categoryName" to p.categoryName,
                        "qrCode" to p.qrCode,
                        "hargaBeli" to p.hargaBeli,
                        "hargaJual" to p.hargaJual,
                        "stok" to p.stok,
                        "minimumStokAlert" to p.minimumStokAlert,
                        "updatedAt" to System.currentTimeMillis(),
                        "isDeleted" to false
                    )
                ).await()
            }

            val transactions = transactionDao.getAllTransactionsSync()
            for (t in transactions) {
                storeDoc.collection("transactions").document(t.id.toString()).set(
                    hashMapOf(
                        "id" to t.id,
                        "invoiceNumber" to t.invoiceNumber,
                        "timestamp" to t.timestamp,
                        "totalAmount" to t.totalAmount,
                        "totalCost" to t.totalCost,
                        "paymentType" to t.paymentType,
                        "paidAmount" to t.paidAmount,
                        "changeAmount" to t.changeAmount,
                        "notes" to t.notes
                    )
                ).await()
            }

            val expenses = expenseDao.getAllExpensesSync()
            for (e in expenses) {
                storeDoc.collection("expenses").document(e.id.toString()).set(
                    hashMapOf(
                        "id" to e.id,
                        "title" to e.title,
                        "category" to e.category,
                        "amount" to e.amount,
                        "timestamp" to e.timestamp,
                        "notes" to e.notes,
                        "updatedAt" to System.currentTimeMillis(),
                        "isDeleted" to false
                    )
                ).await()
            }

            val settings = storeSettingsDao.getSettingsSync()
            if (settings != null) {
                storeDoc.collection("settings").document("profile").set(
                    hashMapOf(
                        "storeName" to settings.storeName,
                        "storeAddress" to settings.storeAddress,
                        "storePhone" to settings.storePhone,
                        "receiptFooter" to settings.receiptFooter,
                        "initialCashCapital" to settings.initialCashCapital,
                        "quickNominals" to settings.quickNominals,
                        "updatedAt" to System.currentTimeMillis()
                    )
                ).await()
            }

            updateSyncTimestamp("Berhasil mengunggah ${products.size} produk & ${transactions.size} transaksi ke cloud.")
            Result.success("Sinkronisasi unggah ke Cloud berhasil!")
        } catch (e: Exception) {
            _syncStatus.value = SyncStatus.ERROR
            _syncMessage.value = "Gagal mengunggah data: ${e.message}"
            Result.failure(e)
        }
    }
}
