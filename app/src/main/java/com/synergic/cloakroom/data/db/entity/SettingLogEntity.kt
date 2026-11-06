package com.synergic.cloakroom.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Audit trail: one row per change of a md_setting value. */
@Entity(
    tableName = "td_setting_log",
    indices = [Index(value = ["customer_id", "changed_at"])],
)
data class SettingLogEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "customer_id") val customerId: Long,
    @ColumnInfo(name = "setting_name") val settingName: String,
    @ColumnInfo(name = "old_value") val oldValue: String?,
    @ColumnInfo(name = "new_value") val newValue: String?,
    @ColumnInfo(name = "changed_by") val changedBy: String,
    @ColumnInfo(name = "changed_at") val changedAt: String,
    @ColumnInfo(name = "is_deleted", defaultValue = "'N'") val isDeleted: String = "N",
    @ColumnInfo(name = "sync_flag", defaultValue = "'N'") val syncFlag: String = "N",
    @ColumnInfo(name = "local_version", defaultValue = "0") val localVersion: Int = 1,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
