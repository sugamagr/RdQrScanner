package com.qrscanner.app.util

import android.content.Context
import android.os.Environment
import androidx.core.content.FileProvider
import com.qrscanner.app.data.RdNumber
import com.qrscanner.app.data.ScanLot
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object XlsxExporter {

    // P5γ MEDIUM: SimpleDateFormat is NOT thread-safe. The previous
    // shared static field could corrupt timestamp formatting if two
    // exports ran concurrently (rapid double-tap, parallel test, etc).
    // Wrap in ThreadLocal so each calling thread gets its own instance
    // — cheap, no allocation per call after first use, and the format
    // string is locked so a maintainer can't introduce a thread-leaky
    // alternative by accident.
    private val dateFormatThreadLocal = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }
    private val dateFormat: SimpleDateFormat
        get() = dateFormatThreadLocal.get()!!

    private val filenameDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }
    private fun todayStamp(): String = filenameDateFormat.get()!!.format(Date())

    // ── XLSX Export ──────────────────────────────────────────────────────────

    fun exportSessionToXlsx(
        context: Context,
        lots: List<ScanLot>,
        rdNumbersPerLot: List<List<RdNumber>>,
        sessionDisplayNumber: Int,
        amountsByRdNumber: Map<String, Int> = emptyMap()
    ): File? {
        if (lots.isEmpty()) return null
        return try {
            val fileName = "RD_Session_${sessionDisplayNumber}_${todayStamp()}.xlsx"
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val file = File(downloadsDir, fileName)

            // XLSX is a ZIP archive of OOXML parts — no external library needed
            FileOutputStream(file).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    writeEntry(zos, "[Content_Types].xml", contentTypesXml())
                    writeEntry(zos, "_rels/.rels", rootRelsXml())
                    writeEntry(zos, "xl/workbook.xml", workbookXml(sessionDisplayNumber))
                    writeEntry(zos, "xl/_rels/workbook.xml.rels", workbookRelsXml())
                    writeEntry(zos, "xl/styles.xml", stylesXml())
                    writeEntry(
                        zos,
                        "xl/worksheets/sheet1.xml",
                        sheet1Xml(lots, rdNumbersPerLot, amountsByRdNumber)
                    )
                }
            }
            file
        } catch (e: Exception) {
            android.util.Log.e("XlsxExporter", "xlsx export failed", e)
            null
        }
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun contentTypesXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private fun rootRelsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbookXml(sessionNumber: Int) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Session $sessionNumber" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>"""

    private fun workbookRelsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    // Style 0 = normal, Style 1 = bold (used for header row)
    private fun stylesXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font><sz val="11"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><name val="Calibri"/></font>
  </fonts>
  <fills count="2">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
  </fills>
  <borders count="1">
    <border><left/><right/><top/><bottom/><diagonal/></border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="2">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
  </cellXfs>
