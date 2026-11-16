package com.synergic.cloakroom.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.synergic.cloakroom.data.db.entity.AssignShiftEntity
import com.synergic.cloakroom.data.db.entity.CustomerEntity
import com.synergic.cloakroom.data.db.entity.GstEntity
import com.synergic.cloakroom.data.db.entity.HeaderFooterEntity
import com.synergic.cloakroom.data.db.entity.IdProofEntity
import com.synergic.cloakroom.data.db.entity.LuggageInOutEntity
import com.synergic.cloakroom.data.db.entity.RateDtlsEntity
import com.synergic.cloakroom.data.db.entity.ReceiptEntity
import com.synergic.cloakroom.data.db.entity.SettingEntity
import com.synergic.cloakroom.data.db.entity.SettingLogEntity
import com.synergic.cloakroom.data.db.entity.ShiftEntity
import com.synergic.cloakroom.data.db.entity.UserCounterEntity
import com.synergic.cloakroom.data.db.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(customer: CustomerEntity)

    /** The customer as the server sent it at login. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(customer: CustomerEntity)

    @Query("SELECT * FROM md_customer WHERE customer_id = :customerId AND is_deleted = 'N'")
    suspend fun get(customerId: Long): CustomerEntity?

    /** Customers on this device (demo data or copied from server logins). */
    @Query("SELECT COUNT(*) FROM md_customer")
    suspend fun count(): Int

    @Query("SELECT * FROM md_customer WHERE customer_id = :customerId AND is_deleted = 'N'")
    fun observe(customerId: Long): Flow<CustomerEntity?>

    @Update
    suspend fun update(customer: CustomerEntity)
}

@Dao
interface UserDao {
    /** Users of the customer from the server (masters.users); keeps the login id a row already has. */
    @Upsert
    suspend fun upsertAll(items: List<UserEntity>)

    @Query("SELECT * FROM md_user WHERE customer_id = :customerId")
    suspend fun getAllIncludingRemoved(customerId: Long): List<UserEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(user: UserEntity): Long

    /** The logged-in user as the server sent it; replaces any row with the same id or user_id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity)

    @Query("SELECT * FROM md_user WHERE user_id = :userId AND is_deleted = 'N'")
    suspend fun getByUserId(userId: String): UserEntity?

    @Query("SELECT * FROM md_user WHERE id = :id AND is_deleted = 'N'")
    suspend fun getById(id: Long): UserEntity?

    /** Includes deleted rows, because the unique index on user_id includes them too. */
    @Query("SELECT COUNT(*) FROM md_user WHERE user_id = :userId")
    suspend fun countWithUserId(userId: String): Int

    /** Includes deleted rows, so a code is never given out twice. */
    @Query("SELECT user_code FROM md_user WHERE customer_id = :customerId AND user_code IS NOT NULL")
    suspend fun getAllUserCodes(customerId: Long): List<String>

    @Update
    suspend fun update(user: UserEntity)

    @Query("SELECT * FROM md_user WHERE customer_id = :customerId AND is_deleted = 'N' ORDER BY user_code")
    fun observeAll(customerId: Long): Flow<List<UserEntity>>

    @Query("SELECT * FROM md_user WHERE customer_id = :customerId AND is_deleted = 'N' ORDER BY user_code")
    suspend fun getAll(customerId: Long): List<UserEntity>
}

@Dao
interface IdProofDao {
    @Upsert
    suspend fun upsert(items: List<IdProofEntity>)

    /** All rows, removed ones included (server import). */
    @Query("SELECT * FROM md_id_proof")
    suspend fun getAllIncludingRemoved(): List<IdProofEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<IdProofEntity>)

    @Query("SELECT * FROM md_id_proof WHERE is_deleted = 'N' ORDER BY id_proof_name")
    fun observeActive(): Flow<List<IdProofEntity>>

    @Query("SELECT * FROM md_id_proof WHERE id_proof_id = :id")
    suspend fun get(id: Long): IdProofEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: IdProofEntity): Long

    @Update
    suspend fun update(item: IdProofEntity)

    /** Active rows with this name (any case), other than [exceptId]. */
    @Query(
        """SELECT COUNT(*) FROM md_id_proof
           WHERE is_deleted = 'N' AND LOWER(id_proof_name) = LOWER(:name) AND id_proof_id != :exceptId"""
    )
    suspend fun countByName(name: String, exceptId: Long): Int
}

