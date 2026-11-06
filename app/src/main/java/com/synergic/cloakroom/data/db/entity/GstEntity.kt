package com.synergic.cloakroom.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Kept for the future sync only. In this phase GST is read from md_setting. */
@Entity(tableName = "md_gst")
data class GstEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "gst_id") val gstId: Long = 0,
    @ColumnInfo(name = "cgst_pct", defaultValue = "0.0") val cgstPct: Double = 0.0,
    @ColumnInfo(name = "sgst_pct", defaultValue = "0.0") val sgstPct: Double = 0.0,
    @ColumnInfo(name = "igst_pct", defaultValue = "0.0") val igstPct: Double = 0.0,
    /** The bill uses the latest GST row with effective_from ≤ bill_dt (YYYY-MM-DD). */
    @ColumnInfo(name = "effective_from", defaultValue = "'2017-07-01'") val effectiveFrom: String = "2017-07-01",
    @ColumnInfo(name = "is_deleted", defaultValue = "'N'") val isDeleted: String = "N",
    @ColumnInfo(name = "sync_flag", defaultValue = "'N'") val syncFlag: String = "N",
    @ColumnInfo(name = "local_version", defaultValue = "0") val localVersion: Int = 1,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
