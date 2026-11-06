package com.synergic.cloakroom.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Shift assigned to a user (`user_id` references md_user.id). */
@Entity(tableName = "td_assign_shift")
data class AssignShiftEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "customer_id") val customerId: Long,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "shift_id") val shiftId: Long,
    @ColumnInfo(name = "effective_from") val effectiveFrom: String,
    @ColumnInfo(name = "is_deleted", defaultValue = "'N'") val isDeleted: String = "N",
    @ColumnInfo(name = "sync_flag", defaultValue = "'N'") val syncFlag: String = "N",
    @ColumnInfo(name = "local_version", defaultValue = "0") val localVersion: Int = 1,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
