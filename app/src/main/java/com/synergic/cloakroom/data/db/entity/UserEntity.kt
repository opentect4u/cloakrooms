package com.synergic.cloakroom.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * App user. `user_code`, `password_hash`, `must_change_pwd` and `failed_attempts` are
 * local-only columns needed while there is no server (see CLAUDE.md).
 */
@Entity(
    tableName = "md_user",
    indices = [Index(value = ["user_id"], unique = true)],
)
data class UserEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "customer_id") val customerId: Long?,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "user_name") val userName: String,
    /** S = super admin, A = customer admin, O = operator. */
    @ColumnInfo(name = "user_type") val userType: String,
    @ColumnInfo(name = "allow_flag", defaultValue = "'Y'") val allowFlag: String = "Y",
    @ColumnInfo(name = "device_id") val deviceId: String? = null,
    @ColumnInfo(name = "session_started_at") val sessionStartedAt: String? = null,
    @ColumnInfo(name = "last_login") val lastLogin: String? = null,
    @ColumnInfo(name = "user_code") val userCode: String? = null,
    @ColumnInfo(name = "password_hash") val passwordHash: String? = null,
    @ColumnInfo(name = "must_change_pwd", defaultValue = "'Y'") val mustChangePwd: String = "Y",
    @ColumnInfo(name = "failed_attempts", defaultValue = "0") val failedAttempts: Int = 0,
    @ColumnInfo(name = "is_deleted", defaultValue = "'N'") val isDeleted: String = "N",
    @ColumnInfo(name = "sync_flag", defaultValue = "'N'") val syncFlag: String = "N",
    @ColumnInfo(name = "local_version", defaultValue = "0") val localVersion: Int = 1,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