@Dao
interface HeaderFooterDao {
    @Upsert
    suspend fun upsert(items: List<HeaderFooterEntity>)

    @Query("SELECT * FROM md_header_footer WHERE customer_id = :customerId")
    suspend fun getAllIncludingRemoved(customerId: Long): List<HeaderFooterEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<HeaderFooterEntity>)

    @Query("SELECT * FROM md_header_footer WHERE customer_id = :customerId AND is_deleted = 'N' ORDER BY line_order, id")
    fun observeActive(customerId: Long): Flow<List<HeaderFooterEntity>>

    @Query("SELECT * FROM md_header_footer WHERE customer_id = :customerId AND is_deleted = 'N' ORDER BY line_order, id")
    suspend fun getActive(customerId: Long): List<HeaderFooterEntity>

    @Query("SELECT * FROM md_header_footer WHERE id = :id")
    suspend fun get(id: Long): HeaderFooterEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: HeaderFooterEntity): Long

    @Update
    suspend fun update(item: HeaderFooterEntity)
}

@Dao
interface ShiftDao {
    @Upsert
    suspend fun upsert(items: List<ShiftEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<ShiftEntity>)

    @Query("SELECT * FROM md_shift WHERE customer_id = :customerId AND is_deleted = 'N' ORDER BY from_time")
    fun observeActive(customerId: Long): Flow<List<ShiftEntity>>

    @Query("SELECT * FROM md_shift WHERE customer_id = :customerId AND is_deleted = 'N' ORDER BY from_time")
    suspend fun getActive(customerId: Long): List<ShiftEntity>

    @Query("SELECT * FROM md_shift WHERE shift_id = :id")
    suspend fun get(id: Long): ShiftEntity?

    /** Including removed shifts, which old rows may still point to. */
    @Query("SELECT * FROM md_shift WHERE customer_id = :customerId ORDER BY is_deleted, from_time")
    suspend fun getAllIncludingRemoved(customerId: Long): List<ShiftEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: ShiftEntity): Long

    @Update
    suspend fun update(item: ShiftEntity)
}

@Dao
interface SettingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<SettingEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: SettingEntity): Long

    /**
     * The customer-wide rows plus the rows of [userId]. With `userId = null` only the customer
     * rows match, because `user_id = NULL` is never true in SQL. Overrides are resolved by
     * [com.synergic.cloakroom.domain.settings.SettingsResolver].
     */
    @Query(
        """SELECT * FROM md_setting
           WHERE customer_id = :customerId AND is_deleted = 'N'
             AND (user_id IS NULL OR user_id = :userId)"""
    )
    suspend fun getScopedRows(customerId: Long, userId: Long?): List<SettingEntity>

    @Query(
        """SELECT * FROM md_setting
           WHERE customer_id = :customerId AND is_deleted = 'N'
             AND (user_id IS NULL OR user_id = :userId)"""
    )
    fun observeScopedRows(customerId: Long, userId: Long?): Flow<List<SettingEntity>>

    /** Soft-deletes every row (customer-wide and user overrides) of settings the app no longer has. */
    @Query(
        """UPDATE md_setting SET is_deleted = 'Y', sync_flag = 'N', local_version = local_version + 1,
               updated_by = :by, updated_at = :now
           WHERE setting_name IN (:names) AND is_deleted = 'N'"""
    )
    suspend fun retire(names: List<String>, by: String, now: String): Int

    /** Every active row (all customers, customer-wide and user overrides) of the given settings. */
    @Query("SELECT * FROM md_setting WHERE setting_name IN (:names) AND is_deleted = 'N'")
    suspend fun getActiveByNames(names: List<String>): List<SettingEntity>

    @Update
    suspend fun update(item: SettingEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLog(log: SettingLogEntity): Long

    @Query("SELECT * FROM td_setting_log WHERE customer_id = :customerId ORDER BY id")
    suspend fun getLogs(customerId: Long): List<SettingLogEntity>
}

