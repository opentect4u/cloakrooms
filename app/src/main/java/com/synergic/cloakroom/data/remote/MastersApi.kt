package com.synergic.cloakroom.data.remote

import com.synergic.cloakroom.domain.billing.RateSlab
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Customer masters and settings (API_GUIDE.md §5 and "Customer masters as super admin (S)").
 * [customerId] is required for a super admin: lists filter by it and creates send it in the body.
 * A station admin passes null (the server takes the customer from the token). PATCH and DELETE
 * address the row by id and never send customer_id.
 */
@Singleton
class MastersApi @Inject constructor(private val api: ApiClient) {

    private fun listQuery(customerId: Long?, vararg extra: Pair<String, Any?>) =
        mapOf("customer_id" to customerId, "limit" to MAX_LIMIT) + extra

    private fun create(customerId: Long?, fields: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        if (customerId != null) put("customer_id", customerId)
        fields()
    }

    // ---- Own cloak room (station admin) -------------------------------------------------------

    /**
     * PATCH /customers/me (role A): partial update of the own cloak room, [fields] = only the
     * changed ones (at least one). Returns the updated customer.
     */
    suspend fun updateOwnCustomer(fields: Map<String, String>): CustomerDto =
        api.patch("/customers/me", buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }, CustomerDto.serializer())

    // ---- Rates --------------------------------------------------------------------------------

    suspend fun listRates(customerId: Long?): List<RateDto> =
        api.getPage("/rates", RateDto.serializer(), listQuery(customerId, "sort" to "effective_from:desc")).items

    /** POST /rates with `rate_slabs` only (sending the older two-rate fields too returns 400). */
    suspend fun createRate(customerId: Long?, slabs: List<RateSlab>, effectiveFrom: String): RateDto =
        api.post("/rates", create(customerId) {
            put("rate_slabs", slabsJson(slabs))
            put("effective_from", effectiveFrom)
        }, RateDto.serializer())

    suspend fun updateRate(id: Long, slabs: List<RateSlab>, effectiveFrom: String): RateDto =
        api.patch("/rates/$id", buildJsonObject {
            put("rate_slabs", slabsJson(slabs))
            put("effective_from", effectiveFrom)
        }, RateDto.serializer())

    private fun slabsJson(slabs: List<RateSlab>) =
        ApiClient.json.encodeToJsonElement(ListSerializer(RateSlabDto.serializer()), slabs.map { RateSlabDto.of(it) })

    suspend fun deleteRate(id: Long) = api.delete("/rates/$id")

    // ---- GST (shared by every customer; read only here) ----------------------------------------

    suspend fun listGst(): List<GstDto> =
        api.getPage("/gst", GstDto.serializer(), mapOf("limit" to MAX_LIMIT, "sort" to "effective_from:desc")).items

    // ---- Shifts -------------------------------------------------------------------------------

    suspend fun listShifts(customerId: Long?): List<ShiftDto> =
        api.getPage("/shifts", ShiftDto.serializer(), listQuery(customerId, "sort" to "from_time:asc")).items

    suspend fun createShift(customerId: Long?, name: String, fromTime: String, toTime: String): ShiftDto =
        api.post("/shifts", create(customerId) {
            put("shift_name", name); put("from_time", fromTime); put("to_time", toTime)
        }, ShiftDto.serializer())

    suspend fun updateShift(id: Long, name: String, fromTime: String, toTime: String): ShiftDto =
        api.patch("/shifts/$id", buildJsonObject {
            put("shift_name", name); put("from_time", fromTime); put("to_time", toTime)
        }, ShiftDto.serializer())

    suspend fun deleteShift(id: Long) = api.delete("/shifts/$id")

    // ---- Shift assignments --------------------------------------------------------------------

    suspend fun listShiftAssignments(customerId: Long?): List<ShiftAssignmentDto> =
        api.getPage("/shift-assignments", ShiftAssignmentDto.serializer(), listQuery(customerId, "sort" to "effective_from:desc")).items

    suspend fun createShiftAssignment(customerId: Long?, userId: Long, shiftId: Long, effectiveFrom: String): ShiftAssignmentDto =
        api.post("/shift-assignments", create(customerId) {
            put("user_id", userId); put("shift_id", shiftId); put("effective_from", effectiveFrom)
        }, ShiftAssignmentDto.serializer())

    /** Only shift_id and effective_from can change. */
    suspend fun updateShiftAssignment(id: Long, shiftId: Long, effectiveFrom: String): ShiftAssignmentDto =
        api.patch("/shift-assignments/$id", buildJsonObject {
            put("shift_id", shiftId); put("effective_from", effectiveFrom)
        }, ShiftAssignmentDto.serializer())

    suspend fun deleteShiftAssignment(id: Long) = api.delete("/shift-assignments/$id")

    // ---- Header / footer ----------------------------------------------------------------------

    suspend fun listHeaderFooter(customerId: Long?): List<HeaderFooterDto> =
        api.getPage("/header-footer", HeaderFooterDto.serializer(), listQuery(customerId, "sort" to "line_order:asc")).items

    suspend fun createLine(
        customerId: Long?, lineType: String, lineOrder: Int, narration: String, inFlag: Boolean, outFlag: Boolean, reportFlag: Boolean,
    ): HeaderFooterDto = api.post("/header-footer", create(customerId) {
        put("line_type", lineType); put("line_order", lineOrder); put("narration", narration)
        put("in_flag", yn(inFlag)); put("out_flag", yn(outFlag)); put("report_flag", yn(reportFlag))
    }, HeaderFooterDto.serializer())

    /** line_type cannot change. */
    suspend fun updateLine(id: Long, lineOrder: Int, narration: String, inFlag: Boolean, outFlag: Boolean, reportFlag: Boolean): HeaderFooterDto =
        api.patch("/header-footer/$id", buildJsonObject {
            put("line_order", lineOrder); put("narration", narration)
            put("in_flag", yn(inFlag)); put("out_flag", yn(outFlag)); put("report_flag", yn(reportFlag))
        }, HeaderFooterDto.serializer())

    suspend fun deleteLine(id: Long) = api.delete("/header-footer/$id")

    /**
     * POST /header-footer/logo (multipart): creates or replaces the customer's logo. PNG only,
     * at most 200 KB; customer_id as a text field for a super admin.
     */
    suspend fun uploadLogo(customerId: Long?, png: ByteArray, inFlag: Boolean, outFlag: Boolean, reportFlag: Boolean): HeaderFooterDto =
        api.postMultipart(
            "/header-footer/logo",
            buildMap {
                if (customerId != null) put("customer_id", customerId.toString())
                put("in_flag", yn(inFlag)); put("out_flag", yn(outFlag)); put("report_flag", yn(reportFlag))
            },
            fileField = "logo", fileName = "logo.png", bytes = png, mediaType = "image/png",
            serializer = HeaderFooterDto.serializer(),
        )

    /** PATCH /header-footer/:id with only the print switches (the logo row has no text). */
    suspend fun updateLineFlags(id: Long, inFlag: Boolean, outFlag: Boolean, reportFlag: Boolean): HeaderFooterDto =
        api.patch("/header-footer/$id", buildJsonObject {
            put("in_flag", yn(inFlag)); put("out_flag", yn(outFlag)); put("report_flag", yn(reportFlag))
        }, HeaderFooterDto.serializer())

    /** PATCH /header-footer/:id line_order only (moving a line up or down). */
    suspend fun updateLineOrder(id: Long, lineOrder: Int): HeaderFooterDto =
        api.patch("/header-footer/$id", buildJsonObject { put("line_order", lineOrder) }, HeaderFooterDto.serializer())

    // ---- ID proof types (global; create/edit/delete: super admin only) -------------------------

    suspend fun listIdProofs(): List<IdProofDto> =
        api.getPage("/id-proofs", IdProofDto.serializer(), mapOf("limit" to MAX_LIMIT, "sort" to "id_proof_name:asc")).items

    suspend fun createIdProof(name: String, maskFlag: Boolean): IdProofDto =
        api.post("/id-proofs", buildJsonObject { put("id_proof_name", name); put("mask_flag", yn(maskFlag)) }, IdProofDto.serializer())

    suspend fun updateIdProof(id: Long, name: String): IdProofDto =
        api.patch("/id-proofs/$id", buildJsonObject { put("id_proof_name", name) }, IdProofDto.serializer())

    suspend fun deleteIdProof(id: Long) = api.delete("/id-proofs/$id")

    // ---- Settings (§8 keys) -------------------------------------------------------------------

    suspend fun listSettings(customerId: Long?): List<SettingDto> =
        api.getPage("/settings", SettingDto.serializer(), listQuery(customerId)).items

    suspend fun createSetting(customerId: Long?, name: String, value: String): SettingDto =
        api.post("/settings", create(customerId) { put("setting_name", name); put("setting_value", value) }, SettingDto.serializer())

    /** Only setting_value can change (validated against the row's key). */
    suspend fun updateSetting(id: Long, value: String): SettingDto =
        api.patch("/settings/$id", buildJsonObject { put("setting_value", value) }, SettingDto.serializer())

    private fun yn(b: Boolean) = if (b) "Y" else "N"

    private companion object {
        /** §2: lists allow at most 200 per page. */
        const val MAX_LIMIT = 200
    }
}
