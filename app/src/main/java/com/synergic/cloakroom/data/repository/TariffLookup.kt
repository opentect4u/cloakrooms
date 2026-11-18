package com.synergic.cloakroom.data.repository

import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.domain.billing.RateSlabs
import com.synergic.cloakroom.domain.billing.Tariff
import com.synergic.cloakroom.domain.billing.toMoney
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §7: the rate of the check-in date (md_rate_dtls, its price ranges) and the GST row of the bill
 * date (md_gst), as the server sent them at login / pull. Parts the phone does not have are null,
 * and the settings' values are used instead (withTariff).
 */
@Singleton
class TariffLookup @Inject constructor(private val db: AppDatabase) {

    suspend fun on(receiptDt: String, billDt: String): Tariff {
        val rate = db.rateDao().rateOn(Tenant.CUSTOMER_ID, receiptDt)
        // Used only when the cloak room has no own GST % (withTariff).
        val gst = db.gstDao().gstOn(billDt)
        return Tariff(
            slabs = rate?.let { r ->
                RateSlabs.parse(r.rateSlabs) ?: RateSlabs.fromTwoRate(r.blockHours, r.firstBlockRate.toMoney(), r.nextBlockRate.toMoney())
            },
            cgstPct = gst?.cgstPct?.toMoney(),
            sgstPct = gst?.sgstPct?.toMoney(),
            igstPct = gst?.igstPct?.toMoney(),
        )
    }
}
