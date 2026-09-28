package com.example.util

import java.text.NumberFormat
import java.util.Locale

object CurrencyFormatter {
    private val indonesianLocale = Locale("id", "ID")
    private val formatter: NumberFormat = NumberFormat.getCurrencyInstance(indonesianLocale).apply {
        maximumFractionDigits = 0
        minimumFractionDigits = 0
    }

    fun formatRupiah(amount: Double): String {
        return try {
            formatter.format(amount).replace("Rp", "Rp ")
        } catch (e: Exception) {
            "Rp ${amount.toLong()}"
        }
    }

    fun formatRupiah(amount: Long): String {
        return formatRupiah(amount.toDouble())
    }

    fun parseAmount(input: String): Double {
        val cleaned = input.replace(Regex("[^0-9]"), "")
        return cleaned.toDoubleOrNull() ?: 0.0
    }
}
