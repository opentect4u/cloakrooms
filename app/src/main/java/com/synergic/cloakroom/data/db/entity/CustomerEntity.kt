package com.synergic.cloakroom.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Cloak-room operator (tenant). Only customer 1 exists on the device in the offline phase. */
@Entity(tableName = "md_customer")
data class CustomerEntity(
    @PrimaryKey @ColumnInfo(name = "customer_id") val customerId: Long,
    @ColumnInfo(name = "customer_name") val customerName: String,
    @ColumnInfo(name = "customer_addr") val customerAddr: String,
    @ColumnInfo(name = "pin_code") val pinCode: String,
    @ColumnInfo(name = "state") val state: String? = null,
    @ColumnInfo(name = "mobile_no") val mobileNo: String,
    @ColumnInfo(name = "email") val email: String? = null,
    @ColumnInfo(name = "renewal_dt") val renewalDt: String? = null,
    @ColumnInfo(name = "gst_no") val gstNo: String? = null,
    @ColumnInfo(name = "upi_id") val upiId: String? = null,
    @ColumnInfo(name = "user_limit", defaultValue = "1") val userLimit: Int = 1,
    /** N = the customer may not log in (set by the super admin). */
    @ColumnInfo(name = "active_flag", defaultValue = "'Y'") val activeFlag: String = "Y",
    @ColumnInfo(name = "is_deleted", defaultValue = "'N'") val isDeleted: String = "N",
    @ColumnInfo(name = "sync_flag", defaultValue = "'N'") val syncFlag: String = "N",
    @ColumnInfo(name = "local_version", defaultValue = "0") val localVersion: Int = 1,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
)
