package com.synergic.cloakroom.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** One receipt/bill/report header, footer or logo line. Lines print in `id` order. */
@Entity(tableName = "md_header_footer")
data class HeaderFooterEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "customer_id") val customerId: Long,
    /** H = header, F = footer, L = logo. */
    @ColumnInfo(name = "line_type") val lineType: String,
    /** Print order within the line type (lower first). */
    @ColumnInfo(name = "line_order", defaultValue = "0") val lineOrder: Int = 0,
    @ColumnInfo(name = "narration") val narration: String? = null,
    @ColumnInfo(name = "image", typeAffinity = ColumnInfo.BLOB) val image: ByteArray? = null,
    @ColumnInfo(name = "in_flag", defaultValue = "'Y'") val inFlag: String = "Y",
    @ColumnInfo(name = "out_flag", defaultValue = "'Y'") val outFlag: String = "Y",
    @ColumnInfo(name = "report_flag", defaultValue = "'N'") val reportFlag: String = "N",
    @ColumnInfo(name = "is_deleted", defaultValue = "'N'") val isDeleted: String = "N",
    @ColumnInfo(name = "sync_flag", defaultValue = "'N'") val syncFlag: String = "N",
    @ColumnInfo(name = "local_version", defaultValue = "0") val localVersion: Int = 1,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
