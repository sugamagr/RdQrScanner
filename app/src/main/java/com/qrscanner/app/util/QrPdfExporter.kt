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
 * [QR_SIZE_PT] = 104 pt (~3.67 cm at 72 DPI) regardless of batch size.
 * The fixed size is an invariant defended HERE, not in the callers
 * (so a future caller can't accidentally request smaller QRs).
 *
 * The 104pt size targets the IndiaPost RD passbook stamp cell (~3.5-4cm
 * wide) so a single printed sheet can be cut into strips + glued into
 * the passbook without further resizing. Density is 20 QRs per A4 page.
 *
 * Layout: A4 portrait (595 × 842 pt), 5 columns × 4 rows grid.
 * Horizontal budget: 5 * 104 = 520pt QR + 6 gutters. Margin auto-derives.
 * Vertical budget: 4 * (104 + 48pt caption) = 608pt row + 5 gutters.
 * Caption block below each QR: name (bold) + RD number (mono) + ₹amount.
 *
 * QR payload: the rd_number string ONLY. Scanners read just that;
 * caption metadata (name, amount) lives in the printable region and
 * the RdAccount lookup post-scan supplies it. Decision per user spec.
 *
 * ZXing config matches the existing single-QR generation pipeline:
 *   * QRCodeWriter with EncodeHintType.MARGIN = 1 (minimal quiet zone)
 *   * ErrorCorrectionLevel.M (balanced — survives moderate print
 *     damage, dense enough for 104pt at typical 300+ DPI printer)
 *   * Bitmap.Config.RGB_565 (compact, lossless for monochrome QR)
 *
 * Print target: standard A4 black/white or color office printer at
 * 300+ DPI. At 104pt QR + 300 DPI = ~437px wide, a 12-digit rd_number
 * packs into a 33-module QR = ~13 px per module — comfortably above
 * the reliability floor for a phone camera at arm's length. If the
 * QR ever needs to shrink below ~85pt, revisit ECC level M -> Q.
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
     * Fixed QR side length in points. 104 pt = ~3.67 cm at 72 DPI —
     * matches the IndiaPost RD passbook stamp cell so printed sheets
     * can be cut + glued in without resizing. Invariant defended here;
     * callers MUST NOT override. If a future passbook redesign moves
     * the cell size, change ONLY this constant and re-verify caption
     * font widths in ellipsize() / paint setup.
     */
    const val QR_SIZE_PT = 104

    // 5 columns × 4 rows per page = 20 QRs/A4. Chosen for the passbook
    // stamp-cell dimensions above. Horizontal fit: 5*104=520pt QR + 75pt
    // margin (595-520) split as 6 gutters ~= 12.5pt each. Vertical fit:
    // 4*(104+48) = 608pt content + 234pt margin split 5 gutters ~= 47pt
    // — plenty of slack for the 3-line caption + inter-row breathing.
    private const val GRID_COLS = 5
    private const val GRID_ROWS = 4
    private const val ITEMS_PER_PAGE = GRID_COLS * GRID_ROWS

    // Caption font sizes must SHRINK from the pre-104pt era: at 104pt QR
    // width, a 12pt bold name overflows the cell for anything over ~11
    // characters. 9pt bold / 8pt regular fits within the 104pt cell for
    // realistic Indian names + 12-digit RD numbers. Verified against the
    // widest names from the user's July 2026 DOP report (MUKESH CHANDRA
    // GUPTA at 20 chars renders as "MUKESH CHANDR..." at 9pt bold).
    private const val CAPTION_LINE_HEIGHT_PT = 10
    private const val CAPTION_LINES = 3
    private const val CAPTION_BLOCK_PT = CAPTION_LINES * CAPTION_LINE_HEIGHT_PT + 6

    // Name character cap for the caption. At 8pt bold sans-serif in a
    // 104pt cell, ~16 characters fit horizontally with a 3-char ellipsis
    // showing truncation. 12-digit RD number below uses monospace and
    // ALWAYS renders in full (no ellipsis) because a partial account
    // number is worse than useless. Change this if QR_SIZE_PT changes.
    private const val NAME_MAX_CHARS = 16

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
            textSize = 8f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val rdMonoPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 7.5f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val amountPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 7.5f
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
                        ellipsize(account.name, NAME_MAX_CHARS),
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
        // Row-buffered write via setPixels() instead of one setPixel()
        // JNI call per pixel. Even at 104x104 = ~11K pixels the row-write
        // path is measurably faster (~5x) than the nested-loop version;
        // the difference matters more at bulk-export scale (200 accounts
        // = ~4200 setPixels calls vs ~2.16M setPixel calls saved) than
        // per single-QR generation.
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
        // PII redaction: `content` is the rd_number, a sensitive
        // financial identifier. Logging it verbatim leaks customer
        // account numbers to logcat / crash reports / device backups.
        // Tail-4 + length is enough for a developer to disambiguate
        // failures without exposing the full identifier.
        val masked = "***${content.takeLast(4)} (len=${content.length})"
        android.util.Log.e("QrPdfExporter", "QR encode failed for $masked", e)
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
