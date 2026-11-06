package com.synergic.cloakroom.domain.numbering

import com.synergic.cloakroom.domain.settings.ReceiptReset
import java.time.LocalDate
import java.time.Month

/** R = receipt (check-in) series, B = bill (check-out) series. */
enum class CounterType(val code: String) { RECEIPT("R"), BILL("B") }

/**
 * Receipt and bill number format: `<user_code>-<5-digit number>`, e.g. U02-00001.
 * Each user has its own series per [CounterType], restarting per RECEIPT_RESET period.
 * The counter itself is kept in md_user_counter by the data layer.
 */
object NumberGenerator {

    /** 2026-11-02 (daily), 2026-11 (monthly), FY2026-27 (April–March), ALL (never). */
    fun periodKey(reset: ReceiptReset, localDate: LocalDate): String = when (reset) {
        ReceiptReset.DAILY -> localDate.toString()
        ReceiptReset.MONTHLY -> "%04d-%02d".format(localDate.year, localDate.monthValue)
        ReceiptReset.FINANCIAL_YEAR -> {
            val start = if (localDate.month >= Month.APRIL) localDate.year else localDate.year - 1
            "FY%04d-%02d".format(start, (start + 1) % 100)
        }
        ReceiptReset.NEVER -> "ALL"
    }

    /** Numbers above 99999 simply get more digits. */
    fun format(userCode: String, number: Int): String {
        require(number >= 1) { "Numbers start at 1" }
        return "%s-%05d".format(userCode, number)
    }
}
