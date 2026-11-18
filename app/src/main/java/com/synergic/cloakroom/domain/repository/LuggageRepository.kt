package com.synergic.cloakroom.domain.repository

import com.synergic.cloakroom.domain.checkin.CheckinField
import com.synergic.cloakroom.domain.checkin.CheckinInput
import com.synergic.cloakroom.domain.print.PrintDocument
import com.synergic.cloakroom.domain.report.CheckinRow
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal

/** An ID proof type for the check-in dropdown (md_id_proof). */
/** [masked]: only the last 4 characters of the number are kept (md_id_proof.mask_flag). */
data class IdProof(val id: Long, val name: String, val masked: Boolean = false)

sealed interface CheckinResult {
    data class Saved(val transId: String, val receiptNo: String) : CheckinResult

    /** Nothing was saved and no receipt number was used. */
    data class Invalid(val errors: Map<CheckinField, String>) : CheckinResult
}

/**
 * Luggage check-in (td_luggage_in_out). Every method needs a logged-in user and throws
 * [com.synergic.cloakroom.domain.auth.NotAuthorizedException] otherwise.
 */
interface LuggageRepository {
    fun observeIdProofs(): Flow<List<IdProof>>

    /** Validates, takes the next receipt number and saves the row in one transaction. */
    suspend fun checkIn(input: CheckinInput): CheckinResult

    /** Bags in store now (all users, oldest first), for the Bags in store list. */
    fun observeBagsInStore(): Flow<List<CheckinRow>>

    /**
     * Advance to collect in cash now for [bags] bags (ADVANCE_FLAG = Y): the bill for ADVANCE_HOURS
     * with today's price ranges and GST setting. 0 when the advance is off.
     */
    suspend fun advanceQuote(bags: Int): BigDecimal

    /** The check-in receipt of [transId], laid out with the current print settings; null if not found. */
    suspend fun checkinReceipt(transId: String): PrintDocument?
}
