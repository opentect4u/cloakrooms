package com.synergic.cloakroom.ui.components

/** "2 bag(s)", or "2 bag(s) + 1 extra (free)" when there are free extra bags (not billed). */
fun bagText(bags: Int, extraBags: Int): String =
    "$bags bag(s)" + if (extraBags > 0) " + $extraBags extra (free)" else ""
