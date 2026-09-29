package com.example.util

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.model.StoreSettingsEntity
import com.example.data.model.TransactionEntity
import com.example.data.model.TransactionItemEntity

object PrintHelper {

    fun generateReceiptText(
        settings: StoreSettingsEntity,
        transaction: TransactionEntity,
        items: List<TransactionItemEntity>
    ): String {
        val sb = StringBuilder()
        val line = "--------------------------------"
        val doubleLine = "================================"

        sb.append(doubleLine).append("\n")
        sb.append("        ").append(settings.storeName).append("\n")
        sb.append("   ").append(settings.storeAddress).append("\n")
        if (settings.storePhone.isNotBlank()) {
            sb.append("         Telp: ").append(settings.storePhone).append("\n")
        }
        sb.append(doubleLine).append("\n")
        sb.append("No. Struk : ").append(transaction.invoiceNumber).append("\n")
        sb.append("Waktu     : ").append(DateFormatter.formatReceiptDateTime(transaction.timestamp)).append("\n")
        sb.append("Kasir     : ").append(settings.storeName).append("\n")
        sb.append(line).append("\n")

        for (item in items) {
            sb.append(item.productName).append("\n")
            val qtyPrice = "  ${item.quantity} x ${CurrencyFormatter.formatRupiah(item.unitPrice)}"
            val subtotal = CurrencyFormatter.formatRupiah(item.subtotal)
            val spaces = " ".repeat((32 - qtyPrice.length - subtotal.length).coerceAtLeast(1))
            sb.append(qtyPrice).append(spaces).append(subtotal).append("\n")
        }

        sb.append(line).append("\n")
        val totalLabel = "TOTAL BELANJA:"
        val totalVal = CurrencyFormatter.formatRupiah(transaction.totalAmount)
        val spTotal = " ".repeat((32 - totalLabel.length - totalVal.length).coerceAtLeast(1))
        sb.append(totalLabel).append(spTotal).append(totalVal).append("\n")

        val payMethod = if (transaction.paymentType == "NON_TUNAI") "NON-TUNAI (QRIS/TRANSFER)" else "TUNAI"
        sb.append("Metode    : ").append(payMethod).append("\n")

        val bayarLabel = "Bayar     :"
        val bayarVal = CurrencyFormatter.formatRupiah(transaction.paidAmount)
        val spBayar = " ".repeat((32 - bayarLabel.length - bayarVal.length).coerceAtLeast(1))
        sb.append(bayarLabel).append(spBayar).append(bayarVal).append("\n")

        val kembaliLabel = "Kembalian :"
        val kembaliVal = CurrencyFormatter.formatRupiah(transaction.changeAmount)
        val spKembali = " ".repeat((32 - kembaliLabel.length - kembaliVal.length).coerceAtLeast(1))
        sb.append(kembaliLabel).append(spKembali).append(kembaliVal).append("\n")

        sb.append(doubleLine).append("\n")
        sb.append(settings.receiptFooter).append("\n")
        sb.append(doubleLine).append("\n")

        return sb.toString()
    }

    fun shareReceipt(
        context: Context,
        settings: StoreSettingsEntity,
        transaction: TransactionEntity,
        items: List<TransactionItemEntity>
    ) {
        val receiptText = generateReceiptText(settings, transaction, items)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Struk Pembelian - ${settings.storeName}")
            putExtra(Intent.EXTRA_TEXT, receiptText)
        }
        val chooser = Intent.createChooser(intent, "Bagikan Struk Belanja")
        context.startActivity(chooser)
    }

    fun printReceipt(
        context: Context,
        settings: StoreSettingsEntity,
        transaction: TransactionEntity,
        items: List<TransactionItemEntity>
    ) {
        val htmlContent = buildHtmlReceipt(settings, transaction, items)

        val webView = WebView(context)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

            override fun onPageFinished(view: WebView, url: String) {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                val printAdapter = webView.createPrintDocumentAdapter(transaction.invoiceNumber)
                val printAttributes = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A6)
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build()
                printManager?.print(
                    "Struk_${transaction.invoiceNumber}",
                    printAdapter,
                    printAttributes
                )
            }
        }
        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
    }

    private fun buildHtmlReceipt(
        settings: StoreSettingsEntity,
        transaction: TransactionEntity,
        items: List<TransactionItemEntity>
    ): String {
        val itemsHtml = StringBuilder()
        for (item in items) {
            itemsHtml.append(
                """
                <tr>
                    <td colspan="2" style="font-weight:bold; padding-top:4px;">${item.productName}</td>
                </tr>
                <tr>
                    <td style="color:#555;">${item.quantity} x ${CurrencyFormatter.formatRupiah(item.unitPrice)}</td>
                    <td style="text-align:right;">${CurrencyFormatter.formatRupiah(item.subtotal)}</td>
                </tr>
                """.trimIndent()
            )
        }

        val payMethod = if (transaction.paymentType == "NON_TUNAI") "NON-TUNAI" else "TUNAI"

        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <style>
                body {
                    font-family: 'Courier New', Courier, monospace;
                    width: 280px;
                    margin: 0 auto;
                    padding: 10px;
                    font-size: 13px;
                    color: #000;
                }
                .text-center { text-align: center; }
                .store-name { font-size: 16px; font-weight: bold; margin-bottom: 2px; }
                .store-addr { font-size: 11px; margin-bottom: 6px; }
                .divider { border-top: 1px dashed #000; margin: 6px 0; }
                .double-divider { border-top: 2px solid #000; margin: 6px 0; }
                table { width: 100%; border-collapse: collapse; }
                td { padding: 2px 0; }
                .bold { font-weight: bold; }
                .footer { font-size: 11px; text-align: center; margin-top: 8px; white-space: pre-line; }
            </style>
        </head>
        <body>
            <div class="text-center">
                <div class="store-name">${settings.storeName}</div>
                <div class="store-addr">${settings.storeAddress}</div>
                ${if (settings.storePhone.isNotBlank()) "<div class='store-addr'>Telp: " + settings.storePhone + "</div>" else ""}
            </div>
            <div class="double-divider"></div>
            <div>
                <div>No: <b>${transaction.invoiceNumber}</b></div>
                <div>Tgl: ${DateFormatter.formatReceiptDateTime(transaction.timestamp)}</div>
            </div>
            <div class="divider"></div>
            <table>
                ${itemsHtml}
            </table>
            <div class="divider"></div>
            <table>
                <tr class="bold">
                    <td>TOTAL:</td>
                    <td style="text-align:right;">${CurrencyFormatter.formatRupiah(transaction.totalAmount)}</td>
                </tr>
                <tr>
                    <td>Metode:</td>
                    <td style="text-align:right;">${payMethod}</td>
                </tr>
                <tr>
                    <td>Bayar:</td>
                    <td style="text-align:right;">${CurrencyFormatter.formatRupiah(transaction.paidAmount)}</td>
                </tr>
                <tr>
                    <td>Kembalian:</td>
                    <td style="text-align:right;">${CurrencyFormatter.formatRupiah(transaction.changeAmount)}</td>
                </tr>
            </table>
            <div class="double-divider"></div>
            <div class="footer">${settings.receiptFooter}</div>
        </body>
        </html>
        """.trimIndent()
    }
}
