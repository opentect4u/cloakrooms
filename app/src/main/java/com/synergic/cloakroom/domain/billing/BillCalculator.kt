package com.synergic.cloakroom.domain.billing

import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.settings.GstType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/**
 * The bill and the snapshot saved on td_receipt. Every amount has 2 decimals.
 * [blocks] is what td_receipt.days_charged stores (how many slab rates were added per bag).
 */
data class BillResult(
    /** Whole minutes between check-in and check-out (seconds are dropped). */
    val minutesStored: Long,
    /** [minutesStored] minus the grace minutes, not below 0. */
    val minutesCharged: Long,
    val blocks: Int,
    val bags: Int,
    /** The tariff billed with (td_receipt.rate_slabs). */
    val slabs: List<RateSlab>,
    /** How often each of [slabs] was charged per bag (same order; the last includes repeats). */
    val slabCounts: List<Int>,
    val baseAmt: BigDecimal,
    val penaltyAmt: BigDecimal,
    val gstFlag: Boolean,
    val gstPct: BigDecimal,
    val gstType: GstType,
    val gstInclusive: Boolean,
    /** GRACE_MINUTES and PENALTY_GST_FLAG billed with (sent with the bill, §7). */
    val graceMinutes: Int,
    val penaltyGstFlag: Boolean,
    /** Value GST was worked out on (the GST base without GST). */
    val taxableAmt: BigDecimal,
    val cgst: BigDecimal,
    val sgst: BigDecimal,
    val igst: BigDecimal,
    /** The full bill (rent + penalty + GST added on top). */
    val netAmt: BigDecimal,
    /** Advance paid at check-in (td_luggage_in_out.adv_amt), subtracted from [netAmt]. */
    val advAmt: BigDecimal = ZERO_MONEY,
) {
    val gstAmt: BigDecimal get() = cgst + sgst + igst

    /** Collected at check-out: max(0, net − advance). No refund when the advance is larger. */
    val balAmt: BigDecimal get() = (netAmt - advAmt).max(BigDecimal.ZERO).money()

    /** Breakup lines (one per charged slab, plus the repeats of the last one). */
    val charges: List<SlabCharge> get() = RateSlabs.charges(slabs, slabCounts)

    /** The older two-rate snapshot columns, kept for older readers: first slab length and rates. */
    val blockHours: Int get() = slabs.first().hours
    val firstBlockRate: BigDecimal get() = slabs.first().rate.money()
    val nextBlockRate: BigDecimal get() = (slabs.getOrNull(1) ?: slabs.first()).rate.money()
}

/** The inputs cannot be billed, e.g. a penalty above PENALTY_MAX_AMT. [message] is shown on screen. */
class BillCalculationException(message: String) : IllegalArgumentException(message)

/**
 * Bill formula of API_GUIDE.md §7. The price ranges (rate slabs), grace, penalty limits and GST
 * all come from the [AppSettings] snapshot; nothing is hard-coded.
 *
 * ```
 * minutes  = (out − in) in minutes − GRACE_MINUTES (not below 0)
 * per bag  = Σ rate of every started slab (the first always; a later one once minutes > from_hour × 60)
 *          + last.rate × ceil((minutes − last.to_hour × 60) / ((last.to − last.from) × 60))  when past the last slab
 * blocks   = how many rates were added
 * base_amt = bags × per bag
 * ```
 */
object BillCalculator {

    private val HUNDRED = BigDecimal(100)

