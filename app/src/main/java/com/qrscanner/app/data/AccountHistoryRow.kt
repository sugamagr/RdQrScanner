package com.qrscanner.app.data

/**
 * Flat projection over the rd_numbers -> scan_lots -> scan_sessions
 * join for AccountHistoryScreen. Room binds each SELECT column to the
 * matching field by name, so the SQL column aliases in
 * RdNumberDao.observeHistoryForRdNumber MUST match these field names
 * exactly. Changing either side without the other silently produces
 * "field not initialized" IllegalStateException at query time on the
 * first row that has a null the field can't hold.
 */
data class AccountHistoryRow(
    val rdNumberId: Long,
    val monthsPaid: Int,
    val monthsList: String?,
    val scannedAt: Long,
    val lotId: Long,
    val lotNumber: Int,
    val lotTimestamp: Long,
    val sessionId: Long,
    val sessionNumber: Int,
    val sessionStart: Long,
    val sessionEnd: Long?,
    val operatorName: String?
)
