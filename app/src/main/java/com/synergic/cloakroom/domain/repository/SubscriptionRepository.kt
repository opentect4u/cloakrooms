package com.synergic.cloakroom.domain.repository

import com.synergic.cloakroom.domain.subscription.SubscriptionStatus
import kotlinx.coroutines.flow.Flow

/** The customer's subscription (renewal date), kept fresh by login and every sync. */
interface SubscriptionRepository {
    /** Live: changes when a sync brings a new renewal date, and when the day changes. */
    fun observeStatus(): Flow<SubscriptionStatus>

    /** Throws SubscriptionExpiredException after the end date. Called before every check-in and check-out. */
    suspend fun requireActive()
}