    fun calculate(
        checkIn: Instant,
        checkOut: Instant,
        bags: Int,
        penalty: BigDecimal,
        settings: AppSettings,
        advance: BigDecimal = ZERO_MONEY,
    ): BillResult {
        if (bags < 1) throw BillCalculationException("Number of bags must be at least 1.")
        val slabs = settings.rateSlabs
        RateSlabs.problem(slabs)?.let { throw BillCalculationException("The price list is not valid: $it.") }
        val penaltyAmt = checkPenalty(penalty, settings)

        // A check-out clock earlier than check-in (wrong device time) counts as 0 minutes.
        val minutesStored = Duration.between(checkIn, checkOut).toMinutes().coerceAtLeast(0)
        val minutesCharged = (minutesStored - settings.graceMinutes).coerceAtLeast(0)
        val counts = RateSlabs.counts(slabs, minutesCharged)
        val blocks = counts.sum()

        val perBag = RateSlabs.perBag(slabs, counts)
        val baseAmt = (perBag * BigDecimal(bags)).money()

        val gstOn = settings.gstFlag
        val gstPct = if (gstOn) settings.gstPct.money() else ZERO_MONEY
        val gross = (baseAmt + penaltyAmt).money()
        val gstBase = if (settings.penaltyGstFlag) gross else baseAmt

        val taxable: BigDecimal
        val gst: BigDecimal
        val net: BigDecimal
        when {
            !gstOn -> {
                taxable = gross
                gst = ZERO_MONEY
                net = gross
            }
            settings.gstInclusive -> {
                taxable = (gstBase * HUNDRED).divide(HUNDRED + gstPct, 2, RoundingMode.HALF_UP)
                gst = (gstBase - taxable).money()
                net = gross
            }
            else -> {
                taxable = gstBase
                gst = (gstBase * gstPct).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                net = (gross + gst).money()
            }
        }

        val (cgst, sgst, igst) = when (settings.gstType) {
            GstType.CGST_SGST -> {
                val c = gst.divide(BigDecimal(2), 2, RoundingMode.HALF_UP)
                Triple(c, (gst - c).money(), ZERO_MONEY)
            }
            GstType.IGST -> Triple(ZERO_MONEY, ZERO_MONEY, gst)
        }

        return BillResult(
            minutesStored = minutesStored,
            minutesCharged = minutesCharged,
            blocks = blocks,
            bags = bags,
            slabs = slabs.map { it.copy(rate = it.rate.money()) },
            slabCounts = counts,
            baseAmt = baseAmt,
            penaltyAmt = penaltyAmt,
            gstFlag = gstOn,
            gstPct = gstPct,
            gstType = settings.gstType,
            gstInclusive = settings.gstInclusive,
            graceMinutes = settings.graceMinutes,
            penaltyGstFlag = settings.penaltyGstFlag,
            taxableAmt = taxable.money(),
            cgst = cgst,
            sgst = sgst,
            igst = igst,
            netAmt = net,
            advAmt = advance.money().max(ZERO_MONEY),
        )
    }

    /**
     * Advance taken at check-in (API_GUIDE.md §7): the bill for ADVANCE_HOURS with the same slabs
     * and GST setting, without penalty. Zero when ADVANCE_FLAG = N.
     */
    fun advance(checkIn: Instant, bags: Int, settings: AppSettings): BigDecimal {
        if (!settings.advanceFlag || settings.advanceHours < 1) return ZERO_MONEY
        // Grace minutes are added back, so exactly ADVANCE_HOURS are charged.
        val out = checkIn.plus(Duration.ofHours(settings.advanceHours.toLong())).plus(Duration.ofMinutes(settings.graceMinutes.toLong()))
        return calculate(checkIn, out, bags, ZERO_MONEY, settings).netAmt
    }

    /** 0 when PENALTY_FLAG = N; otherwise 0 ≤ penalty ≤ PENALTY_MAX_AMT. */
    fun checkPenalty(penalty: BigDecimal, settings: AppSettings): BigDecimal {
        val amount = penalty.money()
        if (amount.signum() < 0) throw BillCalculationException("Penalty cannot be negative.")
        if (!settings.penaltyFlag) {
            if (amount.signum() != 0) throw BillCalculationException("Penalty is not allowed.")
            return ZERO_MONEY
        }
        if (amount > settings.penaltyMaxAmt.money()) {
            throw BillCalculationException("Penalty cannot be more than ₹${settings.penaltyMaxAmt.money().toPlainString()}.")
        }
        return amount
    }
}
