package com.synergic.cloakroom.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** The customer's tariff from the server (login / pull): the bill uses the rate of the check-in date. */
@Entity(tableName = "md_rate_dtls")
data class RateDtlsEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rate_id") val rateId: Long = 0,
    @ColumnInfo(name = "customer_id") val customerId: Long,
    /** Length of one billing block in hours (API_GUIDE.md §5 rates). */
    @ColumnInfo(name = "block_hours", defaultValue = "24") val blockHours: Int = 24,
    /** Per bag for the first block. */
    @ColumnInfo(name = "first_block_rate") val firstBlockRate: Double,
    /** Per bag for each further started block. */
    @ColumnInfo(name = "next_block_rate") val nextBlockRate: Double,
    /** Price ranges as the server sends them (rate_slabs JSON); null on rows from before version 6. */
    @ColumnInfo(name = "rate_slabs") val rateSlabs: String? = null,
    /** The bill uses the latest rate with effective_from ≤ receipt_dt (YYYY-MM-DD). */
    @ColumnInfo(name = "effective_from") val effectiveFrom: String,
    @ColumnInfo(name = "is_deleted", defaultValue = "'N'") val isDeleted: String = "N",
    @ColumnInfo(name = "sync_flag", defaultValue = "'N'") val syncFlag: String = "N",
    @ColumnInfo(name = "local_version", defaultValue = "0") val localVersion: Int = 1,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
