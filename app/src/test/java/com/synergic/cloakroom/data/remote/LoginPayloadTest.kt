package com.synergic.cloakroom.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The login reply as the dev server sent it on 2026-11-04 (admin of customer 3), trimmed. */
class LoginPayloadTest {

    private val reply = """
        {"accessToken":"a","refreshToken":"r",
         "user":{"id":7,"customer_id":3,"user_id":"sadmin","user_name":"Synergic Admin","user_code":"SSS","user_type":"A","allow_flag":"Y","must_change_pwd":"N"},
         "customer":{"customer_id":3,"customer_name":"Synergic","user_limit":5},
         "masters":{
          "id_proofs":[{"id_proof_id":1,"id_proof_name":"Aadhaar Card","mask_flag":"Y","is_deleted":"N","sync_flag":"N","created_by":"system","created_at":"2026-11-02 10:17:22","updated_by":null,"updated_at":null}],
          "gst":[{"gst_id":1,"cgst_pct":"9.00","sgst_pct":"9.00","igst_pct":"0.00","effective_from":"2017-07-01","is_deleted":"N","sync_flag":"N"}],
          "rates":[{"rate_id":5,"customer_id":3,"block_hours":24,"first_block_rate":"30.00","next_block_rate":"10.00","effective_from":"2026-11-04","is_deleted":"N"}],
          "shifts":[{"shift_id":4,"customer_id":3,"shift_name":"Day","from_time":"06:00:00","to_time":"13:00:00","is_deleted":"N"}],
          "shift_assignments":[],
          "header_footer":[{"id":2,"customer_id":3,"line_type":"H","line_order":1,"narration":"Synergic Header 2","image":null,"in_flag":"Y","out_flag":"Y","report_flag":"Y","is_deleted":"N"}],
          "users":[{"id":7,"customer_id":3,"user_code":"SSS","user_name":"Synergic Admin","user_type":"A","allow_flag":"Y","is_deleted":"N","updated_at":"2026-11-04 08:59:47"}]},
         "settings":{"REPORT_FLAG":"N","GST_TYPE":"E","PAPER_WIDTH":"58","SYNC_INTERVAL_MIN":"15"},
         "counters":{"reset":"Y","period_key":"FY2026-27","receipt_last_no":0,"bill_last_no":0},
         "server_time":"2026-11-04 09:50:00"}
    """.trimIndent()

    @Test
    fun realLoginReplyParses() {
        val data = ApiClient.json.decodeFromString(LoginDataDto.serializer(), reply)
        val m = data.masters!!
        assertEquals("Y", m.idProofs.single().maskFlag)
        assertEquals("9.00", m.gst.single().cgstPct)
        assertEquals("30.00", m.rates.single().firstBlockRate)
        assertEquals("06:00:00", m.shifts.single().fromTime)
        assertEquals(0, m.shiftAssignments.size)
        assertNull(m.headerFooter.single().image)
        assertEquals("SSS", m.users.single().userCode)
        assertEquals("E", data.settings!!["GST_TYPE"])
        assertEquals("FY2026-27", data.counters!!.periodKey)
    }
}
