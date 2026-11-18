package com.synergic.cloakroom.data.seed

import com.synergic.cloakroom.domain.billing.RateSlabs
import com.synergic.cloakroom.domain.settings.SettingKeys
import java.math.BigDecimal

/**
 * First-launch demo data. Setting values here are only the starting rows of md_setting;
 * the app always reads them back from the table.
 */
object SeedData {

    data class SeedCustomer(
        val name: String,
        val address: String,
        val pinCode: String,
        val mobile: String,
        val email: String,
        val gstNo: String,
        val upiId: String,
    )

    val customer = SeedCustomer(
        name = "Demo Cloak Room",
        address = "Platform 1, Howrah Railway Station, Howrah, West Bengal",
        pinCode = "711101",
        mobile = "9830012345",
        email = "demo@cloakroom.example",
        gstNo = "19ABCDE1234F1Z5",
        upiId = "democloakroom@upi",
    )

    data class SeedUser(
        val userId: String,
        val userName: String,
        val userType: String,
        val userCode: String,
        val firstPassword: String,
    )

    val users = listOf(
        SeedUser("admin", "Admin", "A", "U01", "Admin@123"),
        SeedUser("user1", "Counter User", "O", "U02", "User@123"),
    )

    val idProofs = listOf("Aadhaar Card", "Driving Licence", "Passport", "PAN Card", "Voter ID")

    /** (line_type, narration). Lines print in this order. */
    val headerFooterLines = listOf(
        "H" to "Demo Cloak Room",
        "H" to "Platform 1, Howrah Railway Station",
        "F" to "Thank you! Keep this slip safe to collect your luggage.",
    )

    /** (shift_name, from_time, to_time), local time. */
    val shifts = listOf(
        Triple("Morning", "06:00", "14:00"),
        Triple("Evening", "14:00", "22:00"),
        Triple("Night", "22:00", "06:00"),
    )

    data class SeedSetting(
        val name: String,
        /** G, B or P. */
        val type: String,
        /** BOOL, INT, DECIMAL, TEXT, ENUM, PASSWORD or SLABS. */
        val dataType: String,
        val value: String,
        val label: String,
        val description: String,
        val options: String? = null,
        val min: Double? = null,
        val max: Double? = null,
        val dependsOn: String? = null,
        /** Fixed sort_order for a row inserted mid-list later, so it sorts right on existing phones too. */
        val sortOrder: Int? = null,
    )

    /** sort_order of [setting] at position [index] of [settings]: its fixed value, else 10, 20, 30… */
    fun sortOrderOf(index: Int, setting: SeedSetting): Int = setting.sortOrder ?: ((index + 1) * 10)

    /** In screen order; sort_order is assigned from the position in this list. */
    /** The old single copies setting; its values are copied into the check-in and check-out copies at start-up. */
    const val LEGACY_PRINT_COPIES = "PRINT_COPIES"

    /** Settings that were removed from the app: their md_setting rows (all scopes) are soft-deleted at start-up. */
    val retiredSettings: List<String> = listOf(
        "UPI_REF_REQUIRED", // 2026-11-05: no UPI reference number any more
        "DAY_BILL_LIMIT_FLAG", "DAY_BILL_LIMIT_COUNT", // 2026-11-05: bills per user per day are unlimited
        "MAX_LOGIN_ATTEMPTS", // 2026-11-05: the server decides blocking
        "ADMIN_COUNTER_FLAG", // 2026-11-05: admins never check in / check out
        SeedData.LEGACY_PRINT_COPIES, // 2026-11-08: split into CHECKIN_PRINT_COPIES and CHECKOUT_PRINT_COPIES
        // 2026-11-18: the two-rate tariff became the price ranges (RATE_SLABS), see DemoSeeder.mergeRateSettings.
        SettingKeys.FIRST_BLOCK_RATE, SettingKeys.NEXT_BLOCK_RATE, SettingKeys.BLOCK_HOURS,
    )

    /** First tariff of a new phone: 0–24 h ₹50 + 24–48 h ₹20, the same bills as the old 50 / 20 / 24 h seed. */
    val DEFAULT_RATE_SLABS: String = RateSlabs.toJson(RateSlabs.fromTwoRate(24, BigDecimal("50"), BigDecimal("20")))

