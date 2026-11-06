package com.synergic.cloakroom.data.numbering

import android.app.Application
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.TestEnv
import com.synergic.cloakroom.data.db.entity.ReceiptEntity
import com.synergic.cloakroom.domain.numbering.CounterType
import com.synergic.cloakroom.domain.settings.ReceiptReset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class UserCounterSequenceTest {

    private lateinit var env: TestEnv
    private lateinit var sequence: UserCounterSequence
    private val nov2 = Instant.parse("2026-11-02T06:30:00Z")

    @Before
    fun setUp() {
        env = TestEnv()
        sequence = UserCounterSequence(env.db)
    }

    @After
    fun tearDown() = env.close()

    private fun next(
        userId: Long = 2,
        code: String = "U02",
        type: CounterType = CounterType.RECEIPT,
        reset: ReceiptReset = ReceiptReset.FINANCIAL_YEAR,
        at: Instant = nov2,
    ) = runBlocking { sequence.next(userId, code, type, reset, at, "user1").formatted }

    @Test
    fun `numbers go up by one per user and series`() {
        assertEquals("U02-00001", next())
        assertEquals("U02-00002", next())
        assertEquals("U02-00001", next(type = CounterType.BILL))
        assertEquals("U01-00001", next(userId = 1, code = "U01"))
        assertEquals("U02-00003", next())
    }

    @Test
    fun `counter row is stored with period key and sync stamp`() = runBlocking {
        next()
        next()
        val row = env.db.userCounterDao().get(Tenant.CUSTOMER_ID, 2, "R", "FY2026-27")!!
        assertEquals(2, row.lastNo)
        assertEquals("N", row.syncFlag)
        assertEquals(2, row.localVersion)
        assertEquals("user1", row.updatedBy)
    }

    @Test
    fun `daily reset restarts on the next local day`() {
        assertEquals("U02-00001", next(reset = ReceiptReset.DAILY))
        assertEquals("U02-00002", next(reset = ReceiptReset.DAILY))
        // 18:30 UTC on 2 Nov is 00:00 IST on 3 Nov.
        assertEquals("U02-00001", next(reset = ReceiptReset.DAILY, at = Instant.parse("2026-11-02T18:30:00Z")))
    }

    @Test
    fun `financial year reset restarts on 1 April`() {
        assertEquals("U02-00001", next(at = Instant.parse("2027-03-31T10:00:00Z")))
        assertEquals("U02-00002", next(at = Instant.parse("2027-03-31T11:00:00Z")))
        assertEquals("U02-00001", next(at = Instant.parse("2027-04-01T10:00:00Z")))
    }

    @Test
    fun `never reset keeps counting`() {
        assertEquals("U02-00001", next(reset = ReceiptReset.NEVER, at = Instant.parse("2026-01-01T10:00:00Z")))
        assertEquals("U02-00002", next(reset = ReceiptReset.NEVER, at = Instant.parse("2030-01-01T10:00:00Z")))
    }

    @Test
    fun `failed insert in the same transaction gives the number back`() = runBlocking {
        assertEquals("U02-00001", next())
        runCatching {
            env.db.withTransaction {
                sequence.next(2, "U02", CounterType.RECEIPT, ReceiptReset.FINANCIAL_YEAR, nov2, "user1")
                error("Insert failed")
            }
        }
        assertEquals("U02-00002", next())
    }

    @Test
    fun `numbers never collide when many are made at once`() = runBlocking {
        val numbers = (1..20).map {
            async(Dispatchers.Default) {
                sequence.next(2, "U02", CounterType.RECEIPT, ReceiptReset.FINANCIAL_YEAR, nov2, "user1").number
            }
        }.awaitAll()
        assertEquals((1..20).toSet(), numbers.toSet())
    }
}
