package com.synergic.cloakroom.domain.repository

import com.synergic.cloakroom.domain.billing.Tariff
import com.synergic.cloakroom.domain.billing.ZERO_MONEY

import com.synergic.cloakroom.domain.checkout.CheckoutField
import com.synergic.cloakroom.domain.print.PrintDocument
import com.synergic.cloakroom.domain.qr.ReceiptQrData
import com.synergic.cloakroom.domain.settings.PayMode
import com.synergic.cloakroom.domain.upi.UpiPayee
import java.math.BigDecimal

/** A checked-in bag as shown on the Check-out screen. */
data class CheckoutBag(
    val transId: String,
    val receiptNo: String,
    val receiptDt: String,
    /** md_user.id of the check-in user. */
    val userIdIn: Long,
    /** UTC db text. */
    val dateTimeIn: String,
    val name: String,
    val mobile: String,
    val pnr: String,
    val bags: Int,
    /** Extra bags stored free of charge: shown, never billed. */
    val extraBags: Int = 0,
    /** Advance paid at check-in (subtracted from the bill); 0 when none. */
    val advAmt: BigDecimal = ZERO_MONEY,
    val idProofName: String?,
    /** Stored (masked) ID number; empty for a bag known only from its QR. */
    val idNumber: String,
    val isOut: Boolean,
    /** Bill number when the bag is already out. */
    val billNo: String?,
    /**
     * Set when the bag was checked in on another counter and is not on this device yet
     * (Case 1 in the PDF): the check-in row is created from the QR when the bill is saved.
     */
    val fromQr: ReceiptQrData? = null,
)

sealed interface QrLookup {
    data class Found(val bag: CheckoutBag) : QrLookup
    data object NotAReceipt : QrLookup
    data object OtherCustomer : QrLookup
}

data class CheckoutRequest(
    val bag: CheckoutBag,
    val penalty: String,
    val payMode: PayMode,
    /** true = "Bill Print", false = "Out Without Bill Print" (td_receipt.bill_printed). */
    val printBill: Boolean,
    /** The total the operator confirmed; the save is refused if the bill has changed since. */
    val expectedNet: BigDecimal,
)

sealed interface CheckoutResult {
    data class Saved(val transId: String, val billNo: String, val netAmt: BigDecimal) : CheckoutResult

    /** Nothing was saved and no bill number was used. */
    data class Invalid(val errors: Map<CheckoutField, String>) : CheckoutResult
}

/**
 * Check-out and bills (td_receipt). Every method needs a logged-in user (admin or operator) and
 * throws [com.synergic.cloakroom.domain.auth.NotAuthorizedException] otherwise.
 */
interface BillRepository {
    /** Bags matching a PNR, mobile, name or receipt number; bags still in store first. */
    suspend fun search(query: String): List<CheckoutBag>

    suspend fun find(transId: String): CheckoutBag?

    /** Reads a scanned QR: the bag on this device, or one built from the QR (another counter). */
    suspend fun lookupQr(text: String): QrLookup

    /** Payee for the UPI QR, or null when md_customer.upi_id is empty. */
    suspend fun upiPayee(): UpiPayee?

    /** The server's rate for a bag checked in on [receiptDt] and today's GST row (§7); null parts = settings. */
    suspend fun tariff(receiptDt: String): Tariff

    /**
     * Validates, calculates the bill at the current time and, in one transaction, marks the bag
     * out, takes the next bill number and saves the bill with its rate and GST snapshot.
     */
    suspend fun checkOut(request: CheckoutRequest): CheckoutResult

    /** The bill of [transId] from its saved snapshot; null when there is no bill. */
    suspend fun billDocument(transId: String): PrintDocument?
}
