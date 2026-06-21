package com.qrscanner.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.qrscanner.app.data.RdAccount
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single source of truth for ALL QR PDF generation across the app.
 *
 * Two callers:
 *   * AddAccountsScreen "Save & Generate QR" path — feeds the freshly
 *     persisted RdAccount rows
 *   * AccountsScreen per-row "Generate QR" button + Bulk QR mode —
 *     feeds 1..N selected rows
 *
 * Both produce a PDF of identical structure: each QR is exactly
 * [QR_SIZE_PT] = 360 pt (~5cm at 72 DPI) regardless of batch size.
 * The fixed size is an invariant defended HERE, not in the callers
 * (so a future caller can't accidentally request smaller QRs).
 *
     * Layout: A4 portrait (595 × 842 pt), 1×2 grid (2 QRs per page).
     * 2×2 would require 720pt of width (2×360pt) which exceeds the
     * 595pt A4 page — the original design comment said 2×2 but the
     * math never worked and the implementation silently clipped the
     * right-column QRs. Switching to 1 column × 2 rows preserves the
     * 360pt invariant for thermal-printer legibility at the cost of
     * half the per-page density. Caption block: name (bold) + RD
     * number (mono) + ₹monthlyAmount beneath each QR.
 *
 * QR payload: the rd_number string ONLY. Scanners read just that;
 * caption metadata (name, amount) lives in the printable region and
 * the RdAccount lookup post-scan supplies it. Decision per user spec.
 *
 * ZXing config matches the existing single-QR generation pipeline:
 *   * QRCodeWriter with EncodeHintType.MARGIN = 1 (minimal quiet zone)
 *   * ErrorCorrectionLevel.M (balanced — survives moderate print
 *     damage, dense enough for 360pt at typical printer DPI)
 *   * Bitmap.Config.RGB_565 (compact, lossless for monochrome QR)
 *
 * Output: written to context.cacheDir/qr-exports/{filename}, returned
 * as a content:// Uri via the project's existing FileProvider authority
 * (${applicationId}.provider, paths declared in res/xml/file_paths.xml).
 */
object QrPdfExporter {

    /** A4 portrait dimensions in PostScript points. */
    private const val A4_WIDTH_PT = 595
    private const val A4_HEIGHT_PT = 842

    /**
     * Fixed QR side length in points. 360 pt = 5 inches / ~12.7 cm — large
     * enough that thermal-printer-grade printouts still scan, regardless
     * of batch size. Invariant defended here; callers MUST NOT override.
     */
    const val QR_SIZE_PT = 360

    // 1 column × 2 rows per page. 1 col is forced because GRID_COLS=2
    // overflows A4 portrait width (see class KDoc). If a future change
    // shrinks QR_SIZE_PT below ~270pt, GRID_COLS can return to 2.
    private const val GRID_COLS = 1
    private const val GRID_ROWS = 2
    private const val ITEMS_PER_PAGE = GRID_COLS * GRID_ROWS

    private const val CAPTION_LINE_HEIGHT_PT = 14
    private const val CAPTION_LINES = 3
    private const val CAPTION_BLOCK_PT = CAPTION_LINES * CAPTION_LINE_HEIGHT_PT + 6

    private const val CELL_HEIGHT_PT = QR_SIZE_PT + CAPTION_BLOCK_PT

    private val MARGIN_X_PT = (A4_WIDTH_PT - GRID_COLS * QR_SIZE_PT) / (GRID_COLS + 1)
    private val MARGIN_Y_PT = (A4_HEIGHT_PT - GRID_ROWS * CELL_HEIGHT_PT) / (GRID_ROWS + 1)

    /**
     * Builds the PDF and returns a shareable content:// Uri, or null
     * when [accounts] is empty (callers MUST gate on this).
     *
     * Stable iteration order: accounts are placed left-to-right then
     * top-to-bottom in input order — keep the input sorted by whatever
     * the caller wants the operator to see in the printed pack.
     */
    fun generate(
        context: Context,
        accounts: List<RdAccount>,
        filename: String = defaultFilename()
    ): android.net.Uri? {
        if (accounts.isEmpty()) return null

        val nameBoldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val rdMonoPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 11f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val amountPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val pdf = PdfDocument()
        try {
            val totalPages = (accounts.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE
            for (pageIndex in 0 until totalPages) {
                val pageInfo = PdfDocument.PageInfo
                    .Builder(A4_WIDTH_PT, A4_HEIGHT_PT, pageIndex + 1)
                    .create()
                val page = pdf.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)

                val pageStart = pageIndex * ITEMS_PER_PAGE
                val pageEnd = minOf(pageStart + ITEMS_PER_PAGE, accounts.size)
                for (i in pageStart until pageEnd) {
                    val cell = i - pageStart
                    val col = cell % GRID_COLS
                    val row = cell / GRID_COLS
                    val x = MARGIN_X_PT + col * (QR_SIZE_PT + MARGIN_X_PT)
                    val y = MARGIN_Y_PT + row * (CELL_HEIGHT_PT + MARGIN_Y_PT)

                    val account = accounts[i]
                    val qr = renderQr(account.rdNumber, QR_SIZE_PT)
                    if (qr != null) {
                        try {
                            canvas.drawBitmap(qr, x.toFloat(), y.toFloat(), null)
                        } finally {
                            qr.recycle()
                        }
                    }

                    val centerX = x + QR_SIZE_PT / 2f
                    val captionTop = y + QR_SIZE_PT + CAPTION_LINE_HEIGHT_PT
                    canvas.drawText(
                        ellipsize(account.name, 28),
                        centerX,
                        captionTop.toFloat(),
                        nameBoldPaint
                    )
                    canvas.drawText(
                        account.rdNumber,
                        centerX,
                        (captionTop + CAPTION_LINE_HEIGHT_PT).toFloat(),
                        rdMonoPaint
                    )
                    canvas.drawText(
                        formatAmount(account.monthlyAmount),
                        centerX,
                        (captionTop + 2 * CAPTION_LINE_HEIGHT_PT).toFloat(),
                        amountPaint
                    )
                }
                pdf.finishPage(page)
            }

            val outDir = File(context.cacheDir, "qr-exports").apply { mkdirs() }
            val outFile = File(outDir, filename)
            FileOutputStream(outFile).use { pdf.writeTo(it) }
            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                outFile
            )
        } catch (e: Exception) {
            android.util.Log.e("QrPdfExporter", "PDF generation failed", e)
            return null
        } finally {
            pdf.close()
        }
    }

    private fun renderQr(content: String, sizePt: Int): Bitmap? = try {
        val writer = QRCodeWriter()
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val matrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePt, sizePt, hints)
        // Build a row buffer of int pixels and write whole rows at a time
        // via setPixels() instead of one setPixel() JNI call per pixel.
        // For 360x360 = 129,600 pixels this is ~10x faster than the prior
        // nested setPixel() loop and removes a perceptible UI freeze when
        // generating bulk QR PDFs.
        val pixels = IntArray(sizePt)
        Bitmap.createBitmap(sizePt, sizePt, Bitmap.Config.RGB_565).also { bmp ->
            for (py in 0 until sizePt) {
                for (px in 0 until sizePt) {
                    pixels[px] = if (matrix[px, py]) Color.BLACK else Color.WHITE
                }
                bmp.setPixels(pixels, 0, sizePt, 0, py, sizePt, 1)
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("QrPdfExporter", "QR encode failed for content=$content", e)
        null
    }

    // ₹ matches the locked phone-side currency presentation in the
    // RD Accounts spec and AccountsScreen UI. "INR" was the pre-spec
    // fallback when font support for the rupee glyph was uncertain;
    // modern Android default Roboto ships it.
    private fun formatAmount(rupees: Int): String = "₹$rupees / month"

    // Caption truncation with visible ellipsis so the operator notices
    // a long name was clipped. Cap at 25 + 3-char ellipsis = 28 total
    // to stay within the cell's horizontal budget.
    private fun ellipsize(text: String, maxLen: Int): String =
        if (text.length <= maxLen) text else text.take(maxLen - 3) + "..."

    // Human-readable filename so the user can identify exports by date
    // when sharing or browsing the share-sheet recents. Local time so
    // the operator's mental model matches what they just printed.
    private fun defaultFilename(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date())
        return "rd-qr-$stamp.pdf"
    }
}
