package com.synergic.cloakroom.data.print

import com.synergic.cloakroom.domain.print.PrintResult

/** One way of reaching a physical ESC/POS printer (Bluetooth, or the POS's built-in serial or USB printer). */
interface PrinterPort {
    /** True when this kind of printer seems to be on this device. Quick, and never throws. */
    fun isPresent(): Boolean

    /**
     * When [isPresent] is false only because the user must do something first (e.g. allow a
     * permission), what to tell them. Null otherwise.
     */
    fun unavailableReason(): String? = null

    /** Sends ready ESC/POS bytes and waits until they are printed (or the print fails). */
    suspend fun send(data: ByteArray): PrintResult
}
