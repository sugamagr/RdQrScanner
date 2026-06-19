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
     * Generates a share-banner image for a single LOT.
     *
     * Renders LOT number, scanned count, and (when present) the defaulter
     * count on an orange band. Designed for the WhatsApp share intent so
     * recipients see the LOT identity at a glance before the number list.
     */
    fun generateLotImage(
        context: Context,
        lotNumber: Int,
        rdNumberCount: Int,
        defaultCount: Int = 0
    ): File? {
        return try {
            val width = 400
            val height = if (defaultCount > 0) 240 else 200

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val baseColor = if (defaultCount > 0) "#F39C12" else "#FF9F43"
            val bgPaint = Paint().apply {
                color = Color.parseColor(baseColor)
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            val overlayPaint = Paint().apply {
                color = Color.parseColor("#FFBE76")
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, width.toFloat(), 8f, overlayPaint)
            canvas.drawRect(0f, (height - 8).toFloat(), width.toFloat(), height.toFloat(), overlayPaint)

            val lotTextPaint = Paint().apply {
                color = Color.WHITE
                textSize = 72f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            val lotY = if (defaultCount > 0) height / 2f - 8f else height / 2f + 10f
            canvas.drawText("LOT $lotNumber", width / 2f, lotY, lotTextPaint)

            val countPaint = Paint().apply {
                color = Color.WHITE
                alpha = 220
                textSize = 28f
                typeface = Typeface.DEFAULT
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            canvas.drawText("$rdNumberCount RD Numbers", width / 2f, lotY + 42f, countPaint)

            if (defaultCount > 0) {
                val defaulterPaint = Paint().apply {
                    color = Color.WHITE
                    alpha = 235
                    textSize = 24f
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                val plural = if (defaultCount == 1) "defaulter" else "defaulters"
                canvas.drawText("$defaultCount $plural", width / 2f, lotY + 78f, defaulterPaint)
            }

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



