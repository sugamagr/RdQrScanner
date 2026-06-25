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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LotImageGenerator {

    private const val BANNER_WIDTH = 400
    private const val BANNER_HEIGHT_PLAIN = 200
    private const val BANNER_HEIGHT_DEFAULTERS = 240
    private const val EDGE_INSET = 16f

    /**
     * Generates a share-banner image for a single LOT.
     *
     * Renders LOT number, scanned count, and (when present) a defaulter
     * summary that includes both the defaulter count and the total months
     * those defaulters represent ('2 defaulters · 6 months'). Designed for
     * the WhatsApp share intent so recipients see the LOT identity and
     * defaulter scale at a glance before reading the text body.
     *
     * Text size on the defaulter line auto-shrinks if the rendered string
     * would overflow the banner width, so high-count LOTs stay legible.
     */
    fun generateLotImage(
        context: Context,
        lotNumber: Int,
        rdNumberCount: Int,
        defaultCount: Int = 0,
        totalMonths: Int = 0
    ): File? {
        val hasDefaulters = defaultCount > 0
        val height = if (hasDefaulters) BANNER_HEIGHT_DEFAULTERS else BANNER_HEIGHT_PLAIN
        val width = BANNER_WIDTH

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)

            val baseColor = if (hasDefaulters) "#F39C12" else "#FF9F43"
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
            val lotY = if (hasDefaulters) height / 2f - 8f else height / 2f + 10f
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

            if (hasDefaulters) {
                val defaulterPaint = Paint().apply {
                    color = Color.WHITE
                    alpha = 235
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                val plural = if (defaultCount == 1) "defaulter" else "defaulters"
                val monthsPart = if (totalMonths > 0) {
                    " · $totalMonths month${if (totalMonths == 1) "" else "s"}"
                } else ""
                val line = "$defaultCount $plural$monthsPart"
                defaulterPaint.textSize = fitToWidth(line, width - 2 * EDGE_INSET, defaulterPaint, max = 24f, min = 14f)
                canvas.drawText(line, width / 2f, lotY + 78f, defaulterPaint)
            }

            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date())
            val fileName = "LOT_${lotNumber}_$stamp.png"
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val file = File(downloadsDir, fileName)

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        } catch (e: Exception) {
            android.util.Log.e("LotImageGenerator", "image generation failed", e)
            null
        } finally {
            // P5γ LOW: recycle in finally so an exception during compress()
            // doesn't leak the bitmap. The Bitmap.createBitmap allocation
            // happens BEFORE try-block so the finally only runs when the
            // bitmap is guaranteed to exist.
            bitmap.recycle()
        }
    }

    /**
     * Walks text size down from [max] toward [min] until the rendered width
     * fits inside [maxWidth]. Keeps the banner readable on high-count LOTs
     * without ever clipping or wrapping.
     */
    private fun fitToWidth(
        text: String,
        maxWidth: Float,
        paint: Paint,
        max: Float,
        min: Float
    ): Float {
        var size = max
        while (size > min) {
            paint.textSize = size
            if (paint.measureText(text) <= maxWidth) return size
            size -= 1f
        }
        paint.textSize = min
        return min
    }

    fun getShareableUri(context: Context, file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file
    )
}
