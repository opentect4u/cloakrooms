package com.synergic.cloakroom.domain.checkin

import com.synergic.cloakroom.domain.settings.AppSettings

/** What the operator typed on the Check-in screen. [idProofId] is null when no ID type is chosen. */
data class CheckinInput(
    val pnr: String,
    val name: String,
    val mobile: String,
    val bags: String,
    val idProofId: Long?,
    val idNumber: String,
    /** The chosen ID proof type has `mask_flag = Y` (e.g. Aadhaar; set by the super admin). */
    val idProofMasked: Boolean = false,
    /** Extra bags stored free of charge; empty = 0. */
    val extraBags: String = "",
)

/** Cleaned values ready to save. [idNumber] is already masked (ID_MASK_FLAG = Y or the type's mask flag). */
data class CheckinData(
    val pnr: String,
    val name: String,
    val mobile: String,
    val bags: Int,
    /** 0 when no ID proof was given (ID_PROOF_REQUIRED = N); the column is NOT NULL. */
    val idProofId: Long,
    val idNumber: String,
    /** Extra bags, not billed (td_luggage_in_out.extra_lugage_qty). */
    val extraBags: Int = 0,
)

enum class CheckinField { PNR, NAME, MOBILE, BAGS, EXTRA_BAGS, ID_PROOF, ID_NUMBER }

sealed interface CheckinValidation {
    data class Valid(val data: CheckinData) : CheckinValidation

    /** [errors] maps each wrong field to the message shown under it. */
    data class Invalid(val errors: Map<CheckinField, String>) : CheckinValidation
}

/** Check-in rules from CLAUDE.md; required fields, limits and masking come from [AppSettings]. */
object CheckinValidator {

    const val NO_ID_PROOF = 0L

    /** Indian mobile numbers: 10 digits, starting with 6, 7, 8 or 9. */
    private val MOBILE = Regex("^[6-9][0-9]{9}$")
    private val PNR = Regex("^[A-Za-z0-9]{1,20}$")
    private const val MAX_NAME = 200
    private const val MAX_ID = 50
    private const val MASK_KEEP = 4

    fun validate(input: CheckinInput, settings: AppSettings): CheckinValidation {
        val errors = LinkedHashMap<CheckinField, String>()

        val pnr = input.pnr.trim().uppercase()
        when {
            pnr.isEmpty() && settings.pnrRequired -> errors[CheckinField.PNR] = "Enter the PNR."
            pnr.isNotEmpty() && !PNR.matches(pnr) -> errors[CheckinField.PNR] = "PNR can have only letters and digits (up to 20)."
        }

        val name = input.name.trim().replace(Regex("\\s+"), " ")
        when {
            name.isEmpty() -> errors[CheckinField.NAME] = "Enter the passenger name."
            name.length > MAX_NAME -> errors[CheckinField.NAME] = "Name is too long."
        }

        val mobile = input.mobile.filter { !it.isWhitespace() }.removePrefix("+91")
        if (!MOBILE.matches(mobile)) errors[CheckinField.MOBILE] = "Enter a valid 10-digit mobile number."

        val bags = input.bags.trim().toIntOrNull()
        when {
            bags == null || bags < 1 -> errors[CheckinField.BAGS] = "Enter the number of bags (at least 1)."
            bags > settings.maxBagsPerReceipt ->
                errors[CheckinField.BAGS] = "At most ${settings.maxBagsPerReceipt} bags per receipt."
        }

        // Extra bags are free; the same per-receipt limit applies to them.
        val extraText = input.extraBags.trim()
        val extraBags = if (extraText.isEmpty()) 0 else extraText.toIntOrNull()
        when {
            extraBags == null || extraBags < 0 -> errors[CheckinField.EXTRA_BAGS] = "Enter the number of extra bags (0 or more)."
            extraBags > settings.maxBagsPerReceipt ->
                errors[CheckinField.EXTRA_BAGS] = "At most ${settings.maxBagsPerReceipt} extra bags per receipt."
        }

        val mask = settings.idMaskFlag || input.idProofMasked
        val idNumber = input.idNumber.filter { !it.isWhitespace() && it != '-' }.uppercase()
        val hasType = input.idProofId != null && input.idProofId != NO_ID_PROOF
        when {
            settings.idProofRequired && !hasType -> errors[CheckinField.ID_PROOF] = "Choose the ID proof type."
            !hasType && idNumber.isNotEmpty() -> errors[CheckinField.ID_PROOF] = "Choose the ID proof type."
        }
        when {
            (settings.idProofRequired || hasType) && idNumber.isEmpty() -> errors[CheckinField.ID_NUMBER] = "Enter the ID number."
            idNumber.length > MAX_ID -> errors[CheckinField.ID_NUMBER] = "ID number is too long."
            mask && idNumber.isNotEmpty() && idNumber.length < MASK_KEEP ->
                errors[CheckinField.ID_NUMBER] = "ID number must have at least $MASK_KEEP characters."
        }

        if (errors.isNotEmpty()) return CheckinValidation.Invalid(errors)
        return CheckinValidation.Valid(
            CheckinData(
                pnr = pnr,
                name = name,
                mobile = mobile,
                bags = bags!!,
                idProofId = if (hasType) input.idProofId!! else NO_ID_PROOF,
                idNumber = if (mask) maskId(idNumber) else idNumber,
                extraBags = extraBags!!,
            )
        )
    }

    /** Keeps only the last 4 characters: "123412341234" → "XXXX1234". Empty stays empty. */
    fun maskId(idNumber: String): String =
        if (idNumber.isEmpty()) "" else "XXXX" + idNumber.takeLast(MASK_KEEP)
}
