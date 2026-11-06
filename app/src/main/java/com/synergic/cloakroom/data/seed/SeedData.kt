package com.synergic.cloakroom.data.seed

import com.synergic.cloakroom.domain.settings.SettingKeys

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
        /** BOOL, INT, DECIMAL, TEXT, ENUM or PASSWORD. */
        val dataType: String,
        val value: String,
        val label: String,
        val description: String,
        val options: String? = null,
        val min: Double? = null,
        val max: Double? = null,
        val dependsOn: String? = null,
    )

    /** In screen order; sort_order is assigned from the position in this list. */
    /** Settings that were removed from the app: their md_setting rows (all scopes) are soft-deleted at start-up. */
    val retiredSettings: List<String> = listOf(
        "UPI_REF_REQUIRED", // 2026-11-05: no UPI reference number any more
        "DAY_BILL_LIMIT_FLAG", "DAY_BILL_LIMIT_COUNT", // 2026-11-05: bills per user per day are unlimited
        "MAX_LOGIN_ATTEMPTS", // 2026-11-05: the server decides blocking
        "ADMIN_COUNTER_FLAG", // 2026-11-05: admins never check in / check out
    )

    val settings: List<SeedSetting> = with(SettingKeys) {
        listOf(
            // Billing (B)
            SeedSetting(FIRST_BLOCK_RATE, "B", "DECIMAL", "50", "First block rate (₹)", "Rate per bag for the first block", min = 0.0),
            SeedSetting(NEXT_BLOCK_RATE, "B", "DECIMAL", "20", "Next block rate (₹)", "Rate per bag for each further started block", min = 0.0),
            SeedSetting(BLOCK_HOURS, "B", "INT", "24", "Block length (hours)", "Length of one billing block in hours", min = 1.0),
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
            SeedSetting(PRINT_COPIES, "P", "INT", "1", "Print copies", "Copies per print (1–3)", min = 1.0, max = 3.0),
            SeedSetting(PRINTER_TYPE, "P", "ENUM", "A", "Printer", "Auto: a paired Bluetooth printer, else the POS's built-in printer. None = preview and Share PDF only", options = "A:Auto|B:Bluetooth|S:Built-in (GC039, serial)|U:USB (GC039ES)|N:None"),
            SeedSetting(BT_PRINTER, "P", "TEXT", "", "Bluetooth printer", "Name or address of the paired printer; leave empty to find it automatically"),
            // Appended (not in the General block) so existing phones keep their sort order.
        )
    }
}
