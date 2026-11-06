package com.synergic.cloakroom.data.numbering

import androidx.room.withTransaction
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.data.db.entity.UserCounterEntity
import com.synergic.cloakroom.domain.numbering.CounterType
import com.synergic.cloakroom.domain.numbering.NumberGenerator
import com.synergic.cloakroom.domain.settings.ReceiptReset
import com.synergic.cloakroom.domain.time.DateTimes
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A reserved number and the md_user_counter period it belongs to. */
data class ReservedNumber(val formatted: String, val number: Int, val periodKey: String)

/**
 * Hands out the next receipt or bill number of a user from md_user_counter.
 *
 * Call [next] inside the same `db.withTransaction { … }` that inserts the receipt or bill: if the
 * insert fails, the counter change is rolled back too, and SQLite's single writer means two
 * transactions can never get the same number.
 */
@Singleton
class UserCounterSequence @Inject constructor(
    private val db: AppDatabase,
) {
    suspend fun next(
        userId: Long,
        userCode: String,
        type: CounterType,
        reset: ReceiptReset,
        at: Instant,
        by: String,
    ): ReservedNumber = db.withTransaction {
        val dao = db.userCounterDao()
        val period = NumberGenerator.periodKey(reset, DateTimes.localDate(at))
        val now = DateTimes.toDbText(at)
        val current = dao.get(Tenant.CUSTOMER_ID, userId, type.code, period)
        val number = if (current == null) {
            dao.insert(
                UserCounterEntity(
                    customerId = Tenant.CUSTOMER_ID,
                    userId = userId,
                    counterType = type.code,
                    periodKey = period,
                    lastNo = 1,
                    createdBy = by, createdAt = now, updatedBy = by, updatedAt = now,
                )
            )
            1
        } else {
            val next = current.lastNo + 1
            dao.update(
                current.copy(
                    lastNo = next,
                    syncFlag = "N",
                    localVersion = current.localVersion + 1,
                    updatedBy = by,
                    updatedAt = now,
                )
            )
            next
        }
        ReservedNumber(NumberGenerator.format(userCode, number), number, period)
    }
}