</styleSheet>"""

    private fun sheet1Xml(
        lots: List<ScanLot>,
        rdNumbersPerLot: List<List<RdNumber>>,
        amountsByRdNumber: Map<String, Int>
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<cols>")
        sb.append("<col min=\"2\" max=\"2\" width=\"40\" customWidth=\"1\"/>")
        sb.append("<col min=\"5\" max=\"5\" width=\"30\" customWidth=\"1\"/>")
        // P3 CROSS-FILE: column F now carries every RD number's month
        // token(s) not just defaulters (see F cell writer below), so the
        // width doubled from 50 to 80. Keeping the value in sync with the
        // cell content is a soft invariant a linter can't catch — if F
        // ever shrinks back to defaulters-only the width should drop too.
        sb.append("<col min=\"6\" max=\"6\" width=\"80\" customWidth=\"1\"/>")
        sb.append("<col min=\"7\" max=\"7\" width=\"22\" customWidth=\"1\"/>")
        // P3 CROSS-FILE: column H width chosen to fit ~40 chars of
        // "<rd>: ₹<amount>" pairs joined by "; ". At the ~4-8 rows/lot
        // typical size the cell fits comfortably; wider than E because
        // the ₹ prefix and thousands separators add characters per RD
        // vs the compact "<rd>: Nm" defaulter shape.
        sb.append("<col min=\"8\" max=\"8\" width=\"50\" customWidth=\"1\"/>")
        sb.append("</cols>")
        sb.append("<sheetData>")

        sb.append("<row r=\"1\">")
        sb.append(strCell("A1", "LOT #", bold = true))
        sb.append(strCell("B1", "RD Numbers", bold = true))
        sb.append(strCell("C1", "Count", bold = true))
        sb.append(strCell("D1", "Default Count", bold = true))
        sb.append(strCell("E1", "Defaulters", bold = true))
        sb.append(strCell("F1", "All Months", bold = true))
        sb.append(strCell("G1", "Timestamp", bold = true))
        sb.append(strCell("H1", "Amounts", bold = true))
        sb.append("</row>")

        lots.forEachIndexed { index, lot ->
            val rows = rdNumbersPerLot.getOrElse(index) { emptyList() }
            val defaulters = rows.filter { it.monthsPaid > 1 }
            val anchor = MonthYear.fromEpochMillis(lot.timestamp)
            val rowNum = index + 2
            sb.append("<row r=\"$rowNum\">")
            sb.append(numCell("A$rowNum", lot.lotNumber))
            sb.append(strCell("B$rowNum", rows.joinToString(", ") { it.number }))
            sb.append(numCell("C$rowNum", rows.size))
            sb.append(numCell("D$rowNum", defaulters.size))
            sb.append(strCell("E$rowNum", defaulters.joinToString("; ") { "${it.number}: ${it.monthsPaid}m" }))
            // P3 SEMANTIC: column F now enumerates EVERY row in the lot
            // with its resolved month token(s), not just defaulters.
            // resolveOrAuto returns [anchor] for the single-month case
            // (monthsPaid == 1 || monthsList empty), so a non-defaulter
            // renders as "<rd>: Jun-2026" — the same shape as a
            // defaulter's multi-month token list. Column E still
            // isolates defaulters for quick scanning; keeping both
            // preserves the pre-existing report shape used by whoever
            // has been consuming the exports.
            sb.append(strCell("F$rowNum", rows.joinToString("; ") { rd ->
                val months = MonthYear.resolveOrAuto(rd.monthsList, rd.monthsPaid, anchor)
                "${rd.number}: " + months.joinToString(", ") { it.formatExport() }
            }))
            sb.append(strCell("G$rowNum", dateFormat.format(Date(lot.timestamp))))
            // P3 CROSS-FILE: column H is the caller-supplied amounts
            // map. Missing keys fall through to 0 defensively but the
            // scanner NOW enforces "account exists before scan" via
            // RegisterAccountDialog (RDScannerScreen), so a real 0
            // means either (a) the account was hard-deleted between
            // scan and export, or (b) manual DB tampering — never
            // steady-state. Format is "<rd>: ₹<amount>" mirroring the
            // per-RD shape of columns E and F.
            sb.append(strCell("H$rowNum", rows.joinToString("; ") { rd ->
                val amt = amountsByRdNumber[rd.number] ?: 0
                "${rd.number}: \u20B9$amt"
            }))
            sb.append("</row>")
        }

        sb.append("</sheetData>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    /** Inline string cell — handles XML special characters */
    private fun strCell(ref: String, value: String, bold: Boolean = false): String {
        val escaped = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
        val style = if (bold) " s=\"1\"" else ""
        return "<c r=\"$ref\" t=\"inlineStr\"$style><is><t>$escaped</t></is></c>"
    }

    /** Numeric cell (no type attr = number in OOXML) */
    private fun numCell(ref: String, value: Int): String =
        "<c r=\"$ref\"><v>$value</v></c>"

    // ── TXT Export ───────────────────────────────────────────────────────────

    fun exportSessionToTxt(
        context: Context,
        lots: List<ScanLot>,
        rdNumbersPerLot: List<List<RdNumber>>,
        sessionDisplayNumber: Int,
        amountsByRdNumber: Map<String, Int> = emptyMap()
    ): File? {
        if (lots.isEmpty()) return null

        return try {
            val fileName = "RD_Session_${sessionDisplayNumber}_${todayStamp()}.txt"
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val file = File(downloadsDir, fileName)

            FileWriter(file).use { writer ->
                writer.append("RD Book Scanner - Session #$sessionDisplayNumber\n")
                writer.append("=".repeat(40) + "\n\n")

                var totalDefaulters = 0
                var totalMonths = 0
                var totalBookValue = 0
                lots.forEachIndexed { index, lot ->
                    val rows = rdNumbersPerLot.getOrElse(index) { emptyList() }
                    val defaulters = rows.filter { it.monthsPaid > 1 }
                    val anchor = MonthYear.fromEpochMillis(lot.timestamp)
                    totalDefaulters += defaulters.size
                    totalMonths += defaulters.sumOf { it.monthsPaid }
                    val lotBookValue = rows.sumOf { amountsByRdNumber[it.number] ?: 0 }
                    totalBookValue += lotBookValue

                    val header = if (defaulters.isEmpty()) {
                        "LOT ${lot.lotNumber} (${rows.size} numbers, \u20B9$lotBookValue book value):"
                    } else {
                        "LOT ${lot.lotNumber} (${rows.size} numbers, ${defaulters.size} defaulter${if (defaulters.size == 1) "" else "s"}, \u20B9$lotBookValue book value):"
                    }
                    writer.append("$header\n")
                    writer.append(rows.joinToString(", ") { it.number })
                    writer.append("\n")
                    if (defaulters.isNotEmpty()) {
                        writer.append("Defaulters: ")
                        writer.append(defaulters.joinToString(", ") { rd ->
                            val months = MonthYear.resolveOrAuto(rd.monthsList, rd.monthsPaid, anchor)
                            "${rd.number} (" + months.joinToString(", ") { it.formatExport() } + ")"
                        })
                        writer.append("\n")
                    }
                    // P3 CROSS-FILE: amounts line mirrors xlsx column H
                    // so pairing xlsx + txt outputs from the same session
                    // gives byte-identical amount data. Missing keys fall
                    // through to 0 defensively but the scanner NOW
                    // enforces "account exists before scan" via
                    // RegisterAccountDialog — see xlsx column H writer.
                    writer.append("Amounts: ")
                    writer.append(rows.joinToString(", ") { rd ->
                        val amt = amountsByRdNumber[rd.number] ?: 0
                        "${rd.number} (\u20B9$amt)"
                    })
                    writer.append("\n\n")
                }

                val totalNumbers = rdNumbersPerLot.sumOf { it.size }
                writer.append("-".repeat(40) + "\n")
                writer.append("Total: ${lots.size} LOTs, $totalNumbers RD Numbers")
                if (totalDefaulters > 0) writer.append(", $totalDefaulters defaulters ($totalMonths months)")
                writer.append(", \u20B9$totalBookValue book value")
                writer.append("\n")
            }

            file
        } catch (e: Exception) {
            android.util.Log.e("XlsxExporter", "txt export failed", e)
            null
        }
    }

    fun exportLotToString(numbers: List<String>): String = numbers.joinToString(", ")

    fun getShareableUri(context: Context, file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file
    )
}
