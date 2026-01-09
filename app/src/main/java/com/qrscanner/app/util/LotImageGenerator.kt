package com.qrscanner.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object LotImageGenerator {
    
    /**
     * Generates a simple image showing the LOT number
     * This is for sharing on WhatsApp so users know which LOT the numbers belong to
     */
    fun generateLotImage(
        context: Context,
        lotNumber: Int,
        rdNumberCount: Int
    ): File? {
        return try {
            val width = 400
            val height = 200
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // Background - Orange gradient effect
            val bgPaint = Paint().apply {
                color = Color.parseColor("#FF9F43")
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            
            // Add a slight overlay pattern
            val overlayPaint = Paint().apply {
                color = Color.parseColor("#FFBE76")
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, width.toFloat(), 8f, overlayPaint)
            canvas.drawRect(0f, (height - 8).toFloat(), width.toFloat(), height.toFloat(), overlayPaint)
            
            // LOT text
            val lotTextPaint = Paint().apply {
                color = Color.WHITE
                textSize = 72f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            canvas.drawText("LOT $lotNumber", width / 2f, height / 2f + 10f, lotTextPaint)
            
            // Count text
            val countPaint = Paint().apply {
                color = Color.parseColor("#FFFFFF")
                alpha = 200
                textSize = 28f
                typeface = Typeface.DEFAULT
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            canvas.drawText("$rdNumberCount RD Numbers", width / 2f, height / 2f + 55f, countPaint)
            
            // Save to file
            val fileName = "LOT_${lotNumber}_${System.currentTimeMillis()}.png"
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val file = File(downloadsDir, fileName)
            
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            bitmap.recycle()
            file
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



