package com.synergic.cloakroom.domain.repository

/** How a §8 setting is edited. */
sealed interface ServerSettingKind {
    data object YesNo : ServerSettingKind
    data class Number(val min: Int, val max: Int) : ServerSettingKind
    /** Money with 2 decimals, sent as e.g. "500.00". */
    data class Decimal(val min: Double, val max: Double) : ServerSettingKind
    data class Choice(val options: List<Pair<String, String>>) : ServerSettingKind
    /** 4–64 characters; the server stores a hash and returns "********". */
    data object Password : ServerSettingKind
}

/** The settings keys of API_GUIDE.md §8 — the only keys the server accepts. */
enum class ServerSettingKey(val label: String, val help: String, val kind: ServerSettingKind, val default: String) {
    REPORT_FLAG("Counter users see reports", "Operators may open reports", ServerSettingKind.YesNo, "N"),
    REPORT_PWD_FLAG("Report password", "Reports need the report password", ServerSettingKind.YesNo, "N"),
    REPORT_PWD("Report password value", "4–64 characters; stored as a hash", ServerSettingKind.Password, ""),
    TOTAL_COLLECTION_FLAG("Show total collection", "Show the total collection", ServerSettingKind.YesNo, "Y"),
    RECEIPT_RESET(
        "Number reset", "When receipt / bill numbers restart",
        ServerSettingKind.Choice(listOf("D" to "Daily", "M" to "Monthly", "Y" to "Financial year (Apr–Mar)", "N" to "Never")), "Y",
    ),
    SYNC_INTERVAL_MIN("Sync interval (minutes)", "Background sync every … minutes", ServerSettingKind.Number(15, 1440), "15"),
    GST_FLAG("GST breakup on bill", "Print the GST breakup on the bill", ServerSettingKind.YesNo, "Y"),
    GST_TYPE("GST type", "How GST is split", ServerSettingKind.Choice(listOf("E" to "CGST + SGST (intra-state)", "I" to "IGST (inter-state)")), "E"),
    QR_FLAG("Print QR", "Print the QR on receipts", ServerSettingKind.YesNo, "Y"),
    DEFAULT_PAY_MODE("Default payment mode", "Preselected at check-out", ServerSettingKind.Choice(listOf("C" to "Cash", "U" to "UPI")), "C"),
    PAPER_WIDTH("Paper width", "Printer paper width", ServerSettingKind.Choice(listOf("58" to "58 mm", "80" to "80 mm")), "58"),

    // Keys added on the server on 2026-11-05 (customer-wide; ranges and defaults as in §8).
    GRACE_MINUTES("Grace minutes", "Extra minutes allowed before the next block starts", ServerSettingKind.Number(0, 1440), "0"),
    GST_INCLUSIVE("Rate includes GST", "Y = rate already includes GST; N = GST is added on top", ServerSettingKind.YesNo, "Y"),
    PENALTY_FLAG("Allow penalty", "Can the operator add a penalty at check-out?", ServerSettingKind.YesNo, "Y"),
    PENALTY_MAX_AMT("Maximum penalty (₹)", "Maximum penalty the operator can type", ServerSettingKind.Decimal(0.0, 99999.99), "500.00"),
    PENALTY_GST_FLAG("GST on penalty", "Y = GST is applied to the penalty as well", ServerSettingKind.YesNo, "Y"),
    UPI_ENABLED("UPI enabled", "Show the UPI option and the UPI QR", ServerSettingKind.YesNo, "Y"),
    OUT_WITHOUT_PRINT_FLAG("Allow out without print", "Allow the \"Out Without Bill Print\" button", ServerSettingKind.YesNo, "Y"),
    SINGLE_SESSION_FLAG("Single session", "One active login per user ID", ServerSettingKind.YesNo, "Y"),
    PWD_MIN_LENGTH("Minimum password length", "Shortest password a user may set", ServerSettingKind.Number(8, 32), "8"),
    SHIFT_LOGIN_FLAG("Login only in own shift", "Counter users with a shift may log in only during it", ServerSettingKind.YesNo, "Y"),
    SHIFT_EARLY_LOGIN_MIN("Early login (minutes)", "Minutes before the shift when login opens", ServerSettingKind.Number(0, 120), "15"),
    MAX_BAGS_PER_RECEIPT("Max bags per receipt", "Max bags in one check-in", ServerSettingKind.Number(1, 999), "10"),
    PNR_REQUIRED("PNR required", "PNR is mandatory at check-in", ServerSettingKind.YesNo, "Y"),
    ID_PROOF_REQUIRED("ID proof required", "ID proof is mandatory at check-in", ServerSettingKind.YesNo, "Y"),
    /** The cloak room's own GST %; absent (no row) = the shared GST table (/gst) of the bill date. */
    GST_PCT("GST %", "Total GST % for this cloak room (0–28)", ServerSettingKind.Decimal(0.0, 28.0), "18.00"),
    ID_MASK_FLAG("Mask ID number", "Store and print only the last 4 digits of the ID number", ServerSettingKind.YesNo, "Y"),
}