@Dao
interface LuggageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: LuggageInOutEntity)

    @Query("SELECT * FROM td_luggage_in_out WHERE trans_id = :transId")
    suspend fun get(transId: String): LuggageInOutEntity?

    /**
     * Search for check-out. [pattern] is a LIKE pattern such as `%9876%` with `\` as escape
     * character. Bags still in store come first, newest first.
     */
    @Query(
        """SELECT * FROM td_luggage_in_out
           WHERE customer_id = :customerId AND is_deleted = 'N'
             AND (pnr_no LIKE :pattern ESCAPE '\' OR pasn_mob_no LIKE :pattern ESCAPE '\'
                  OR receipt_no LIKE :pattern ESCAPE '\' OR pasn_name LIKE :pattern ESCAPE '\')
           ORDER BY out_flag ASC, date_time_in DESC
           LIMIT :limit"""
    )
    suspend fun search(customerId: Long, pattern: String, limit: Int): List<LuggageInOutEntity>

    /** Check-out update from the PDF (Case 2). The caller wraps it in one transaction with the bill. */
    @Query(
        """UPDATE td_luggage_in_out
           SET user_id_out = :userId, device_id_out = :deviceId, shift_id_out = :shiftId,
               date_time_out = :now, out_flag = 'Y',
               sync_flag = 'N', local_version = local_version + 1,
               updated_by = :updatedBy, updated_at = :now
           WHERE trans_id = :transId AND out_flag = 'N'"""
    )
    suspend fun markOut(transId: String, userId: Long, deviceId: String, shiftId: Long?, now: String, updatedBy: String): Int

    /** Bags in store now (all users), oldest first. */
    @Query(
        """SELECT * FROM td_luggage_in_out
           WHERE customer_id = :customerId AND out_flag = 'N' AND is_deleted = 'N'
           ORDER BY date_time_in"""
    )
    fun observeInStore(customerId: Long): Flow<List<LuggageInOutEntity>>

    /** Check-ins on a local date, by check-in user; [userId] null = all users. */
    @Query(
        """SELECT COUNT(*) FROM td_luggage_in_out
           WHERE customer_id = :customerId AND receipt_dt = :date AND is_deleted = 'N'
             AND (:userId IS NULL OR user_id_in = :userId)"""
    )
    fun observeCheckinCount(customerId: Long, date: String, userId: Long?): Flow<Int>

    /** Check-ins between two local dates (both included), by check-in user and shift; null = all. */
    @Query(
        """SELECT * FROM td_luggage_in_out
           WHERE customer_id = :customerId AND receipt_dt BETWEEN :from AND :to AND is_deleted = 'N'
             AND (:userId IS NULL OR user_id_in = :userId)
             AND (:shiftId IS NULL OR shift_id_in = :shiftId)
           ORDER BY date_time_in"""
    )
    suspend fun getCheckins(customerId: Long, from: String, to: String, userId: Long?, shiftId: Long?): List<LuggageInOutEntity>

    /** Sync: marks the row synced only if unchanged since the push. */
    @Query("UPDATE td_luggage_in_out SET sync_flag = 'Y' WHERE trans_id = :transId AND local_version = :pushedVersion")
    suspend fun markSynced(transId: String, pushedVersion: Int): Int

    /** Rows waiting for the push (sync_flag = N), oldest first. */
    @Query("SELECT * FROM td_luggage_in_out WHERE customer_id = :customerId AND sync_flag = 'N' ORDER BY created_at, trans_id LIMIT :limit")
    suspend fun getPending(customerId: Long, limit: Int): List<LuggageInOutEntity>

    @Query("SELECT COUNT(*) FROM td_luggage_in_out WHERE customer_id = :customerId AND sync_flag = 'N'")
    fun observePendingCount(customerId: Long): Flow<Int>

    /** A row from the server (pull) or merged with it; written as synced. */
    @Update
    suspend fun update(item: LuggageInOutEntity)

    /** After a pull merge: the server already has this state (the resync trigger set N on the out fields). */
    @Query("UPDATE td_luggage_in_out SET sync_flag = 'Y' WHERE trans_id = :transId")
    suspend fun setSynced(transId: String)
}

