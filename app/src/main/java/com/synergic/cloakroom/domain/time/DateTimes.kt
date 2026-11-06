package com.synergic.cloakroom.domain.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Source of "now", so tests can use a fixed time. */
fun interface TimeSource {
    fun now(): Instant
}

/**
 * Database times are UTC text 'YYYY-MM-DD HH:MM:SS'. Screens and receipts show Asia/Kolkata time.
 * Local dates (receipt_dt, bill_dt) are Asia/Kolkata dates as 'YYYY-MM-DD'.
 */
object DateTimes {
    val DISPLAY_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    private val DB_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DISPLAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a")

    fun toDbText(instant: Instant): String =
        LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(DB_FORMAT)

    fun fromDbText(text: String): Instant =
        LocalDateTime.parse(text, DB_FORMAT).toInstant(ZoneOffset.UTC)

    fun localDate(instant: Instant): LocalDate = instant.atZone(DISPLAY_ZONE).toLocalDate()

    /** 'YYYY-MM-DD' in Asia/Kolkata, for receipt_dt and bill_dt. */
    fun localDateText(instant: Instant): String = localDate(instant).toString()

    /** e.g. "02-11-2026 07:05 PM" (Asia/Kolkata). */
    fun displayText(dbText: String): String =
        fromDbText(dbText).atZone(DISPLAY_ZONE).format(DISPLAY_FORMAT)
}
