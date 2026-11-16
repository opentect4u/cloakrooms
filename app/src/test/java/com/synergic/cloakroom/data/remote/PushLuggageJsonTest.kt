package com.synergic.cloakroom.data.remote

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushLuggageJsonTest {

    private val row = PushLuggageDto(
        transId = "t1", localVersion = 1, receiptNo = "U02-00001", receiptDt = "2026-11-16", userIdIn = 2,
        deviceIdIn = "dev-A", dateTimeIn = "2026-11-16 05:00:00", pasnName = "Ravi", pasnMobNo = "9876543210",
        pnrNo = "P1", luggageQty = 2, outFlag = "N",
    )

    @Test
    fun `extra_lugage_qty is sent only when there are extra bags`() {
        // Without extra bags the row is exactly as before, so a server without the column takes it.
        assertFalse(ApiClient.json.encodeToString(row).contains("extra_lugage_qty"))
        assertTrue(ApiClient.json.encodeToString(row.copy(extraLugageQty = 3)).contains("\"extra_lugage_qty\":3"))
    }

    @Test
    fun `a pulled row without extra_lugage_qty reads as 0`() {
        val json = """{"trans_id":"t1","receipt_no":"U02-00001","receipt_dt":"2026-11-16","user_id_in":2,
            "date_time_in":"2026-11-16 05:00:00","pasn_name":"Ravi","pasn_mob_no":"9876543210","luggage_qty":2}"""
        assertTrue(ApiClient.json.decodeFromString<PullLuggageDto>(json).extraLugageQty == 0)
        assertTrue(ApiClient.json.decodeFromString<PullLuggageDto>(json.replace("}", ",\"extra_lugage_qty\":4}")).extraLugageQty == 4)
    }
}
