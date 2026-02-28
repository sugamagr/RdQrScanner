package com.qrscanner.app.util

import android.content.Context
import android.os.Environment
import androidx.core.content.FileProvider
import com.qrscanner.app.data.ScanLot
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun exportSessionToCsv(
        context: Context,
        lots: List<ScanLot>,
        rdNumbersPerLot: List<List<String>>,
        sessionDisplayNumber: Int
    ): File? {
        if (lots.isEmpty()) return null

        return try {
            val fileName = "RD_Session_${sessionDisplayNumber}_${System.currentTimeMillis()}.csv"
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val file = File(downloadsDir, fileName)

            FileWriter(file).use { writer ->
                writer.append("LOT,RD Numbers,Count,Timestamp\n")

                lots.forEachIndexed { index, lot ->
                    val numbers = rdNumbersPerLot.getOrElse(index) { emptyList() }
                    val rdNumbersStr = numbers.joinToString(", ")
                    val timestamp = dateFormat.format(Date(lot.timestamp))
                    writer.append("${lot.lotNumber},\"$rdNumbersStr\",${numbers.size},$timestamp\n")
                }
            }

            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

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

    fun exportLotToString(numbers: List<String>): String {
        return numbers.joinToString(", ")
    }

    fun getShareableUri(context: Context, file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file
    )
}