@Dao
interface ReceiptDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: ReceiptEntity)

    /** Bills waiting for the push (sync_flag = N), oldest first. */
    @Query("SELECT * FROM td_receipt WHERE customer_id = :customerId AND sync_flag = 'N' ORDER BY created_at, trans_id LIMIT :limit")
    suspend fun getPending(customerId: Long, limit: Int): List<ReceiptEntity>

    @Query("SELECT COUNT(*) FROM td_receipt WHERE customer_id = :customerId AND sync_flag = 'N'")
    fun observePendingCount(customerId: Long): Flow<Int>

    /** Sync: marks the bill synced only if unchanged since the push. */
    @Query("UPDATE td_receipt SET sync_flag = 'Y' WHERE trans_id = :transId AND local_version = :pushedVersion")
    suspend fun markSynced(transId: String, pushedVersion: Int): Int

    @Query("SELECT * FROM td_receipt WHERE trans_id = :transId")
    suspend fun get(transId: String): ReceiptEntity?

    /** Bills on a local date (bill_dt) by billing user; [userId] null = all users. */
    @Query(
        """SELECT * FROM td_receipt
           WHERE customer_id = :customerId AND bill_dt = :date AND is_deleted = 'N'
             AND (:userId IS NULL OR user_id = :userId)"""
    )
    fun observeBillsOn(customerId: Long, date: String, userId: Long?): Flow<List<ReceiptEntity>>

    /** Bills between two local dates (both included) with their check-in details. */
    @Query(
        """SELECT r.*, l.receipt_no AS l_receipt_no, l.pasn_name AS l_name, l.date_time_out AS l_out,
                  l.extra_lugage_qty AS l_extra
           FROM td_receipt r JOIN td_luggage_in_out l ON l.trans_id = r.trans_id
           WHERE r.customer_id = :customerId AND r.bill_dt BETWEEN :from AND :to AND r.is_deleted = 'N'
             AND (:userId IS NULL OR r.user_id = :userId)
             AND (:shiftId IS NULL OR r.shift_id = :shiftId)
           ORDER BY r.created_at"""
    )
    suspend fun getBillsWithBag(customerId: Long, from: String, to: String, userId: Long?, shiftId: Long?): List<BillWithBag>
}

@Dao
interface UserCounterDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: UserCounterEntity)

    @Query(
        """SELECT * FROM md_user_counter WHERE customer_id = :customerId AND user_id = :userId
           AND counter_type = :counterType AND period_key = :periodKey"""
    )
    suspend fun get(customerId: Long, userId: Long, counterType: String, periodKey: String): UserCounterEntity?

    @Update
    suspend fun update(item: UserCounterEntity)
}

@Dao
interface AssignShiftDao {
    @Upsert
    suspend fun upsert(items: List<AssignShiftEntity>)

