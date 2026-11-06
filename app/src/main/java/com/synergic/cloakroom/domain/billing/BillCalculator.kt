package com.synergic.cloakroom.domain.billing

import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.settings.GstType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/**
 * The bill and the snapshot saved on td_receipt. Every amount has 2 decimals.
 * [blocks] is what td_receipt.hours_charged stores.
 */
data class BillResult(
    /** Whole minutes between check-in and check-out (seconds are dropped). */
    val minutesStored: Long,
    /** [minutesStored] minus the grace minutes, not below 0. */
    val minutesCharged: Long,
    val blocks: Int,
    val bags: Int,
    val firstBlockRate: BigDecimal,
    val nextBlockRate: BigDecimal,
    val blockHours: Int,
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
    /** Total amount collected from the passenger. */
    val netAmt: BigDecimal,
) {
    val gstAmt: BigDecimal get() = cgst + sgst + igst
}

/** The inputs cannot be billed, e.g. a penalty above PENALTY_MAX_AMT. [message] is shown on screen. */
class BillCalculationException(message: String) : IllegalArgumentException(message)

/**
 * Bill formula from CLAUDE.md. Rates, block length, grace, penalty limits and GST all come from
 * the [AppSettings] snapshot; nothing is hard-coded.
 *
 * ```
 * minutes  = (out − in) in minutes − GRACE_MINUTES (not below 0)
 * blocks   = max(1, ceil(minutes / (BLOCK_HOURS × 60)))
 * base_amt = bags × (FIRST_BLOCK_RATE + NEXT_BLOCK_RATE × (blocks − 1))
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
    ): BillResult {
        if (bags < 1) throw BillCalculationException("Number of bags must be at least 1.")
        if (settings.blockHours < 1) throw BillCalculationException("Block length must be at least 1 hour.")
        val penaltyAmt = checkPenalty(penalty, settings)

        // A check-out clock earlier than check-in (wrong device time) counts as 0 minutes.
        val minutesStored = Duration.between(checkIn, checkOut).toMinutes().coerceAtLeast(0)
        val minutesCharged = (minutesStored - settings.graceMinutes).coerceAtLeast(0)
        val blockMinutes = settings.blockHours * 60L
        val blocks = maxOf(1L, (minutesCharged + blockMinutes - 1) / blockMinutes).toInt()

        val perBag = settings.firstBlockRate + settings.nextBlockRate * BigDecimal(blocks - 1)
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
            firstBlockRate = settings.firstBlockRate.money(),
            nextBlockRate = settings.nextBlockRate.money(),
            blockHours = settings.blockHours,
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
        )
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
