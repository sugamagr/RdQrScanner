package com.qrscanner.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream

object PdfGenerator {
    
    // A4 dimensions in points (1 inch = 72 points)
    private const val A4_WIDTH = 595 // ~210mm
    private const val A4_HEIGHT = 842 // ~297mm
    
    // QR code size tuned for 6×4 grid on A4
    private const val QR_SIZE = 80
    private const val QR_WITH_TEXT_HEIGHT = 97  // QR_SIZE + 17 for text area

    // Grid layout: 6 columns × 4 rows = 24 per page
    // Width check: 25 + 6×80 + 5×13 + 25 = 595 ✓
    private const val COLUMNS = 6
    private const val ROWS = 4
    private const val MARGIN = 25
    private const val SPACING_X = 13
    private const val SPACING_Y = 30
    
    fun generatePdf(
        context: Context,
        rdNumbers: List<String>,
        fileName: String = "RD_QR_Codes_${System.currentTimeMillis()}.pdf"
    ): File? {
        if (rdNumbers.isEmpty()) return null
        
        val pdfDocument = PdfDocument()
        
        try {
            // Fixed grid: 6 columns × 4 rows = 24 per page
            val itemsPerPage = COLUMNS * ROWS
            
            // Calculate number of pages
            val totalPages = (rdNumbers.size + itemsPerPage - 1) / itemsPerPage
            
            val textPaint = Paint().apply {
                color = Color.BLACK
                textSize = 7.5f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            
            for (pageIndex in 0 until totalPages) {
                val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, pageIndex + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas
                
                // White background
                canvas.drawColor(Color.WHITE)
                
                val startIndex = pageIndex * itemsPerPage
                val endIndex = minOf(startIndex + itemsPerPage, rdNumbers.size)
                
                for (i in startIndex until endIndex) {
                    val itemIndex = i - startIndex
                    val col = itemIndex % COLUMNS
                    val row = itemIndex / COLUMNS
                    
                    val x = MARGIN + col * (QR_SIZE + SPACING_X)
                    val y = MARGIN + row * (QR_WITH_TEXT_HEIGHT + SPACING_Y)
                    
                    val rdNumber = rdNumbers[i]
                    
                    // Generate QR code bitmap
                    val qrBitmap = generateQRBitmap(rdNumber, QR_SIZE)
                    if (qrBitmap != null) {
                        canvas.drawBitmap(qrBitmap, x.toFloat(), y.toFloat(), null)
                        
                        // Draw account number CENTERED below QR code
                        val textY = y + QR_SIZE + 12f
                        val textX = x + QR_SIZE / 2f
                        canvas.drawText(rdNumber, textX, textY, textPaint)
                        
                        // Recycle bitmap to free memory
                        qrBitmap.recycle()
                    }
                }
                
                pdfDocument.finishPage(page)
            }
            
            // Save PDF
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val file = File(downloadsDir, fileName)
            
            FileOutputStream(file).use { outputStream ->
                pdfDocument.writeTo(outputStream)
            }
            
            return file
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            pdfDocument.close()
        }
    }
    
    private fun generateQRBitmap(content: String, size: Int): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1
            )
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun getShareableUri(context: Context, file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file
    )
}
