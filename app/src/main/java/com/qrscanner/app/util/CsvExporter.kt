package com.qrscanner.app.util

import android.content.Context
import android.os.Environment
import androidx.core.content.FileProvider
import com.qrscanner.app.data.ScanLot
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CsvExporter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // ── XLSX Export ──────────────────────────────────────────────────────────

    fun exportSessionToXlsx(
        context: Context,
        lots: List<ScanLot>,
        rdNumbersPerLot: List<List<String>>,
        sessionDisplayNumber: Int
    ): File? {
        if (lots.isEmpty()) return null
        return try {
            val fileName = "RD_Session_${sessionDisplayNumber}_${System.currentTimeMillis()}.xlsx"
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
                    writeEntry(zos, "xl/worksheets/sheet1.xml", sheet1Xml(lots, rdNumbersPerLot))
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
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

    private fun sheet1Xml(lots: List<ScanLot>, rdNumbersPerLot: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<sheetData>")

        // Header row (bold)
        sb.append("<row r=\"1\">")
        sb.append(strCell("A1", "LOT #", bold = true))
        sb.append(strCell("B1", "RD Numbers", bold = true))
        sb.append(strCell("C1", "Count", bold = true))
        sb.append(strCell("D1", "Timestamp", bold = true))
        sb.append("</row>")

        // One row per LOT
        lots.forEachIndexed { index, lot ->
            val numbers = rdNumbersPerLot.getOrElse(index) { emptyList() }
            val rowNum = index + 2  // 1-based, after header
            sb.append("<row r=\"$rowNum\">")
            sb.append(numCell("A$rowNum", lot.lotNumber))
            sb.append(strCell("B$rowNum", numbers.joinToString(", ")))
            sb.append(numCell("C$rowNum", numbers.size))
            sb.append(strCell("D$rowNum", dateFormat.format(Date(lot.timestamp))))
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
        rdNumbersPerLot: List<List<String>>,
        sessionDisplayNumber: Int
    ): File? {
        if (lots.isEmpty()) return null

        return try {
            val fileName = "RD_Session_${sessionDisplayNumber}_${System.currentTimeMillis()}.txt"
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val file = File(downloadsDir, fileName)

            FileWriter(file).use { writer ->
                writer.append("RD Book Scanner - Session #$sessionDisplayNumber\n")
                writer.append("=".repeat(40) + "\n\n")

                lots.forEachIndexed { index, lot ->
                    val numbers = rdNumbersPerLot.getOrElse(index) { emptyList() }
                    writer.append("LOT ${lot.lotNumber} (${numbers.size} numbers):\n")
                    writer.append(numbers.joinToString(", "))
                    writer.append("\n\n")
                }

                val totalNumbers = rdNumbersPerLot.sumOf { it.size }
                writer.append("-".repeat(40) + "\n")
                writer.append("Total: ${lots.size} LOTs, $totalNumbers RD Numbers\n")
            }

            file
        } catch (e: Exception) {
            e.printStackTrace()
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
