package com.synergic.cloakroom.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Work shift. Times are local 'HH:MM' and may cross midnight. */
@Entity(tableName = "md_shift")
data class ShiftEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "shift_id") val shiftId: Long = 0,
    @ColumnInfo(name = "customer_id") val customerId: Long,
    @ColumnInfo(name = "shift_name") val shiftName: String,
    @ColumnInfo(name = "from_time") val fromTime: String,
    @ColumnInfo(name = "to_time") val toTime: String,
    @ColumnInfo(name = "is_deleted", defaultValue = "'N'") val isDeleted: String = "N",
    @ColumnInfo(name = "sync_flag", defaultValue = "'N'") val syncFlag: String = "N",
    @ColumnInfo(name = "local_version", defaultValue = "0") val localVersion: Int = 1,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
