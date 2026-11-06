package com.synergic.cloakroom.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One luggage transaction. Created at check-in; the out columns are filled at check-out.
 * Trigger `trg_inout_resync` resets `sync_flag` whenever an out column changes.
 */
@Entity(
    tableName = "td_luggage_in_out",
    indices = [
        Index(value = ["customer_id", "receipt_no", "receipt_dt"], unique = true),
        Index(value = ["customer_id", "date_time_in"]),
        Index(value = ["customer_id", "pasn_mob_no"]),
        Index(value = ["customer_id", "pnr_no"]),
    ],
)
data class LuggageInOutEntity(
    /** UUID created on the check-in device and printed in the QR. */
    @PrimaryKey @ColumnInfo(name = "trans_id") val transId: String,
    @ColumnInfo(name = "customer_id") val customerId: Long,
    @ColumnInfo(name = "receipt_no") val receiptNo: String,
    @ColumnInfo(name = "receipt_dt") val receiptDt: String,
    @ColumnInfo(name = "user_id_in") val userIdIn: Long,
    /** Null on a row built from a QR on another device (API_GUIDE.md §6.2, A8) until the check-in syncs. */
    @ColumnInfo(name = "device_id_in") val deviceIdIn: String?,
    @ColumnInfo(name = "shift_id_in") val shiftIdIn: Long? = null,
    @ColumnInfo(name = "date_time_in") val dateTimeIn: String,
    @ColumnInfo(name = "pasn_name") val pasnName: String,
    @ColumnInfo(name = "pasn_mob_no") val pasnMobNo: String,
    @ColumnInfo(name = "pnr_no") val pnrNo: String,
    @ColumnInfo(name = "luggage_qty") val luggageQty: Int,
    /** Optional, but always together with [idNumber] (never in the QR). */
    @ColumnInfo(name = "id_proof_id") val idProofId: Long?,
    @ColumnInfo(name = "id_number") val idNumber: String?,
    @ColumnInfo(name = "user_id_out") val userIdOut: Long? = null,
    @ColumnInfo(name = "device_id_out") val deviceIdOut: String? = null,
    @ColumnInfo(name = "shift_id_out") val shiftIdOut: Long? = null,
    @ColumnInfo(name = "date_time_out") val dateTimeOut: String? = null,
    @ColumnInfo(name = "out_flag", defaultValue = "'N'") val outFlag: String = "N",
    @ColumnInfo(name = "is_deleted", defaultValue = "'N'") val isDeleted: String = "N",
    @ColumnInfo(name = "sync_flag", defaultValue = "'N'") val syncFlag: String = "N",
    @ColumnInfo(name = "local_version", defaultValue = "0") val localVersion: Int = 1,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
