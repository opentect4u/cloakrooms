package com.synergic.cloakroom.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** ID proof types for the check-in dropdown. */
@Entity(tableName = "md_id_proof")
data class IdProofEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id_proof_id") val idProofId: Long = 0,
    @ColumnInfo(name = "id_proof_name") val idProofName: String,
    /** Y (e.g. Aadhaar): only the last 4 characters of the ID number are kept and printed. */
    @ColumnInfo(name = "mask_flag", defaultValue = "'N'") val maskFlag: String = "N",
    @ColumnInfo(name = "is_deleted", defaultValue = "'N'") val isDeleted: String = "N",
    @ColumnInfo(name = "sync_flag", defaultValue = "'N'") val syncFlag: String = "N",
    @ColumnInfo(name = "local_version", defaultValue = "0") val localVersion: Int = 1,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
