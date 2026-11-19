package com.synergic.cloakroom.data.repository

import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.domain.repository.SubscriptionRepository
import com.synergic.cloakroom.domain.subscription.Subscription
import com.synergic.cloakroom.domain.subscription.SubscriptionExpiredException
import com.synergic.cloakroom.domain.subscription.SubscriptionStatus
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads md_customer.renewal_dt on the phone, so the rule also works offline. The server sends the
 * renewal date at login and with every sync (GET /customers/me), so a renewal by the super admin
 * reaches logged-in users at the next sync.
 */
@Singleton
class SubscriptionRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val time: TimeSource,
) : SubscriptionRepository {

    override fun observeStatus(): Flow<SubscriptionStatus> =
        combine(db.customerDao().observe(Tenant.CUSTOMER_ID), today()) { customer, today ->
            Subscription.status(customer?.renewalDt, today)
        }.distinctUntilChanged()

    override suspend fun requireActive() {
        val status = Subscription.status(db.customerDao().get(Tenant.CUSTOMER_ID)?.renewalDt, DateTimes.localDate(time.now()))
        if (status is SubscriptionStatus.Expired) throw SubscriptionExpiredException(status.endDate)
    }

    /** The local date, re-checked every minute so the screen turns at midnight with the app open. */
    private fun today() = flow {
        while (true) {
            emit(DateTimes.localDate(time.now()))
            delay(60_000)
        }
    }.distinctUntilChanged()
}
