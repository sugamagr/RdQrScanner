package com.qrscanner.app.util

import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

/**
 * Canonical year/month pair used wherever the app needs to talk about
 * 'which month was paid for'.
 *
 * Stored in [com.qrscanner.app.data.RdNumber.monthsList] as a
 * comma-separated list of `YYYY-MM` tokens. All format / parse / arithmetic
 * goes through this class so the wire format is owned in exactly one place.
 *
 * @property year 4-digit year, bounded to [MIN_YEAR]..[MAX_YEAR] at parse time.
 * @property month 1-based month (1 = January, 12 = December).
 */
data class MonthYear(val year: Int, val month: Int) : Comparable<MonthYear> {

    init {
        require(month in 1..12) { "month must be 1..12, got $month" }
        require(year in MIN_YEAR..MAX_YEAR) { "year must be $MIN_YEAR..$MAX_YEAR, got $year" }
    }

    fun toToken(): String = "%04d-%02d".format(year, month)

    /**
     * Returns the previous calendar month, rolling year back at January.
     */
    fun minusOneMonth(): MonthYear =
        if (month == 1) MonthYear(year - 1, 12) else MonthYear(year, month - 1)

    /**
     * Returns the next calendar month, rolling year forward at December.
     */
    fun plusOneMonth(): MonthYear =
        if (month == 12) MonthYear(year + 1, 1) else MonthYear(year, month + 1)

    /**
     * Localized short name, e.g. "Jun 2024" in en-US, "जून 2024" in hi-IN.
     * Uses the JVM's [DateFormatSymbols] which is populated for every locale
     * Android supports; falls back to "MM YYYY" only on the impossible empty
     * array case.
     */
    fun formatShort(locale: Locale = Locale.getDefault()): String {
        val names = DateFormatSymbols(locale).shortMonths
        val name = names.getOrNull(month - 1)?.takeIf { it.isNotBlank() }
            ?: "%02d".format(month)
        return "$name $year"
    }

    /**
     * Locale-stable English short form for machine-readable exports.
     */
    fun formatExport(): String = formatShort(Locale.ENGLISH)

    override fun compareTo(other: MonthYear): Int =
        compareValuesBy(this, other, { it.year }, { it.month })

