package com.synergic.cloakroom.domain.billing

import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.settings.GstType
import java.math.BigDecimal

/**
 * The server's tariff for one bill (API_GUIDE.md §7): the rate in force on the check-in date
 * (latest `effective_from ≤ receipt_dt`) and the GST row in force on the bill date
 * (`effective_from ≤ bill_dt`). Either part is null when the phone has no such row (demo data);
 * the settings' values are used then. A cloak room's own GST % (GST_PCT set) replaces the GST row.
 */
data class Tariff(
    val blockHours: Int? = null,
    val firstBlockRate: BigDecimal? = null,
    val nextBlockRate: BigDecimal? = null,
    val cgstPct: BigDecimal? = null,
    val sgstPct: BigDecimal? = null,
    val igstPct: BigDecimal? = null,
)

/** [AppSettings] with the tariff's rate and GST % (CGST + SGST for E, IGST for I) put in. */
fun AppSettings.withTariff(t: Tariff?): AppSettings {
    if (t == null) return this
    val gst = when (gstType) {
        GstType.CGST_SGST -> if (t.cgstPct != null && t.sgstPct != null) t.cgstPct + t.sgstPct else null
        GstType.IGST -> t.igstPct
    }
    return copy(
        blockHours = t.blockHours ?: blockHours,
        firstBlockRate = t.firstBlockRate ?: firstBlockRate,
        nextBlockRate = t.nextBlockRate ?: nextBlockRate,
        gstPct = if (gstPctOwn) gstPct else gst ?: gstPct,
    )
}
