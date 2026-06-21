package com.qrscanner.app.data

/**
 * Where an [RdAccount] was created.
 *
 * Drives the editability gate on the phone — CSV-imported accounts are
 * locked to phone-side edits ("contact Sugam" snackbar) because the
 * portal CSV upload is treated as the canonical record. MANUAL accounts
 * are operator-entered from the phone and freely editable on both
 * surfaces.
 *
 * Stored as TEXT (Room enum support); on cloud it's a CHECK-constrained
 * text column with the same two values. Adding a new value here requires
 * a Room migration AND a cloud schema patch (CHECK constraint).
 */
enum class AccountSource {
    /** Operator-entered via the AddAccountsScreen spreadsheet. Editable everywhere. */
    MANUAL,

    /** Bulk-imported via portal CSV upload. Phone shows a Lock icon + "contact Sugam" snackbar on edit attempt. Portal can edit. */
    CSV
}
