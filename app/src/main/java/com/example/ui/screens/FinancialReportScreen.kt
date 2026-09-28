package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Money
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.viewmodel.PeriodFilter
import com.example.ui.viewmodel.TokoViewModel
import com.example.util.CurrencyFormatter

@Composable
fun FinancialReportScreen(
    viewModel: TokoViewModel,
    modifier: Modifier = Modifier
) {
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()
    val periodTransactions by viewModel.periodTransactions.collectAsStateWithLifecycle()
    val periodExpenses by viewModel.periodExpenses.collectAsStateWithLifecycle()
    val storeSettings by viewModel.storeSettings.collectAsStateWithLifecycle()

    // Financial calculations
    val pendapatanKotor = periodTransactions.sumOf { it.totalAmount }
    val totalHpp = periodTransactions.sumOf { it.totalCost }
    val totalPengeluaran = periodExpenses.sumOf { it.amount }
    val labaKotor = pendapatanKotor - totalHpp
    val pendapatanBersih = labaKotor - totalPengeluaran

    // Cash reconciliation calculations
    val penjualanTunai = periodTransactions.filter { it.paymentType == "TUNAI" }.sumOf { it.totalAmount }
    val penjualanNonTunai = periodTransactions.filter { it.paymentType == "NON_TUNAI" }.sumOf { it.totalAmount }
    val modalKasAwal = storeSettings.initialCashCapital
    val estimasiUangKasFisik = modalKasAwal + penjualanTunai - totalPengeluaran

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Laporan Keuangan Toko",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Analisis pendapatan kotor, bersih, serta laporan khusus kas tunai & non-tunai",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Period Tabs: Hari Ini, Minggu Ini, Bulan Ini, Semua
                TabRow(
                    selectedTabIndex = selectedPeriod.ordinal,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .testTag("report_period_tabs")
                ) {
                    PeriodFilter.values().forEach { period ->
                        Tab(
                            selected = selectedPeriod == period,
                            onClick = { viewModel.setSelectedPeriod(period) },
                            text = {
                                Text(
                                    text = period.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedPeriod == period) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            modifier = Modifier.testTag("period_tab_${period.name}")
                        )
                    }
                }
            }
        }

        // Scrollable Report Body
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main Revenue & Profit Summary Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Pendapatan Kotor Card
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.ArrowUpward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Pendapatan Kotor",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = CurrencyFormatter.formatRupiah(pendapatanKotor),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "${periodTransactions.size} Transaksi Penjualan",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }

                // Pendapatan Bersih Card
                val isProfitPositive = pendapatanBersih >= 0
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isProfitPositive) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Insights,
                                contentDescription = null,
                                tint = if (isProfitPositive) Color(0xFF166534) else Color(0xFF991B1B),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Pendapatan Bersih",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isProfitPositive) Color(0xFF166534) else Color(0xFF991B1B)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = CurrencyFormatter.formatRupiah(pendapatanBersih),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isProfitPositive) Color(0xFF166534) else Color(0xFF991B1B)
                        )
                        Text(
                            text = "Laba Bersih Toko",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isProfitPositive) Color(0xFF166534).copy(alpha = 0.8f) else Color(0xFF991B1B).copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Breakdown Table Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Rincian Laba Rugi (${selectedPeriod.label})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    ReportDetailRow("1. Total Penjualan (Omzet Kotor)", CurrencyFormatter.formatRupiah(pendapatanKotor), isPositive = true)
                    ReportDetailRow("2. Modal Pokok Barang Terjual (HPP)", "- ${CurrencyFormatter.formatRupiah(totalHpp)}", isNegative = true)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    ReportDetailRow("Laba Kotor Penjualan", CurrencyFormatter.formatRupiah(labaKotor), isBold = true)

                    Spacer(modifier = Modifier.height(4.dp))
                    ReportDetailRow("3. Biaya Pengeluaran Operasional", "- ${CurrencyFormatter.formatRupiah(totalPengeluaran)}", isNegative = true)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)

                    ReportDetailRow(
                        "PENDAPATAN BERSIH AKHIR",
                        CurrencyFormatter.formatRupiah(pendapatanBersih),
                        isBold = true,
                        fontSize = 14.sp,
                        valueColor = if (pendapatanBersih >= 0) Color(0xFF16A34A) else Color(0xFFDC2626)
                    )
                }
            }

            // SPECIAL NON-TUNAI & CASH RECONCILIATION REPORT
            // "Dan untuk pembayaran Non Tunai terdapat laporan khusus supaya uang tunai yang ada sesuai dengan laporan penjualan"
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.PointOfSale,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Laporan Khusus Uang Kas vs Non-Tunai",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = "Pencocokan uang tunai fisik di laci kasir",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Split Cash vs Non-Cash
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Tunai Box
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Money, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF16A34A))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Penjualan Tunai", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = CurrencyFormatter.formatRupiah(penjualanTunai),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF166534)
                                )
                            }
                        }

                        // Non-Tunai Box
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CreditCard, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Non-Tunai (QRIS)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = CurrencyFormatter.formatRupiah(penjualanNonTunai),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Reconciliation calculation box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF1F5F9))
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = "Rekonsiliasi Uang Fisik di Laci Kasir:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF334155)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            ReportDetailRow("Modal Kas Awal Laci", "+ ${CurrencyFormatter.formatRupiah(modalKasAwal)}")
                            ReportDetailRow("Pemasukan Penjualan Tunai", "+ ${CurrencyFormatter.formatRupiah(penjualanTunai)}")
                            ReportDetailRow("Pengeluaran Kas Tunai", "- ${CurrencyFormatter.formatRupiah(totalPengeluaran)}")
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color(0xFFCBD5E1))
                            ReportDetailRow(
                                "Fisik Uang Tunai di Laci Kasir",
                                CurrencyFormatter.formatRupiah(estimasiUangKasFisik),
                                isBold = true,
                                valueColor = Color(0xFF0F766E),
                                fontSize = 13.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Catatan: Uang non-tunai (QRIS/Transfer) tidak dimasukkan ke dalam laci kasir melainkan masuk langsung ke rekening bank toko.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportDetailRow(
    label: String,
    value: String,
    isBold: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    valueColor: Color = Color(0xFF0F172A),
    isPositive: Boolean = false,
    isNegative: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = fontSize,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            color = Color(0xFF334155)
        )
        Text(
            text = value,
            fontSize = fontSize,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.SemiBold,
            color = when {
                isPositive -> Color(0xFF16A34A)
                isNegative -> Color(0xFFDC2626)
                else -> valueColor
            }
        )
    }
}
