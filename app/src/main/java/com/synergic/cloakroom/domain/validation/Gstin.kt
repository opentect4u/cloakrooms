package com.synergic.cloakroom.domain.validation

/** Indian GSTIN check, shared by Admin → Masters → Cloak room and Super Admin → customer form. */
object Gstin {
    /** State code, PAN (5 letters, 4 digits, 1 letter), entity number, 'Z', check character. */
    private val PATTERN = Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$")

    const val FORMAT_MESSAGE =
        "Not a valid GSTIN. Format: 2-digit state code, 5 letters, 4 digits, 1 letter, 1 digit or letter, Z, " +
            "1 digit or letter — e.g. 19ABCDE1234F1Z5."

    /** [gst] in capitals, without spaces. */
    fun isValid(gst: String): Boolean = PATTERN.matches(gst)
}
