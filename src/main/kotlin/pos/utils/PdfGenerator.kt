package pos.utils

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class InvoiceItem(
    val code: String,
    val description: String,
    val qty: Double,
    val price: Double,
    val total: Double
)

data class InvoiceData(
    val invoiceNo: Long,
    val customerName: String,
    val customerPhone: String?,
    val date: String,
    val items: List<InvoiceItem>,
    val subtotal: Double,
    val tax: Double = 0.0,
    val total: Double,
    val paidAmount: Double,
    val paymentMethod: String
)

object PdfGenerator {
    // Column positions for alignment
    private const val COL_CODE = 50f
    private const val COL_DESC = 120f
    private const val COL_QTY = 350f
    private const val COL_PRICE = 430f
    private const val COL_TOTAL = 510f
    private const val LINE_END = 570f

    fun generateInvoice(invoiceData: InvoiceData, outputPath: String): String {
        val file = File(outputPath)
        file.parentFile?.mkdirs()

        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)

            val helvetica = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val helveticaBold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

            PDPageContentStream(doc, page).use { content ->
                // Title
                content.beginText()
                content.setFont(helveticaBold, 20f)
                content.newLineAtOffset(200f, 750f)
                content.showText("INVOICE")
                content.endText()

                var y = 710f

                // Invoice details
                content.beginText()
                content.setFont(helvetica, 11f)
                content.newLineAtOffset(COL_CODE, y)
                content.showText("Invoice #: ${invoiceData.invoiceNo}")
                content.endText()

                y -= 20
                content.beginText()
                content.setFont(helvetica, 10f)
                content.newLineAtOffset(COL_CODE, y)
                content.showText("Date: ${invoiceData.date}")
                content.endText()

                y -= 15
                content.beginText()
                content.setFont(helvetica, 10f)
                content.newLineAtOffset(COL_CODE, y)
                content.showText("Payment Method: ${invoiceData.paymentMethod}")
                content.endText()

                // Customer info
                y -= 30
                content.beginText()
                content.setFont(helveticaBold, 11f)
                content.newLineAtOffset(COL_CODE, y)
                content.showText("Customer: ${invoiceData.customerName}")
                content.endText()

                if (!invoiceData.customerPhone.isNullOrBlank()) {
                    y -= 15
                    content.beginText()
                    content.setFont(helvetica, 10f)
                    content.newLineAtOffset(COL_CODE, y)
                    content.showText("Mobile: ${invoiceData.customerPhone}")
                    content.endText()
                }

                // Table header
                y -= 30
                drawLine(content, COL_CODE, y, LINE_END, y)
                
                y -= 15
                content.beginText()
                content.setFont(helveticaBold, 10f)
                content.newLineAtOffset(COL_CODE, y)
                content.showText("Code")
                content.endText()

                content.beginText()
                content.setFont(helveticaBold, 10f)
                content.newLineAtOffset(COL_DESC, y)
                content.showText("Description")
                content.endText()

                content.beginText()
                content.setFont(helveticaBold, 10f)
                content.newLineAtOffset(COL_QTY, y)
                content.showText("Qty")
                content.endText()

                content.beginText()
                content.setFont(helveticaBold, 10f)
                content.newLineAtOffset(COL_PRICE, y)
                content.showText("Price")
                content.endText()

                content.beginText()
                content.setFont(helveticaBold, 10f)
                content.newLineAtOffset(COL_TOTAL, y)
                content.showText("Total")
                content.endText()

                y -= 5
                drawLine(content, COL_CODE, y, LINE_END, y)

                // Items
                y -= 15
                content.setFont(helvetica, 9f)
                invoiceData.items.forEach { item ->
                    // Code
                    content.beginText()
                    content.newLineAtOffset(COL_CODE, y)
                    content.showText(item.code.take(10))
                    content.endText()

                    // Description
                    content.beginText()
                    content.newLineAtOffset(COL_DESC, y)
                    content.showText(item.description.take(30))
                    content.endText()

                    // Quantity (right-aligned)
                    val qtyStr = String.format("%.2f", item.qty)
                    content.beginText()
                    content.newLineAtOffset(COL_QTY, y)
                    content.showText(qtyStr)
                    content.endText()

                    // Price (right-aligned)
                    val priceStr = String.format("%.2f", item.price)
                    content.beginText()
                    content.newLineAtOffset(COL_PRICE, y)
                    content.showText(priceStr)
                    content.endText()

                    // Total (right-aligned)
                    val totalStr = String.format("%.2f", item.total)
                    content.beginText()
                    content.newLineAtOffset(COL_TOTAL, y)
                    content.showText(totalStr)
                    content.endText()

                    y -= 15
                }

                // Bottom line
                drawLine(content, COL_CODE, y, LINE_END, y)

                // Summary section
                y -= 20
                content.setFont(helvetica, 11f)

                content.beginText()
                content.newLineAtOffset(COL_PRICE - 30, y)
                content.showText("Subtotal:")
                content.endText()
                content.beginText()
                content.newLineAtOffset(COL_TOTAL, y)
                content.showText(String.format("%.2f", invoiceData.subtotal))
                content.endText()

                y -= 15
                content.beginText()
                content.newLineAtOffset(COL_PRICE - 30, y)
                content.showText("Tax:")
                content.endText()
                content.beginText()
                content.newLineAtOffset(COL_TOTAL, y)
                content.showText(String.format("%.2f", invoiceData.tax))
                content.endText()

                y -= 20
                content.setFont(helveticaBold, 12f)
                content.beginText()
                content.newLineAtOffset(COL_PRICE - 30, y)
                content.showText("Total:")
                content.endText()
                content.beginText()
                content.newLineAtOffset(COL_TOTAL, y)
                content.showText(String.format("%.2f", invoiceData.total))
                content.endText()

                y -= 15
                content.setFont(helvetica, 11f)
                content.beginText()
                content.newLineAtOffset(COL_PRICE - 30, y)
                content.showText("Paid:")
                content.endText()
                content.beginText()
                content.newLineAtOffset(COL_TOTAL, y)
                content.showText(String.format("%.2f", invoiceData.paidAmount))
                content.endText()

                val balance = invoiceData.total - invoiceData.paidAmount
                if (balance != 0.0) {
                    y -= 15
                    content.setFont(helveticaBold, 11f)
                    content.beginText()
                    content.newLineAtOffset(COL_PRICE - 30, y)
                    content.showText("Balance:")
                    content.endText()
                    content.beginText()
                    content.newLineAtOffset(COL_TOTAL, y)
                    content.showText(String.format("%.2f", balance))
                    content.endText()
                }

                // Footer
                y -= 40
                content.setFont(helvetica, 10f)
                content.beginText()
                content.newLineAtOffset(200f, y)
                content.showText("Thank you for your purchase!")
                content.endText()
            }

            doc.save(file)
        }

        return file.absolutePath
    }

    private fun drawLine(content: PDPageContentStream, x1: Float, y1: Float, x2: Float, y2: Float) {
        content.moveTo(x1, y1)
        content.lineTo(x2, y2)
        content.stroke()
    }

    fun generateInvoicePath(invoiceNo: Long): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val today = LocalDateTime.now().format(formatter)
        val fileName = "invoice-$invoiceNo-$today.pdf"
        val dir = File("invoices")
        return File(dir, fileName).absolutePath
    }
}