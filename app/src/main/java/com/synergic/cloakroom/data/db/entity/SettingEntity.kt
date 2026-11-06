package com.synergic.cloakroom.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One rule/rate/switch. `user_id = NULL` applies to the whole customer; a row with a
 * user's md_user.id overrides it for that user. The columns after `setting_type` describe
 * how the Settings screen shows and validates the value.
 *
 * The unique index on (customer_id, IFNULL(user_id,0), setting_name) is an expression index,
 * which Room cannot declare, so it is created in [com.synergic.cloakroom.data.db.SchemaExtras].
 */
@Entity(tableName = "md_setting")
data class SettingEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "setting_id") val settingId: Long = 0,
    @ColumnInfo(name = "customer_id") val customerId: Long,
    @ColumnInfo(name = "user_id") val userId: Long? = null,
    @ColumnInfo(name = "setting_name") val settingName: String,
    @ColumnInfo(name = "setting_value") val settingValue: String,
    /** G = general, B = billing, P = print. */
    @ColumnInfo(name = "setting_type") val settingType: String,
    @ColumnInfo(name = "label") val label: String? = null,
    /** BOOL, INT, DECIMAL, TEXT, ENUM or PASSWORD. */
    @ColumnInfo(name = "data_type", defaultValue = "'TEXT'") val dataType: String = "TEXT",
    @ColumnInfo(name = "options") val options: String? = null,
    @ColumnInfo(name = "min_value") val minValue: Double? = null,
    @ColumnInfo(name = "max_value") val maxValue: Double? = null,
    @ColumnInfo(name = "depends_on") val dependsOn: String? = null,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "is_deleted", defaultValue = "'N'") val isDeleted: String = "N",
    @ColumnInfo(name = "sync_flag", defaultValue = "'N'") val syncFlag: String = "N",
    @ColumnInfo(name = "local_version", defaultValue = "0") val localVersion: Int = 1,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
