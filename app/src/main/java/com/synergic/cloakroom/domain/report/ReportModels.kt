package com.synergic.cloakroom.domain.report

import com.synergic.cloakroom.domain.billing.ZERO_MONEY
import com.synergic.cloakroom.domain.billing.money
import com.synergic.cloakroom.domain.settings.PayMode
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Report period (local dates, both included), user and shift.
 * [userId] null = all users (admin only); [shiftId] null = all shifts.
 */
data class ReportFilter(val from: LocalDate, val to: LocalDate, val userId: Long?, val shiftId: Long? = null)

/** A shift in the report filter. [removed] = deleted in Masters but may still be in old data. */
data class ReportShift(val id: Long, val name: String, val fromTime: String, val toTime: String, val removed: Boolean) {
    val label: String get() = "$name ($fromTime–$toTime)" + if (removed) " · removed" else ""
}

/** A user in the admin's report filter. */
data class ReportUser(val id: Long, val userCode: String, val userName: String)

/** One check-in, counted for the user who checked it in (user_id_in). */
data class CheckinRow(
    val transId: String,
    val receiptNo: String,
    /** UTC db text. */
    val dateTimeIn: String,
    val userCode: String,
    val name: String,
    val mobile: String,
    val pnr: String,
    val bags: Int,
    val isOut: Boolean,
    /** Extra bags stored free of charge (not billed). */
    val extraBags: Int = 0,
    /** Shift name at check-in, empty when none. */
    val shift: String = "",
    /** Advance taken in cash at check-in; 0 when none. */
    val advAmt: BigDecimal = ZERO_MONEY,
)

/** One bill, counted for the user who made it (td_receipt.user_id, i.e. user_id_out). */
data class BillRow(
    val transId: String,
    val billNo: String,
    val billDt: String,
    val receiptNo: String,
    /** UTC db text. */
    val dateTimeOut: String,
    val userCode: String,
    val name: String,
    val bags: Int,
    /** Extra bags stored free of charge (not billed). */
    val extraBags: Int = 0,
    val baseAmt: BigDecimal,
    val penaltyAmt: BigDecimal,
    val taxableAmt: BigDecimal,
    val cgst: BigDecimal,
    val sgst: BigDecimal,
    val igst: BigDecimal,
    /** The full bill. */
    val netAmt: BigDecimal,
    val payMode: PayMode,
    val printed: Boolean,
    /** Shift name at billing, empty when none. */
    val shift: String = "",
    /** Advance subtracted on this bill (paid at check-in). */
    val advAmt: BigDecimal = ZERO_MONEY,
    /** Collected at check-out: max(0, net − advance). */
    val balAmt: BigDecimal = netAmt,
)

/** Totals of a group of bills (one pay mode, or all). */
data class CollectionLine(
    /** Null for the grand total. */
    val payMode: PayMode?,
    val bills: Int,
    val baseAmt: BigDecimal,
    val penaltyAmt: BigDecimal,
    val taxableAmt: BigDecimal,
    val cgst: BigDecimal,
    val sgst: BigDecimal,
    val igst: BigDecimal,
    /** Full bills. */
    val netAmt: BigDecimal,
    /** Advance subtracted on these bills (it was collected at check-in). */
    val advAmt: BigDecimal = ZERO_MONEY,
    /** Collected at check-out on these bills. */
    val balAmt: BigDecimal = netAmt,
) {
    companion object {
        /** Sums with BigDecimal, so totals are exact to the paisa. */
        fun of(payMode: PayMode?, rows: List<BillRow>) = CollectionLine(
            payMode = payMode,
            bills = rows.size,
            baseAmt = rows.sumMoney { it.baseAmt },
            penaltyAmt = rows.sumMoney { it.penaltyAmt },
            taxableAmt = rows.sumMoney { it.taxableAmt },
            cgst = rows.sumMoney { it.cgst },
            sgst = rows.sumMoney { it.sgst },
            igst = rows.sumMoney { it.igst },
            netAmt = rows.sumMoney { it.netAmt },
            advAmt = rows.sumMoney { it.advAmt },
            balAmt = rows.sumMoney { it.balAmt },
        )

        private fun List<BillRow>.sumMoney(pick: (BillRow) -> BigDecimal): BigDecimal =
            fold(ZERO_MONEY) { acc, row -> acc + pick(row) }.money()
    }
}

/**
 * A report. [byPayMode] (cash first, then UPI; modes without bills left out) and [total] are summed
 * from [bills] on the phone; the server report passes its own totals instead (its bill rows carry
 * only the net amount).
 */
data class ReportData(
    val filter: ReportFilter,
    val checkins: List<CheckinRow>,
    val bills: List<BillRow>,
    val byPayMode: List<CollectionLine> =
        PayMode.entries.map { mode -> CollectionLine.of(mode, bills.filter { it.payMode == mode }) }.filter { it.bills > 0 },
    val total: CollectionLine = CollectionLine.of(null, bills),
    /** Advance taken in cash at the check-ins of the period (counted on the check-in date). */
    val advanceCollected: BigDecimal = checkins.fold(ZERO_MONEY) { acc, c -> acc + c.advAmt }.money(),
) {

    /** Money taken in the period: balances at check-out + advances at check-in. */
    val totalCollected: BigDecimal get() = (total.balAmt + advanceCollected).money()

    val bagsCheckedIn: Int get() = checkins.sumOf { it.bags }

    /** Free extra bags of the check-ins (not billed). */
    val extraBagsCheckedIn: Int get() = checkins.sumOf { it.extraBags }
}

/**
 * Dashboard numbers. "Today" is the local date. [allUsers] = admin view; otherwise the counts are
 * the logged-in user's own. [todayCollection] is null when TOTAL_COLLECTION_FLAG = N.
 */
data class DashboardData(
    val receiptsInStore: Int,
    val bagsInStore: Int,
    val todayCheckins: Int,
    val todayBills: Int,
    val todayCollection: BigDecimal?,
    val allUsers: Boolean,
)

/**
 * A row the server could not take as sent (API_GUIDE.md §6.2, exceptions report). [ref] is the
 * receipt or bill number from the row; [createdAt] UTC db text.
 */
data class SyncExceptionRow(
    val id: Long,
    val transId: String,
    val isBill: Boolean,
    val ref: String,
    val reason: String,
    val userName: String,
    val createdAt: String,
    val resolved: Boolean,
)

/** Whether the logged-in user may open Reports. [showExceptions]: the server's sync exceptions tab. */
sealed interface ReportAccess {
    data class Allowed(val needsPassword: Boolean, val canChooseUser: Boolean, val showExceptions: Boolean = false) : ReportAccess
    data object Denied : ReportAccess
}