    @Query("SELECT * FROM td_assign_shift WHERE customer_id = :customerId")
    suspend fun getAllIncludingRemoved(customerId: Long): List<AssignShiftEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: AssignShiftEntity): Long

    @Update
    suspend fun update(item: AssignShiftEntity)

    /** The assignment in force on [today] (local date): the latest effective_from not after today. */
    @Query(
        """SELECT * FROM td_assign_shift
           WHERE customer_id = :customerId AND user_id = :userId AND is_deleted = 'N' AND effective_from <= :today
           ORDER BY effective_from DESC, id DESC LIMIT 1"""
    )
    suspend fun getCurrent(customerId: Long, userId: Long, today: String): AssignShiftEntity?

    @Query("SELECT * FROM td_assign_shift WHERE customer_id = :customerId AND user_id = :userId AND is_deleted = 'N'")
    suspend fun getActive(customerId: Long, userId: Long): List<AssignShiftEntity>

    /** All active assignments, newest first, for the Users list. */
    @Query(
        """SELECT * FROM td_assign_shift WHERE customer_id = :customerId AND is_deleted = 'N'
           ORDER BY effective_from DESC, id DESC"""
    )
    fun observeActive(customerId: Long): Flow<List<AssignShiftEntity>>
}

/** A bill with the receipt number, passenger name and check-out time of its luggage row. */
data class BillWithBag(
    @Embedded val receipt: ReceiptEntity,
    @ColumnInfo(name = "l_receipt_no") val receiptNo: String,
    @ColumnInfo(name = "l_name") val name: String,
    @ColumnInfo(name = "l_out") val dateTimeOut: String?,
    @ColumnInfo(name = "l_extra") val extraBags: Int,
)

/** Row counts of every table, for the database status screen. */
data class TableCounts(
    val customers: Int,
    val users: Int,
    val idProofs: Int,
    val rates: Int,
    val gst: Int,
    val headerFooter: Int,
    val shifts: Int,
    val assignShifts: Int,
    val settings: Int,
    val settingLogs: Int,
    val luggage: Int,
    val receipts: Int,
    val counters: Int,
)

@Dao
interface StatusDao {
    @Query(
        """SELECT
           (SELECT COUNT(*) FROM md_customer) AS customers,
           (SELECT COUNT(*) FROM md_user) AS users,
           (SELECT COUNT(*) FROM md_id_proof) AS idProofs,
           (SELECT COUNT(*) FROM md_rate_dtls) AS rates,
           (SELECT COUNT(*) FROM md_gst) AS gst,
           (SELECT COUNT(*) FROM md_header_footer) AS headerFooter,
           (SELECT COUNT(*) FROM md_shift) AS shifts,
           (SELECT COUNT(*) FROM td_assign_shift) AS assignShifts,
           (SELECT COUNT(*) FROM md_setting) AS settings,
           (SELECT COUNT(*) FROM td_setting_log) AS settingLogs,
           (SELECT COUNT(*) FROM td_luggage_in_out) AS luggage,
           (SELECT COUNT(*) FROM td_receipt) AS receipts,
           (SELECT COUNT(*) FROM md_user_counter) AS counters"""
    )
    fun observeCounts(): Flow<TableCounts>
}

/** Rates from the server (md_rate_dtls); the bill uses the latest with effective_from ≤ receipt_dt. */
@Dao
interface RateDao {
    @Upsert
    suspend fun upsert(items: List<RateDtlsEntity>)

    @Query("SELECT * FROM md_rate_dtls WHERE customer_id = :customerId")
    suspend fun getAll(customerId: Long): List<RateDtlsEntity>

    /** The rate in force on [date] (YYYY-MM-DD). */
    @Query(
        """SELECT * FROM md_rate_dtls WHERE customer_id = :customerId AND is_deleted = 'N' AND effective_from <= :date
           ORDER BY effective_from DESC LIMIT 1"""
    )
    suspend fun rateOn(customerId: Long, date: String): RateDtlsEntity?
}

/** GST rates from the server (md_gst, global); the bill uses the latest with effective_from ≤ bill_dt. */
@Dao
interface GstDao {
    @Upsert
    suspend fun upsert(items: List<GstEntity>)

    @Query("SELECT * FROM md_gst")
    suspend fun getAll(): List<GstEntity>

    @Query("SELECT * FROM md_gst WHERE is_deleted = 'N' AND effective_from <= :date ORDER BY effective_from DESC LIMIT 1")
    suspend fun gstOn(date: String): GstEntity?
}
