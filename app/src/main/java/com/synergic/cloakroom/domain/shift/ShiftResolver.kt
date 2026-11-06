package com.synergic.cloakroom.domain.shift

import java.time.LocalTime

/** A shift from md_shift; times are local 'HH:MM'. */
data class ShiftSlot(val shiftId: Long, val fromTime: String, val toTime: String)

/** Finds the shift whose time range contains a local time. A range may cross midnight (22:00–06:00). */
object ShiftResolver {

    fun shiftAt(shifts: List<ShiftSlot>, localTime: LocalTime): Long? = shifts.firstOrNull { s ->
        val from = parse(s.fromTime) ?: return@firstOrNull false
        val to = parse(s.toTime) ?: return@firstOrNull false
        when {
            from == to -> true // a 24-hour shift
            from < to -> localTime >= from && localTime < to
            else -> localTime >= from || localTime < to
        }
    }?.shiftId

    /**
     * Whether [localTime] is inside [shift], opening [earlyMinutes] before its start
     * (e.g. 05:45 for a 06:00 shift with 15 minutes). Handles shifts that cross midnight.
     * A shift with unreadable times never blocks anybody.
     */
    fun isWithin(shift: ShiftSlot, localTime: LocalTime, earlyMinutes: Int): Boolean {
        val start = parse(shift.fromTime) ?: return true
        val end = parse(shift.toTime) ?: return true
        if (start == end) return true
        // Minutes since the (early) opening, around the clock; inside while below the window length.
        val opens = start.minusMinutes(earlyMinutes.toLong().coerceIn(0, 24 * 60))
        val window = Math.floorMod(minuteOfDay(end) - minuteOfDay(opens), 24 * 60).let { if (it == 0) 24 * 60 else it }
        return Math.floorMod(minuteOfDay(localTime) - minuteOfDay(opens), 24 * 60) < window
    }

    private fun minuteOfDay(t: LocalTime) = t.hour * 60 + t.minute

    private fun parse(hhmm: String): LocalTime? = runCatching { LocalTime.parse(hhmm.trim()) }.getOrNull()
}
