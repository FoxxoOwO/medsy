package cz.medsy.domain.prescription

import cz.medsy.domain.message.plain
import cz.medsy.domain.message.plainArgs

import cz.medsy.domain.message.MessageArg
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.scanner.EReceptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The queue exists to answer one question: "do I still have time to collect this?" Days are
 * counted in calendar days, so an eRecept valid until 23:59 today is "expires today", not
 * "0.9 days".
 */
class PrescriptionExpiryTest {

    private fun textArg(message: cz.medsy.domain.message.Message, index: Int): String =
        message.args[index].plain()

    private val prague = ZoneId.of("Europe/Prague")
    private val now: Instant = Instant.parse("2026-06-01T10:00:00Z")

    private fun queued(
        validUntil: Instant?,
        dispensed: Boolean = false,
    ) = QueuedPrescription(
        id = 1,
        code = "ABCD1234EFGH",
        issuedAt = Instant.parse("2026-05-20T08:00:00Z"),
        validUntil = validUntil,
        dispensed = dispensed,
        items = emptyList(),
    )

    @Test
    fun `the default fourteen-day validity comes from the parser`() {
        val parsed = EReceptParser.parse("vystaveno:2026-05-20 code=ABCD1234EFGH")!!
        val item = QueuedPrescription(
            id = 1,
            code = parsed.code,
            issuedAt = parsed.issuedAt,
            validUntil = parsed.validUntil,
            dispensed = false,
            items = emptyList(),
        )

        assertEquals(14L, item.daysLeft(Instant.parse("2026-05-20T00:00:00Z"), prague))
    }

    @Test
    fun `a validity printed on the eRecept wins over the default`() {
        val parsed = EReceptParser.parse(
            "vystaveno:2026-05-20 platnost:2026-06-10 code=ABCD1234EFGH",
        )!!
        val item = QueuedPrescription(
            id = 1,
            code = parsed.code,
            issuedAt = parsed.issuedAt,
            validUntil = parsed.validUntil,
            dispensed = false,
            items = emptyList(),
        )

        assertEquals(21L, item.daysLeft(Instant.parse("2026-05-20T09:00:00Z"), prague))
    }

    @Test
    fun `validity is anchored to Czech local time, not the device zone`() {
        val parsed = EReceptParser.parse("vystaveno:2026-05-20 code=ABCD1234EFGH")!!

        assertEquals(Instant.parse("2026-05-19T22:00:00Z"), parsed.issuedAt)
        assertEquals(Instant.parse("2026-06-03T21:59:59Z"), parsed.validUntil)
    }

    @Test
    fun `an expired prescription is expired and never warns`() {
        val item = queued(Instant.parse("2026-05-30T21:59:59Z"))

        assertTrue(item.isExpired(now))
        assertFalse(item.isUsable(now))
        assertFalse(item.isExpiringSoon(now))
        assertEquals(MessageKey.RX_EXPIRED, PrescriptionQueue.describe(item, now).key)
    }

    @Test
    fun `two days left warns, three days does not`() {
        val twoDays = queued(Instant.parse("2026-06-03T21:59:59Z"))
        val threeDays = queued(Instant.parse("2026-06-04T21:59:59Z"))

        assertTrue(twoDays.isExpiringSoon(now))
        assertFalse(threeDays.isExpiringSoon(now))
        assertEquals(2L, twoDays.daysLeft(now, prague))
    }

    @Test
    fun `expiring today counts as zero days and still warns`() {
        val item = queued(Instant.parse("2026-06-01T21:59:59Z"))

        assertEquals(0L, item.daysLeft(now, prague))
        assertTrue(item.isExpiringSoon(now))
        assertEquals(MessageKey.RX_EXPIRES_TODAY, PrescriptionQueue.describe(item, now).key)
    }

    @Test
    fun `one day left reads as tomorrow`() {
        val item = queued(Instant.parse("2026-06-02T21:59:59Z"))

        assertEquals(MessageKey.RX_EXPIRES_TOMORROW, PrescriptionQueue.describe(item, now).key)
    }

    @Test
    fun `a dispensed prescription never warns`() {
        val item = queued(Instant.parse("2026-06-02T21:59:59Z"), dispensed = true)

        assertFalse(item.isExpiringSoon(now))
        assertFalse(item.isUsable(now))
        assertEquals(MessageKey.RX_DISPENSED, PrescriptionQueue.describe(item, now).key)
    }

    @Test
    fun `a prescription without an expiry never warns and says so`() {
        val item = queued(validUntil = null)

        assertNull(item.daysLeft(now))
        assertFalse(item.isExpiringSoon(now))
        assertEquals(MessageKey.RX_NO_EXPIRY, PrescriptionQueue.describe(item, now).key)
    }

    @Test
    fun `the badge counts only prescriptions inside the window`() {
        val items = listOf(
            queued(Instant.parse("2026-06-02T21:59:59Z")),
            queued(Instant.parse("2026-06-03T21:59:59Z")),
            queued(Instant.parse("2026-06-20T21:59:59Z")),
            queued(Instant.parse("2026-05-30T21:59:59Z")),
            queued(Instant.parse("2026-06-02T21:59:59Z"), dispensed = true),
        )

        assertEquals(2, PrescriptionQueue.badgeCount(items, now))
    }

    @Test
    fun `active drops expired and dispensed prescriptions`() {
        val items = listOf(
            queued(Instant.parse("2026-06-02T21:59:59Z")),
            queued(Instant.parse("2026-05-30T21:59:59Z")),
            queued(Instant.parse("2026-06-20T21:59:59Z"), dispensed = true),
        )

        assertEquals(listOf("ABCD1234EFGH"), PrescriptionQueue.active(items, now).map { it.code })
    }

    @Test
    fun `the reminder names the nearest expiring prescription`() {
        val message = PrescriptionQueue.reminderMessage(
            listOf(
                queued(Instant.parse("2026-06-02T21:59:59Z")),
                queued(Instant.parse("2026-06-03T21:59:59Z")),
            ),
            now,
        )

        assertEquals(MessageKey.RX_REMINDER_MORE, message!!.key)
        assertEquals("ABCD1234EFGH", textArg(message, 0))
        assertEquals(MessageKey.RX_EXPIRES_TOMORROW, (message.args[1] as MessageArg.Nested).message.key)
        assertEquals(1, message.count)
    }

    @Test
    fun `a single expiring prescription is named without a count`() {
        val message = PrescriptionQueue.reminderMessage(
            listOf(queued(Instant.parse("2026-06-02T21:59:59Z"))),
            now,
        )

        assertEquals(MessageKey.RX_REMINDER_ONE, message?.key)
        assertEquals("ABCD1234EFGH", textArg(message!!, 0))
        assertEquals(MessageKey.RX_EXPIRES_TOMORROW, (message.args[1] as MessageArg.Nested).message.key)
    }

    @Test
    fun `no expiring prescription means no notification`() {
        val message = PrescriptionQueue.reminderMessage(
            listOf(
                queued(Instant.parse("2026-06-20T21:59:59Z")),
                queued(Instant.parse("2026-05-30T21:59:59Z")),
                queued(Instant.parse("2026-06-02T21:59:59Z"), dispensed = true),
            ),
            now,
        )

        assertNull(message)
    }
}
