package com.synergic.cloakroom.domain.settings

import com.synergic.cloakroom.domain.auth.UserTypes
import java.math.BigDecimal

/** Names of the md_setting rows the app reads. */
object SettingKeys {
    /** Settings only the Synergic super admin may change; a station admin sees them read-only. */
    val SUPER_ADMIN_ONLY: Set<String> get() = setOf(SINGLE_SESSION_FLAG)

    /**
     * Print tab settings the Admin / Super Admin set on the server (MySQL md_setting). Sync copies
     * them into this phone's customer-wide row; counter users only see them and have no own value.
     */
    val ADMIN_SET_PRINT: Set<String> get() = setOf(QR_FLAG)

    // Billing (B)
    const val FIRST_BLOCK_RATE = "FIRST_BLOCK_RATE"
    const val NEXT_BLOCK_RATE = "NEXT_BLOCK_RATE"
    const val BLOCK_HOURS = "BLOCK_HOURS"
    const val GRACE_MINUTES = "GRACE_MINUTES"
    const val GST_FLAG = "GST_FLAG"
    const val GST_PCT = "GST_PCT"
    const val GST_TYPE = "GST_TYPE"
    const val GST_INCLUSIVE = "GST_INCLUSIVE"
    const val PENALTY_FLAG = "PENALTY_FLAG"
    const val PENALTY_MAX_AMT = "PENALTY_MAX_AMT"
    const val PENALTY_GST_FLAG = "PENALTY_GST_FLAG"
    const val DEFAULT_PAY_MODE = "DEFAULT_PAY_MODE"
    const val UPI_ENABLED = "UPI_ENABLED"
    const val OUT_WITHOUT_PRINT_FLAG = "OUT_WITHOUT_PRINT_FLAG"
    const val RECEIPT_RESET = "RECEIPT_RESET"

    // General (G)
    const val SINGLE_SESSION_FLAG = "SINGLE_SESSION_FLAG"
    const val PWD_MIN_LENGTH = "PWD_MIN_LENGTH"
    const val SHIFT_LOGIN_FLAG = "SHIFT_LOGIN_FLAG"
    const val SHIFT_EARLY_LOGIN_MIN = "SHIFT_EARLY_LOGIN_MIN"
    const val MAX_BAGS_PER_RECEIPT = "MAX_BAGS_PER_RECEIPT"
    const val PNR_REQUIRED = "PNR_REQUIRED"
    const val ID_PROOF_REQUIRED = "ID_PROOF_REQUIRED"
    const val ID_MASK_FLAG = "ID_MASK_FLAG"
    const val REPORT_FLAG = "REPORT_FLAG"
    const val REPORT_PWD_FLAG = "REPORT_PWD_FLAG"
    const val REPORT_PWD = "REPORT_PWD"
    const val TOTAL_COLLECTION_FLAG = "TOTAL_COLLECTION_FLAG"
    const val SYNC_INTERVAL_MIN = "SYNC_INTERVAL_MIN"

    // Print (P)
    const val PAPER_WIDTH = "PAPER_WIDTH"
    const val QR_FLAG = "QR_FLAG"
    const val CHECKIN_PRINT_FLAG = "CHECKIN_PRINT_FLAG"
    const val CHECKIN_PRINT_COPIES = "CHECKIN_PRINT_COPIES"
    const val CHECKOUT_PRINT_COPIES = "CHECKOUT_PRINT_COPIES"
    const val PRINTER_TYPE = "PRINTER_TYPE"
    const val BT_PRINTER = "BT_PRINTER"
    const val PAPER_FEED_MM = "PAPER_FEED_MM"
}

enum class GstType(val code: String) { CGST_SGST("E"), IGST("I") }

enum class PayMode(val code: String) { CASH("C"), UPI("U") }

enum class ReceiptReset(val code: String) { DAILY("D"), MONTHLY("M"), FINANCIAL_YEAR("Y"), NEVER("N") }

/** Which printer prints: found automatically, a paired Bluetooth printer, the POS's built-in serial or USB printer, or none. */
enum class PrinterType(val code: String) { AUTO("A"), BLUETOOTH("B"), BUILT_IN_SERIAL("S"), USB("U"), NONE("N") }

/**
 * Typed snapshot of every md_setting value for one user (user rows already override customer
 * rows). Built only from the database: there are no default values in code.
 */
