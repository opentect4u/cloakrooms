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

    /**
     * A timestamp sent by the server → database text (UTC). Since 2026-11-18 the server stores and
     * returns timestamps (date_time_in / out, created_at, session_started_at…) as **IST** wall time
     * 'YYYY-MM-DD HH:MM:SS'; a value with an explicit zone ('…Z', '+05:30') is read as that zone.
     * `server_time` is still UTC and must not go through here. Unreadable text is returned as it is.
     */
    fun fromServerText(text: String): String {
        val t = text.trim()
        return runCatching {
            val iso = t.replace(' ', 'T')
            val hasZone = iso.endsWith("Z") || Regex("T[^+-]*[+-]\\d{2}:\\d{2}$").containsMatchIn(iso)
            if (hasZone) {
                toDbText(java.time.OffsetDateTime.parse(iso).toInstant())
            } else {
                val local = LocalDateTime.parse(t.replace('T', ' ').substringBefore('.').take(19), DB_FORMAT)
                toDbText(local.atZone(DISPLAY_ZONE).toInstant())
            }
        }.getOrDefault(text)
    }

    /** e.g. "02-11-2026 07:05 PM" (Asia/Kolkata). */
    fun displayText(dbText: String): String =
        fromDbText(dbText).atZone(DISPLAY_ZONE).format(DISPLAY_FORMAT)
}
