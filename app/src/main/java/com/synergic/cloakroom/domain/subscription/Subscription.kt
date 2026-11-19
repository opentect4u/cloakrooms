package com.synergic.cloakroom.domain.subscription

import com.synergic.cloakroom.domain.time.DateTimes
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The customer's subscription, from md_customer.renewal_dt (= the end date of the latest
 * registration the Synergic super admin added).
 *
 * - The end date itself is still a working day; from the next day (Asia/Kolkata) it is [Expired].
 * - [Expired] blocks only check-in and check-out. Users stay logged in; reports, bags in store and
 *   reprint keep working.
 * - Within [Subscription.WARN_DAYS] days before the end date it is [ExpiringSoon] and every user of
 *   the customer (admin too) sees a notice at the top of the dashboard.
 * - No renewal date (demo data, or none set yet) = [Active]: nothing is blocked.
 */
sealed interface SubscriptionStatus {
    data object Active : SubscriptionStatus

    /** [daysLeft] = 0 on the end date itself. */
    data class ExpiringSoon(val endDate: LocalDate, val daysLeft: Long) : SubscriptionStatus

    data class Expired(val endDate: LocalDate) : SubscriptionStatus
}

/** Check-in or check-out was refused because the subscription ended; the message is shown on screen. */
class SubscriptionExpiredException(endDate: LocalDate) :
    RuntimeException("Subscription expired on ${Subscription.display(endDate)}. Check-in and check-out are stopped until it is renewed. Please contact Synergic.")

object Subscription {
    /**
     * Days before the end date when the renewal notice starts. Set by Synergic (your instruction,
     * 2026-11-19), not by the customer, so it is not an md_setting row.
     */
    const val WARN_DAYS = 10L

    private val DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    fun status(renewalDt: String?, today: LocalDate): SubscriptionStatus {
        val end = parse(renewalDt) ?: return SubscriptionStatus.Active
        val daysLeft = ChronoUnit.DAYS.between(today, end)
        return when {
            daysLeft < 0 -> SubscriptionStatus.Expired(end)
            daysLeft < WARN_DAYS -> SubscriptionStatus.ExpiringSoon(end, daysLeft)
            else -> SubscriptionStatus.Active
        }
    }

    /**
     * 'YYYY-MM-DD' as the super admin typed it. A value with a time (e.g. '2026-11-17T18:30:00Z')
     * is turned into the Asia/Kolkata date. Unreadable or empty text = no end date.
     */
    fun parse(renewalDt: String?): LocalDate? {
        val t = renewalDt?.trim().orEmpty()
        if (t.isEmpty()) return null
        return runCatching {
            if (t.length <= 10) LocalDate.parse(t)
            else DateTimes.localDate(DateTimes.fromDbText(DateTimes.fromServerText(t)))
        }.recoverCatching { LocalDate.parse(t.take(10)) }.getOrNull()
    }

    fun display(date: LocalDate): String = date.format(DISPLAY)

    /** The text of the dashboard notice. */
    fun notice(status: SubscriptionStatus): String? = when (status) {
        SubscriptionStatus.Active -> null
        is SubscriptionStatus.Expired ->
            "Subscription expired on ${display(status.endDate)} (not renewed). Check-in and check-out are stopped. Please contact Synergic to renew."
        is SubscriptionStatus.ExpiringSoon -> when (status.daysLeft) {
            0L -> "Subscription ends today (${display(status.endDate)}). Please renew to keep using check-in and check-out."
            1L -> "Subscription ends tomorrow (${display(status.endDate)}). Please renew to avoid interruption."
            else -> "Subscription ends on ${display(status.endDate)} (${status.daysLeft} days left). Please renew to avoid interruption."
        }
    }
}