    val settings: List<SeedSetting> = with(SettingKeys) {
        listOf(
            // Billing (B)
            // 2026-11-18: replaces FIRST_BLOCK_RATE / NEXT_BLOCK_RATE / BLOCK_HOURS (fixed sort_order 10 = their place).
            SeedSetting(
                RATE_SLABS, "B", "SLABS", DEFAULT_RATE_SLABS, "Price range",
                "Price per bag for each range of hours. After the last range, its price repeats every (to − from) hours",
                sortOrder = 10,
            ),
            // 2026-11-17: fixed sort_order 33 / 36 = between Block length (30) and Grace minutes (40) on older phones.
            SeedSetting(ADVANCE_FLAG, "B", "BOOL", "N", "Advance", "Take the first hours' rent in advance (cash) at check-in", sortOrder = 33),
            SeedSetting(ADVANCE_HOURS, "B", "INT", "24", "Advance hours", "Hours paid in advance at check-in (whole hours, at least 1)", min = 1.0, max = 720.0, dependsOn = ADVANCE_FLAG, sortOrder = 36),
            SeedSetting(GRACE_MINUTES, "B", "INT", "0", "Grace minutes", "Extra minutes allowed before the next block starts", min = 0.0),
            SeedSetting(GST_FLAG, "B", "BOOL", "Y", "GST applicable", "Is GST applicable?"),
            SeedSetting(GST_PCT, "B", "DECIMAL", "18", "GST %", "Total GST % (0–28)", min = 0.0, max = 28.0, dependsOn = GST_FLAG),
            SeedSetting(GST_TYPE, "B", "ENUM", "E", "GST type", "How GST is split", options = "E:CGST+SGST|I:IGST", dependsOn = GST_FLAG),
            SeedSetting(GST_INCLUSIVE, "B", "BOOL", "Y", "Rate includes GST", "Y = rate already includes GST; N = GST is added on top", dependsOn = GST_FLAG),
            SeedSetting(PENALTY_FLAG, "B", "BOOL", "Y", "Allow penalty", "Can the operator add a penalty at check-out?"),
            SeedSetting(PENALTY_MAX_AMT, "B", "DECIMAL", "500", "Maximum penalty (₹)", "Maximum penalty the operator can type", min = 0.0, dependsOn = PENALTY_FLAG),
            SeedSetting(PENALTY_GST_FLAG, "B", "BOOL", "Y", "GST on penalty", "Y = GST is applied to the penalty as well", dependsOn = PENALTY_FLAG),
            SeedSetting(DEFAULT_PAY_MODE, "B", "ENUM", "C", "Default payment mode", "Payment mode preselected at check-out", options = "C:Cash|U:UPI"),
            SeedSetting(UPI_ENABLED, "B", "BOOL", "Y", "UPI enabled", "Show the UPI option and the UPI QR"),
            SeedSetting(OUT_WITHOUT_PRINT_FLAG, "B", "BOOL", "Y", "Allow out without print", "Allow the \"Out Without Bill Print\" button"),
            SeedSetting(RECEIPT_RESET, "B", "ENUM", "Y", "Number reset", "When receipt/bill numbers restart", options = "D:Daily|M:Monthly|Y:Financial year (Apr–Mar)|N:Never"),
            // General (G)
            SeedSetting(SINGLE_SESSION_FLAG, "G", "BOOL", "Y", "Single session", "One active login per user ID"),
            SeedSetting(PWD_MIN_LENGTH, "G", "INT", "8", "Minimum password length", "Shortest password a user may set", min = 4.0, max = 32.0),
            SeedSetting(SHIFT_LOGIN_FLAG, "G", "BOOL", "Y", "Login only in own shift", "Counter users with an assigned shift can log in only during that shift"),
            SeedSetting(SHIFT_EARLY_LOGIN_MIN, "G", "INT", "15", "Early login (minutes)", "Minutes before the shift starts when login is already allowed", min = 0.0, max = 120.0, dependsOn = SHIFT_LOGIN_FLAG),
            SeedSetting(MAX_BAGS_PER_RECEIPT, "G", "INT", "10", "Max bags per receipt", "Max bags in one check-in", min = 1.0),
            SeedSetting(PNR_REQUIRED, "G", "BOOL", "Y", "PNR required", "PNR is mandatory at check-in"),
            SeedSetting(ID_PROOF_REQUIRED, "G", "BOOL", "Y", "ID proof required", "ID proof is mandatory at check-in"),
            SeedSetting(ID_MASK_FLAG, "G", "BOOL", "Y", "Mask ID number", "Store and print only the last 4 digits of the ID number"),
            SeedSetting(REPORT_FLAG, "G", "BOOL", "Y", "Users see own reports", "Normal users can see their own reports"),
            SeedSetting(REPORT_PWD_FLAG, "G", "BOOL", "N", "Report password", "Ask for a password before opening reports"),
            SeedSetting(REPORT_PWD, "G", "PASSWORD", "", "Report password value", "Report password (stored as a hash)", dependsOn = REPORT_PWD_FLAG),
            SeedSetting(TOTAL_COLLECTION_FLAG, "G", "BOOL", "Y", "Show total collection", "Show the total collection on the dashboard"),
            SeedSetting(SYNC_INTERVAL_MIN, "G", "INT", "15", "Sync interval (minutes)", "For the future sync (min 15). Not used yet", min = 15.0),
            // Print (P)
            SeedSetting(PAPER_WIDTH, "P", "ENUM", "58", "Paper width", "Printer paper width", options = "58:58 mm|80:80 mm"),
            SeedSetting(QR_FLAG, "P", "BOOL", "Y", "Print QR", "Print the QR on the check-in receipt"),
            SeedSetting(CHECKIN_PRINT_FLAG, "P", "BOOL", "Y", "Print at check-in", "Print a receipt at check-in"),
            SeedSetting(CHECKIN_PRINT_COPIES, "P", "INT", "1", "Print copies for Check-in", "Copies of the check-in receipt per print (1–3)", min = 1.0, max = 3.0),
            SeedSetting(CHECKOUT_PRINT_COPIES, "P", "INT", "1", "Print copies for Check-out", "Copies of the check-out bill per print (1–3)", min = 1.0, max = 3.0),
            SeedSetting(PRINTER_TYPE, "P", "ENUM", "A", "Printer", "Auto: a paired Bluetooth printer, else the POS's built-in printer. None = preview and Share PDF only", options = "A:Auto|B:Bluetooth|S:Built-in (GC039, serial)|U:USB (GC039ES)|N:None"),
            SeedSetting(BT_PRINTER, "P", "TEXT", "", "Bluetooth printer", "Name or address of the paired printer; leave empty to find it automatically"),
            // Appended (not in the General block) so existing phones keep their sort order.
            SeedSetting(PAPER_FEED_MM, "P", "INT", "24", "Blank paper after print (mm)", "Paper fed after each print to reach the tear bar. Lower it if your printer leaves a long blank strip", min = 0.0, max = 40.0),
        )
    }
}
