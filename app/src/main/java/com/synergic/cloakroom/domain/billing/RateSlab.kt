package com.synergic.cloakroom.domain.billing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal

/**
 * One price range of the tariff (API_GUIDE.md §7 `rate_slabs`): from [fromHour] to [toHour] the
 * bag costs [rate] (per bag, added once when the stay reaches this range).
 */
data class RateSlab(val fromHour: Int, val toHour: Int, val rate: BigDecimal) {
    val hours: Int get() = toHour - fromHour
}

/** One line of the bill breakup: [slab] was charged [times] per bag. */
data class SlabCharge(val slab: RateSlab, val times: Int, val isRepeat: Boolean)

/**
 * The tariff as a list of [RateSlab], stored as the server's JSON:
 * `[{"from_hour":0,"to_hour":24,"rate":"30.00"},{"from_hour":24,"to_hour":48,"rate":"10.00"}]`.
 *
 * Bill rule (§7, the owner's examples):
 * - every started slab adds its own rate per bag; the first slab is always charged, a later one
 *   once the stay goes past its from_hour;
 * - after the last slab ends, its rate is added again for every started (to − from) hours;
 * - days_charged = how many rates were added.
 */
object RateSlabs {
    const val MAX_SLABS = 20
    const val MAX_HOUR = 8760

    private val json = Json { ignoreUnknownKeys = true }

    /** The older two-rate tariff as slabs: 0–B h first rate, B–2B h next rate (same bills as before). */
    fun fromTwoRate(blockHours: Int, firstRate: BigDecimal, nextRate: BigDecimal): List<RateSlab> = listOf(
        RateSlab(0, blockHours, firstRate.money()),
        RateSlab(blockHours, blockHours * 2, nextRate.money()),
    )

    /** Strict reading of saved JSON; null when it is not a valid tariff ([problem] says why). */
    fun parse(text: String?): List<RateSlab>? {
        val rows = parseDraft(text ?: return null) ?: return null
        val slabs = rows.map { r ->
            RateSlab(
                fromHour = r.from.trim().toIntOrNull() ?: return null,
                toHour = r.to.trim().toIntOrNull() ?: return null,
                rate = r.rate.trim().toBigDecimalOrNull()?.money() ?: return null,
            )
        }
        return slabs.takeIf { problem(it) == null }
    }

    /** Why [slabs] is not a valid tariff, or null when it is. */
    fun problem(slabs: List<RateSlab>): String? {
        if (slabs.isEmpty()) return "Add at least one price range"
        if (slabs.size > MAX_SLABS) return "At most $MAX_SLABS price ranges"
        if (slabs.first().fromHour != 0) return "The first range must start at 0 hours"
        slabs.forEachIndexed { i, s ->
            val n = i + 1
            if (i > 0 && s.fromHour != slabs[i - 1].toHour) return "Range $n must start at ${slabs[i - 1].toHour} hours"
            if (s.toHour <= s.fromHour) return "Range $n: \"to\" hours must be more than ${s.fromHour}"
            if (s.toHour > MAX_HOUR) return "Range $n: at most $MAX_HOUR hours"
            if (s.rate.signum() < 0) return "Range $n: the price cannot be negative"
        }
        return null
    }

    fun toJson(slabs: List<RateSlab>): String = buildJsonArray {
        slabs.forEach { s ->
            add(buildJsonObject {
                put("from_hour", s.fromHour)
                put("to_hour", s.toHour)
                put("rate", s.rate.money().toPlainString())
            })
        }
    }.toString()

    // ---- Editor drafts --------------------------------------------------------------------------

    /** A row as typed on the Settings screen: hours and price may still be incomplete text. */
    data class DraftRow(val from: String, val to: String, val rate: String)

    /** Reads saved or draft JSON (numbers or strings); null when it is not a JSON list of rows. */
    fun parseDraft(text: String): List<DraftRow>? = runCatching {
        json.parseToJsonElement(text).jsonArray.map { e ->
            val o = e.jsonObject
            DraftRow(o.text("from_hour"), o.text("to_hour"), o.text("rate"))
        }
    }.getOrNull()

    fun draftToJson(rows: List<DraftRow>): String = JsonArray(rows.map { r ->
        JsonObject(mapOf("from_hour" to number(r.from), "to_hour" to number(r.to), "rate" to JsonPrimitive(r.rate)))
    }).toString()