data class AppSettings(
    // Billing
    val firstBlockRate: BigDecimal,
    val nextBlockRate: BigDecimal,
    val blockHours: Int,
    val graceMinutes: Int,
    val gstFlag: Boolean,
    /** The cloak room's own GST % ([gstPctOwn]); 0 when not set (the shared GST row applies). */
    val gstPct: BigDecimal,
    /** False when GST_PCT is blank: bills use the shared GST table of the bill date. */
    val gstPctOwn: Boolean,
    val gstType: GstType,
    val gstInclusive: Boolean,
    val penaltyFlag: Boolean,
    val penaltyMaxAmt: BigDecimal,
    val penaltyGstFlag: Boolean,
    val defaultPayMode: PayMode,
    val upiEnabled: Boolean,
    val outWithoutPrintFlag: Boolean,
    val receiptReset: ReceiptReset,
    // General
    val singleSessionFlag: Boolean,
    val pwdMinLength: Int,
    /** Counter users with an assigned shift may log in only during it. */
    val shiftLoginFlag: Boolean,
    /** Minutes before the shift start when login is already allowed. */
    val shiftEarlyLoginMin: Int,
    val maxBagsPerReceipt: Int,
    val pnrRequired: Boolean,
    val idProofRequired: Boolean,
    val idMaskFlag: Boolean,
    val reportFlag: Boolean,
    val reportPwdFlag: Boolean,
    /** PBKDF2 hash of the report password, or empty when none is set. */
    val reportPwdHash: String,
    val totalCollectionFlag: Boolean,
    val syncIntervalMin: Int,
    /** Admins may check in / check out too (off: only counter users do counter work). */
    // Print
    val paperWidthMm: Int,
    val qrFlag: Boolean,
    val checkinPrintFlag: Boolean,
    /** Copies per print of the check-in receipt. */
    val checkinPrintCopies: Int,
    /** Copies per print of the check-out bill. */
    val checkoutPrintCopies: Int,
    val printerType: PrinterType,
    /** Name or address of the paired Bluetooth printer; empty = find it automatically. */
    val btPrinter: String,
    /** Blank paper fed after each print so it reaches the tear bar (differs per printer model). */
    val paperFeedMm: Int,
) {
    /** Whether a user of [userType] may check bags in and out: counter users only, never admins. */
    fun canWorkCounter(userType: String): Boolean = userType != UserTypes.ADMIN

    companion object {
        /**
         * Builds the snapshot from setting name → value text.
         * @throws InvalidSettingException if a setting is missing or its value cannot be read.
         */
        fun fromValues(values: Map<String, String>): AppSettings {
            val r = Reader(values)
            return with(SettingKeys) {
                AppSettings(
                    firstBlockRate = r.decimal(FIRST_BLOCK_RATE),
                    nextBlockRate = r.decimal(NEXT_BLOCK_RATE),
                    blockHours = r.int(BLOCK_HOURS),
                    graceMinutes = r.int(GRACE_MINUTES),
                    gstFlag = r.bool(GST_FLAG),
                    gstPct = r.decimalOrNull(GST_PCT) ?: BigDecimal.ZERO,
                    gstPctOwn = r.decimalOrNull(GST_PCT) != null,
                    gstType = r.enum(GST_TYPE, GstType.entries) { it.code },
                    gstInclusive = r.bool(GST_INCLUSIVE),
                    penaltyFlag = r.bool(PENALTY_FLAG),
                    penaltyMaxAmt = r.decimal(PENALTY_MAX_AMT),
                    penaltyGstFlag = r.bool(PENALTY_GST_FLAG),
                    defaultPayMode = r.enum(DEFAULT_PAY_MODE, PayMode.entries) { it.code },
                    upiEnabled = r.bool(UPI_ENABLED),
                    outWithoutPrintFlag = r.bool(OUT_WITHOUT_PRINT_FLAG),
                    receiptReset = r.enum(RECEIPT_RESET, ReceiptReset.entries) { it.code },
                    singleSessionFlag = r.bool(SINGLE_SESSION_FLAG),
                    pwdMinLength = r.int(PWD_MIN_LENGTH),
                    shiftLoginFlag = r.bool(SHIFT_LOGIN_FLAG),
                    shiftEarlyLoginMin = r.int(SHIFT_EARLY_LOGIN_MIN),
                    maxBagsPerReceipt = r.int(MAX_BAGS_PER_RECEIPT),
                    pnrRequired = r.bool(PNR_REQUIRED),
                    idProofRequired = r.bool(ID_PROOF_REQUIRED),
                    idMaskFlag = r.bool(ID_MASK_FLAG),
                    reportFlag = r.bool(REPORT_FLAG),
                    reportPwdFlag = r.bool(REPORT_PWD_FLAG),
                    reportPwdHash = r.text(REPORT_PWD),
                    totalCollectionFlag = r.bool(TOTAL_COLLECTION_FLAG),
                    syncIntervalMin = r.int(SYNC_INTERVAL_MIN),
                    paperWidthMm = r.int(PAPER_WIDTH),
                    qrFlag = r.bool(QR_FLAG),
                    checkinPrintFlag = r.bool(CHECKIN_PRINT_FLAG),
                    checkinPrintCopies = r.int(CHECKIN_PRINT_COPIES),
                    checkoutPrintCopies = r.int(CHECKOUT_PRINT_COPIES),
                    printerType = r.enum(PRINTER_TYPE, PrinterType.entries) { it.code },
                    btPrinter = r.text(BT_PRINTER).trim(),
                    paperFeedMm = r.int(PAPER_FEED_MM),
                )
            }
        }
    }

    private class Reader(private val values: Map<String, String>) {
        fun text(name: String): String =
            values[name] ?: throw InvalidSettingException(name, "is missing")

        fun bool(name: String): Boolean = when (text(name).trim().uppercase()) {
            "Y" -> true
            "N" -> false
            else -> throw InvalidSettingException(name, "must be Y or N")
        }

        fun int(name: String): Int =
            text(name).trim().toIntOrNull() ?: throw InvalidSettingException(name, "must be a whole number")

        fun decimal(name: String): BigDecimal =
            text(name).trim().toBigDecimalOrNull() ?: throw InvalidSettingException(name, "must be a number")

        /** Blank = not set (null); anything else must be a number. */
        fun decimalOrNull(name: String): BigDecimal? = text(name).trim().ifEmpty { null }?.let {
            it.toBigDecimalOrNull() ?: throw InvalidSettingException(name, "must be a number")
        }

        fun <E> enum(name: String, entries: List<E>, codeOf: (E) -> String): E {
            val code = text(name).trim()
            return entries.firstOrNull { codeOf(it) == code }
                ?: throw InvalidSettingException(name, "has unknown value '$code'")
        }
    }
}

class InvalidSettingException(val settingName: String, problem: String) :
    IllegalStateException("Setting $settingName $problem")
