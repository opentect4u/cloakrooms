package com.synergic.cloakroom.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Last receipt (R) or bill (B) number used by a user in one reset period.
 * `user_id` references md_user.id; `period_key` is e.g. 2026-11-02, 2026-11, FY2026-27 or ALL.
 */
@Entity(
    tableName = "md_user_counter",
    primaryKeys = ["customer_id", "user_id", "counter_type", "period_key"],
)
data class UserCounterEntity(
    @ColumnInfo(name = "customer_id") val customerId: Long,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "counter_type") val counterType: String,
    @ColumnInfo(name = "period_key") val periodKey: String,
    @ColumnInfo(name = "last_no", defaultValue = "0") val lastNo: Int = 0,
    @ColumnInfo(name = "is_deleted", defaultValue = "'N'") val isDeleted: String = "N",
    @ColumnInfo(name = "sync_flag", defaultValue = "'N'") val syncFlag: String = "N",
    @ColumnInfo(name = "local_version", defaultValue = "0") val localVersion: Int = 1,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
