package com.synergic.cloakroom.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The bill, one per luggage transaction, made at check-out. Rates, block length and GST are
 * snapshotted so later setting changes never alter old bills.
 */
@Entity(
    tableName = "td_receipt",
    indices = [
        Index(value = ["customer_id", "bill_no", "bill_dt"], unique = true),
        Index(value = ["customer_id", "bill_dt"]),
    ],
)
data class ReceiptEntity(
    @PrimaryKey @ColumnInfo(name = "trans_id") val transId: String,
    @ColumnInfo(name = "customer_id") val customerId: Long,
    @ColumnInfo(name = "bill_no") val billNo: String,
    @ColumnInfo(name = "bill_dt") val billDt: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "shift_id") val shiftId: Long? = null,
    @ColumnInfo(name = "luggage_qty") val luggageQty: Int,
    /** Number of billing blocks charged (API_GUIDE.md: days_charged). */
    @ColumnInfo(name = "days_charged") val daysCharged: Int,
    @ColumnInfo(name = "first_block_rate", defaultValue = "0.0") val firstBlockRate: Double = 0.0,
    @ColumnInfo(name = "next_block_rate", defaultValue = "0.0") val nextBlockRate: Double = 0.0,
    @ColumnInfo(name = "block_hours", defaultValue = "0") val blockHours: Int = 0,
    /** The price ranges billed with (rate_slabs JSON, database version 6); null on older bills (then the two-rate fields). */
    @ColumnInfo(name = "rate_slabs") val rateSlabs: String? = null,
    @ColumnInfo(name = "base_amt", defaultValue = "0.0") val baseAmt: Double = 0.0,
    @ColumnInfo(name = "penal_amt", defaultValue = "0.0") val penalAmt: Double = 0.0,
    /** The full bill. */
    @ColumnInfo(name = "net_amt", defaultValue = "0.0") val netAmt: Double = 0.0,
    /** The bag's advance (paid at check-in), subtracted from [netAmt]. */
    @ColumnInfo(name = "adv_amt", defaultValue = "0") val advAmt: Double = 0.0,
    /** Collected at check-out: max(0, net_amt − adv_amt). Older bills: net_amt. */
    @ColumnInfo(name = "bal_amt", defaultValue = "0") val balAmt: Double = 0.0,
    @ColumnInfo(name = "taxable_amt", defaultValue = "0.0") val taxableAmt: Double = 0.0,
    @ColumnInfo(name = "gst_pct", defaultValue = "0.0") val gstPct: Double = 0.0,
    @ColumnInfo(name = "cgst", defaultValue = "0.0") val cgst: Double = 0.0,
    @ColumnInfo(name = "sgst", defaultValue = "0.0") val sgst: Double = 0.0,
    @ColumnInfo(name = "igst", defaultValue = "0.0") val igst: Double = 0.0,
    @ColumnInfo(name = "gst_flag", defaultValue = "'N'") val gstFlag: String = "N",
    /** Options the bill was made with (database version 4); the server recomputes with them. */
    @ColumnInfo(name = "grace_minutes", defaultValue = "0") val graceMinutes: Int = 0,
    @ColumnInfo(name = "gst_inclusive", defaultValue = "'Y'") val gstInclusive: String = "Y",
    @ColumnInfo(name = "penalty_gst_flag", defaultValue = "'Y'") val penaltyGstFlag: String = "Y",
    /** C = cash, U = UPI. */
    @ColumnInfo(name = "pay_mode", defaultValue = "'C'") val payMode: String = "C",
    @ColumnInfo(name = "upi_ref") val upiRef: String? = null,
    @ColumnInfo(name = "bill_printed", defaultValue = "'Y'") val billPrinted: String = "Y",
    /** GSTIN printed on this bill (snapshot of md_customer.gst_no); null when GST was not applied. Added in db version 2. */
    @ColumnInfo(name = "gst_no") val gstNo: String? = null,
    @ColumnInfo(name = "is_deleted", defaultValue = "'N'") val isDeleted: String = "N",
    @ColumnInfo(name = "sync_flag", defaultValue = "'N'") val syncFlag: String = "N",
    @ColumnInfo(name = "local_version", defaultValue = "0") val localVersion: Int = 1,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