    companion object {
        const val MIN_YEAR = 2000
        const val MAX_YEAR = 2099

        fun current(): MonthYear {
            val cal = Calendar.getInstance()
            return MonthYear(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
        }

        fun fromEpochMillis(millis: Long): MonthYear {
            val cal = Calendar.getInstance().apply { timeInMillis = millis }
            return MonthYear(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
        }

        /**
         * Parses a single YYYY-MM token. Returns null for any malformed input
         * (wrong shape, out-of-range fields) so callers can fall back without
         * crashing on corrupted DB values.
         */
        fun parseToken(token: String): MonthYear? {
            val trimmed = token.trim()
            if (trimmed.length != 7 || trimmed[4] != '-') return null
            val year = trimmed.substring(0, 4).toIntOrNull() ?: return null
            val month = trimmed.substring(5, 7).toIntOrNull() ?: return null
            if (year !in MIN_YEAR..MAX_YEAR || month !in 1..12) return null
            return MonthYear(year, month)
        }

        /**
         * Parses a comma-separated list. Returns null if the list is null,
         * empty, contains any malformed token, or has the wrong count for the
         * caller's [expectedCount]. Callers use null to mean 'fall back to
         * auto-derived window' so a partial parse never produces a half-broken
         * UI.
         */
        fun parseList(raw: String?, expectedCount: Int): List<MonthYear>? {
            if (raw.isNullOrBlank() || expectedCount <= 0) return null
            val tokens = raw.split(',')
            if (tokens.size != expectedCount) return null
            val parsed = tokens.map { parseToken(it) ?: return null }
            return parsed
        }

        fun encodeList(months: List<MonthYear>): String =
            months.joinToString(",") { it.toToken() }

        /**
         * Generates the default month window: the [count] most-recent months
         * ending at [endingAt], ordered most-recent-first. Clamps [count] to
         * [com.qrscanner.app.data.RdNumber.MONTHS_MIN]..[com.qrscanner.app.data.RdNumber.MONTHS_MAX].
         */
        fun autoWindow(count: Int, endingAt: MonthYear = current()): List<MonthYear> {
            val safeCount = count.coerceIn(1, 36)
            val result = ArrayList<MonthYear>(safeCount)
            var cursor = endingAt
            repeat(safeCount) {
                result.add(cursor)
                cursor = cursor.minusOneMonth()
            }
            return result
        }

        /**
         * Returns the stored list when valid; otherwise the auto-derived
         * window. Single render-side fallback used by every surface that
         * displays the list (dialog, detail screen, exports, share text).
         */
        fun resolveOrAuto(
            raw: String?,
            count: Int,
            endingAt: MonthYear = current()
        ): List<MonthYear> = parseList(raw, count) ?: autoWindow(count, endingAt)

        /**
         * Signed difference in months: positive when [other] is later
         * than [this], negative when earlier, zero when equal. Used by
         * the LOT review month bar to compute the index of an anchor
         * month inside the rendered range so the LazyRow can scroll
         * it into view on open.
         */
        fun monthsBetween(from: MonthYear, to: MonthYear): Int =
            (to.year - from.year) * 12 + (to.month - from.month)

        /**
         * Generates a contiguous inclusive range starting at [from] and
         * ending at [to], oldest-first. Caller is responsible for
         * ordering; this helper preserves natural calendar order so the
         * LOT review bar reads left-to-right as time-forward.
         * Returns empty when [to] is strictly earlier than [from].
         */
        fun range(from: MonthYear, to: MonthYear): List<MonthYear> {
            if (to < from) return emptyList()
            val span = monthsBetween(from, to) + 1
            val result = ArrayList<MonthYear>(span)
            var cursor = from
            repeat(span) {
                result.add(cursor)
                cursor = cursor.plusOneMonth()
            }
            return result
        }

        /**
         * Per-account status classifier. Single source of truth for the
         * R2 defaulter semantic locked with user: an account is
         * "current" iff [lastPaidThrough] EQUALS the current month
         * token exactly. Any other value (null, earlier, or later) is a
         * defaulter. Forward-paid accounts additionally carry positive
         * credit (months paid ahead) so surfaces like a per-account
         * badge can show 'Paid ahead N months' instead of hiding the
         * credit inside the defaulter bucket.
         *
         * Cross-file contract: the portal mirror lives in
         * portal/src/lib/dashboardQueries.ts computeAccountStatus.
         * Any change here must land there too — pre-R2 the two
         * surfaces drifted (portal used >=, phone used <) and the
         * user hit UX inconsistency between phone and portal defaulter
         * views. Do not re-introduce that drift.
         *
         * currentMonth defaults to today's local YYYY-MM (matches the
         * anchor MonthYear.current() uses) so callers don't have to
         * thread the current-month value through every call site.
         */
        fun computeAccountStatus(
            lastPaidThrough: String?,
            currentMonth: String = current().toToken()
        ): AccountStatusResult {
            if (lastPaidThrough == currentMonth) {
                return AccountStatusResult(AccountStatus.CURRENT, creditMonths = 0)
            }
            val credit = if (lastPaidThrough != null && lastPaidThrough > currentMonth) {
                monthDiffPositive(lastPaidThrough, currentMonth)
            } else 0
            return AccountStatusResult(AccountStatus.DEFAULTER, credit)
        }

        /**
         * Returns the count of months from [earlier] to [later] as a
         * non-negative Int. Both inputs are YYYY-MM tokens. Returns 0
         * when [later] <= [earlier] or when either input fails to
         * parse — same defense-in-depth stance as the portal mirror in
         * dashboardQueries.ts monthDiffPositive.
         */
        private fun monthDiffPositive(later: String, earlier: String): Int {
            val laterMy = parseToken(later) ?: return 0
            val earlierMy = parseToken(earlier) ?: return 0
            val diff = monthsBetween(earlierMy, laterMy)
            return if (diff > 0) diff else 0
        }
    }
}

enum class AccountStatus { CURRENT, DEFAULTER }

/**
 * Result shape for [MonthYear.computeAccountStatus]. Kept as a
 * top-level data class rather than nested so it can be imported and
 * destructured without pulling in the whole MonthYear namespace.
 * [creditMonths] is 0 for CURRENT and for DEFAULTER-underpaid; only
 * DEFAULTER-forward-paid carries a positive count.
 */
data class AccountStatusResult(
    val status: AccountStatus,
    val creditMonths: Int
)