    /** Error for the editor, or null when the draft is a valid tariff. */
    fun draftProblem(text: String): String? {
        val rows = parseDraft(text) ?: return "Not a valid price list"
        rows.forEachIndexed { i, r ->
            val n = i + 1
            if (r.to.trim().toIntOrNull() == null) return "Range $n: enter the \"to\" hours as a whole number"
            val rate = r.rate.trim().toBigDecimalOrNull() ?: return "Range $n: enter the price"
            if (rate.scale() > 2) return "Range $n: at most 2 decimals in the price"
        }
        val slabs = rows.map { RateSlab(it.from.trim().toIntOrNull() ?: -1, it.to.trim().toInt(), it.rate.trim().toBigDecimal()) }
        return problem(slabs)
    }

    /** Draft rows after editing: every row starts where the previous one ends. */
    fun chain(rows: List<DraftRow>): List<DraftRow> {
        var from = "0"
        return rows.map { r -> r.copy(from = from).also { from = it.to.trim() } }
    }

    /** A new row after [rows]: same length and price as the last one. */
    fun addRow(rows: List<DraftRow>): List<DraftRow> {
        val last = rows.lastOrNull() ?: return listOf(DraftRow("0", "24", ""))
        val from = last.to.trim().toIntOrNull() ?: return rows + DraftRow(last.to, "", last.rate)
        val length = ((last.to.trim().toIntOrNull() ?: 0) - (last.from.trim().toIntOrNull() ?: 0)).coerceAtLeast(1)
        return rows + DraftRow(from.toString(), (from + length).toString(), last.rate)
    }

    /** Same tariff in a stable text form (for "changed?" checks and the confirm dialog). */
    fun canonical(text: String): String = parse(text)?.let { toJson(it) } ?: text

    // ---- Billing --------------------------------------------------------------------------------

    /**
     * How often each slab's rate is added for [billableMinutes] (minutes after grace). The list
     * matches [slabs]; the last entry includes the repeats. The total is days_charged.
     */
    fun counts(slabs: List<RateSlab>, billableMinutes: Long): List<Int> {
        val m = billableMinutes.coerceAtLeast(0)
        val started = slabs.indices.map { i -> if (i == 0 || m > slabs[i].fromHour * 60L) 1 else 0 }.toMutableList()
        val last = slabs.last()
        val lastEnd = last.toHour * 60L
        if (m > lastEnd) {
            val len = last.hours * 60L
            started[started.lastIndex] += ((m - lastEnd + len - 1) / len).toInt()
        }
        return started
    }

    /** The same counts rebuilt from a saved bill's days_charged (started slabs are always a prefix). */
    fun countsFromDays(slabs: List<RateSlab>, days: Int): List<Int> = slabs.indices.map { i ->
        when {
            i < slabs.lastIndex -> if (i < days) 1 else 0
            else -> (days - slabs.lastIndex).coerceAtLeast(0)
        }
    }

    /** Per-bag rent for the counts. */
    fun perBag(slabs: List<RateSlab>, counts: List<Int>): BigDecimal =
        slabs.zip(counts).fold(BigDecimal.ZERO) { acc, (s, c) -> acc + s.rate * BigDecimal(c) }

    /** Breakup lines: one per charged slab, and one for the repeats of the last slab. */
    fun charges(slabs: List<RateSlab>, counts: List<Int>): List<SlabCharge> = slabs.zip(counts).flatMapIndexed { i, (s, c) ->
        when {
            c == 0 -> emptyList()
            i < slabs.lastIndex -> listOf(SlabCharge(s, 1, false))
            c == 1 -> listOf(SlabCharge(s, 1, false))
            else -> listOf(SlabCharge(s, 1, false), SlabCharge(s, c - 1, true))
        }
    }

    /** "0-24 h" for a slab, or "each further 24 h" for repeats of the last one. */
    fun label(c: SlabCharge): String =
        if (c.isRepeat) "Next ${c.times} x ${c.slab.hours} h" else "${c.slab.fromHour}-${c.slab.toHour} h"

    /** Short text for the receipt / dashboard, e.g. "0-24 h Rs.30, 24-48 h Rs.10, then Rs.10 per 24 h". */
    fun describe(slabs: List<RateSlab>, currency: String = "Rs."): String {
        fun amt(a: BigDecimal): String {
            val plain = a.stripTrailingZeros()
            return currency + if (plain.scale() <= 0) plain.toPlainString() else a.money().toPlainString()
        }
        val last = slabs.last()
        return slabs.joinToString(", ") { "${it.fromHour}-${it.toHour} h ${amt(it.rate)}" } +
            ", then ${amt(last.rate)} per ${last.hours} h (per bag)"
    }

    private fun JsonObject.text(key: String): String {
        val p = this[key]?.jsonPrimitive ?: return ""
        return p.contentOrNull.orEmpty()
    }

    /** Hours go out as numbers when they are whole numbers, else as the typed text. */
    private fun number(text: String): JsonPrimitive = text.trim().toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(text)
}
