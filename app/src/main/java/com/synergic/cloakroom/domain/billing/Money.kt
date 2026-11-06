package com.synergic.cloakroom.domain.billing

import java.math.BigDecimal
import java.math.RoundingMode

/** All amounts are rounded HALF_UP to 2 decimals (paise). */
fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

/** Converts a stored REAL back to an exact 2-decimal amount (e.g. 59.32 stays 59.32, not 59.3199…). */
fun Double.toMoney(): BigDecimal = BigDecimal.valueOf(this).money()

val ZERO_MONEY: BigDecimal = BigDecimal.ZERO.money()
