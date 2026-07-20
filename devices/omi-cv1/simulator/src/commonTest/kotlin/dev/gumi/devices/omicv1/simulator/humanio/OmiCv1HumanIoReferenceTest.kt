package dev.gumi.devices.omicv1.simulator.humanio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OmiCv1HumanIoReferenceTest {
    @Test
    fun `raw switch bounce becomes one accepted down and up`() {
        val subject = OmiCv1StableButtonDebouncer()
        val observed = buildList {
            addAll(subject.onRawLevel(0, OmiCv1ButtonLevel.PRESSED))
            addAll(subject.onRawLevel(10, OmiCv1ButtonLevel.RELEASED))
            addAll(subject.onRawLevel(18, OmiCv1ButtonLevel.PRESSED))
            addAll(subject.onRawLevel(100, OmiCv1ButtonLevel.RELEASED))
            addAll(subject.advanceTo(130))
        }

        assertEquals(
            listOf(
                OmiCv1AcceptedButtonEdge(48, OmiCv1ButtonLevel.PRESSED),
                OmiCv1AcceptedButtonEdge(130, OmiCv1ButtonLevel.RELEASED),
            ),
            observed,
        )
    }

    @Test
    fun `raw level stable for exactly thirty milliseconds is accepted once`() {
        val subject = OmiCv1StableButtonDebouncer()

        assertEquals(emptyList(), subject.onRawLevel(0, OmiCv1ButtonLevel.PRESSED))
        assertEquals(emptyList(), subject.advanceTo(29))
        assertEquals(
            listOf(OmiCv1AcceptedButtonEdge(30, OmiCv1ButtonLevel.PRESSED)),
            subject.onRawLevel(30, OmiCv1ButtonLevel.RELEASED),
        )
        assertEquals(emptyList(), subject.advanceTo(59))
        assertEquals(
            listOf(OmiCv1AcceptedButtonEdge(60, OmiCv1ButtonLevel.RELEASED)),
            subject.advanceTo(60),
        )
    }

    @Test
    fun `second press at inclusive window suppresses single and releases as double`() {
        val subject = OmiCv1GestureRecognizer(OmiCv1GestureContext.NORMAL_IDLE)
        val events = buildList {
            addAll(subject.acceptEdge(0, OmiCv1ButtonLevel.PRESSED))
            addAll(subject.acceptEdge(100, OmiCv1ButtonLevel.RELEASED))
            addAll(subject.acceptEdge(450, OmiCv1ButtonLevel.PRESSED))
            addAll(subject.advanceTo(450))
            addAll(subject.acceptEdge(550, OmiCv1ButtonLevel.RELEASED))
        }

        assertEquals(
            listOf(
                OmiCv1GestureEvent(550, OmiCv1GestureEventType.DOUBLE_TAP),
                OmiCv1GestureEvent(550, OmiCv1GestureEventType.START_BASE_RECORDING_REQUESTED),
            ),
            events,
        )
    }

    @Test
    fun `release at exact hold deadline wins and becomes a single tap`() {
        val subject = OmiCv1GestureRecognizer(OmiCv1GestureContext.NORMAL_IDLE)
        val events = buildList {
            addAll(subject.acceptEdge(0, OmiCv1ButtonLevel.PRESSED))
            addAll(subject.acceptEdge(500, OmiCv1ButtonLevel.RELEASED))
            addAll(subject.advanceTo(500))
            addAll(subject.advanceTo(850))
        }

        assertEquals(
            listOf(
                OmiCv1GestureEvent(850, OmiCv1GestureEventType.SINGLE_TAP),
                OmiCv1GestureEvent(850, OmiCv1GestureEventType.REPEAT_STATUS),
            ),
            events,
        )
    }

    @Test
    fun `hold timer commits at exactly five hundred milliseconds when no edge wins`() {
        val subject = OmiCv1GestureRecognizer(OmiCv1GestureContext.NORMAL_IDLE)

        assertEquals(emptyList(), subject.acceptEdge(0, OmiCv1ButtonLevel.PRESSED))
        assertEquals(emptyList(), subject.advanceTo(499))
        assertEquals(
            listOf(
                OmiCv1GestureEvent(500, OmiCv1GestureEventType.HOLD_COMMITTED),
                OmiCv1GestureEvent(500, OmiCv1GestureEventType.START_VOICE_TURN_REQUESTED),
            ),
            subject.advanceTo(500),
        )
    }

    @Test
    fun `an accepted edge cannot arrive after deadlines advanced at the same timestamp`() {
        val subject = OmiCv1GestureRecognizer(OmiCv1GestureContext.NORMAL_IDLE)
        subject.acceptEdge(0, OmiCv1ButtonLevel.PRESSED)
        subject.advanceTo(500)

        assertFailsWith<IllegalArgumentException> {
            subject.acceptEdge(500, OmiCv1ButtonLevel.RELEASED)
        }

        assertEquals(
            listOf(
                OmiCv1GestureEvent(501, OmiCv1GestureEventType.HOLD_RELEASED),
                OmiCv1GestureEvent(501, OmiCv1GestureEventType.END_VOICE_TURN_REQUESTED),
            ),
            subject.acceptEdge(501, OmiCv1ButtonLevel.RELEASED),
        )
    }

    @Test
    fun `duplicate accepted edges fail without consuming pending deadlines`() {
        val duplicateDown = OmiCv1GestureRecognizer(OmiCv1GestureContext.NORMAL_IDLE)
        duplicateDown.acceptEdge(0, OmiCv1ButtonLevel.PRESSED)
        assertFailsWith<IllegalArgumentException> {
            duplicateDown.acceptEdge(600, OmiCv1ButtonLevel.PRESSED)
        }
        duplicateDown.acceptEdge(500, OmiCv1ButtonLevel.RELEASED)
        assertEquals(
            listOf(
                OmiCv1GestureEvent(850, OmiCv1GestureEventType.SINGLE_TAP),
                OmiCv1GestureEvent(850, OmiCv1GestureEventType.REPEAT_STATUS),
            ),
            duplicateDown.advanceTo(850),
        )

        val duplicateUp = OmiCv1GestureRecognizer(OmiCv1GestureContext.NORMAL_IDLE)
        duplicateUp.acceptEdge(0, OmiCv1ButtonLevel.PRESSED)
        duplicateUp.acceptEdge(100, OmiCv1ButtonLevel.RELEASED)
        assertFailsWith<IllegalArgumentException> {
            duplicateUp.acceptEdge(451, OmiCv1ButtonLevel.RELEASED)
        }
        assertEquals(
            listOf(
                OmiCv1GestureEvent(450, OmiCv1GestureEventType.SINGLE_TAP),
                OmiCv1GestureEvent(450, OmiCv1GestureEventType.REPEAT_STATUS),
            ),
            duplicateUp.advanceTo(450),
        )
    }

    @Test
    fun `stale accepted edge fails without changing a valid press`() {
        val subject = OmiCv1GestureRecognizer(OmiCv1GestureContext.NORMAL_IDLE)
        subject.acceptEdge(100, OmiCv1ButtonLevel.PRESSED)

        assertFailsWith<IllegalArgumentException> {
            subject.acceptEdge(99, OmiCv1ButtonLevel.RELEASED)
        }

        subject.acceptEdge(600, OmiCv1ButtonLevel.RELEASED)
        assertEquals(
            listOf(
                OmiCv1GestureEvent(950, OmiCv1GestureEventType.SINGLE_TAP),
                OmiCv1GestureEvent(950, OmiCv1GestureEventType.REPEAT_STATUS),
            ),
            subject.advanceTo(950),
        )
    }

    @Test
    fun `contextual two second hold confirms only inside its disclosed lease`() {
        val subject = OmiCv1GestureRecognizer(
            context = OmiCv1GestureContext.AWAITING_CONFIRMATION,
            confirmationOperation = "firmware_update",
            confirmationLeaseExpiresAtMillis = 15_000,
        )
        val events = buildList {
            addAll(subject.acceptEdge(1_000, OmiCv1ButtonLevel.PRESSED))
            addAll(subject.advanceTo(3_000))
            addAll(subject.acceptEdge(3_100, OmiCv1ButtonLevel.RELEASED))
        }

        assertEquals(
            listOf(
                OmiCv1GestureEvent(
                    3_000,
                    OmiCv1GestureEventType.PHYSICAL_CONFIRMATION,
                    operation = "firmware_update",
                ),
            ),
            events,
        )
    }

    @Test
    fun `release at exact contextual hold deadline wins`() {
        val subject = OmiCv1GestureRecognizer(
            context = OmiCv1GestureContext.AWAITING_CONFIRMATION,
            confirmationOperation = "firmware_update",
            confirmationLeaseExpiresAtMillis = 5_000,
        )

        assertEquals(emptyList(), subject.acceptEdge(0, OmiCv1ButtonLevel.PRESSED))
        assertEquals(emptyList(), subject.acceptEdge(2_000, OmiCv1ButtonLevel.RELEASED))
        assertEquals(emptyList(), subject.advanceTo(2_000))
    }

    @Test
    fun `contextual hold must commit strictly before lease expiry`() {
        fun confirmWithLease(expiryMillis: Long): List<OmiCv1GestureEvent> {
            val subject = OmiCv1GestureRecognizer(
                context = OmiCv1GestureContext.AWAITING_CONFIRMATION,
                confirmationOperation = "firmware_update",
                confirmationLeaseExpiresAtMillis = expiryMillis,
            )
            subject.acceptEdge(0, OmiCv1ButtonLevel.PRESSED)
            return subject.advanceTo(2_000)
        }

        assertEquals(
            listOf(
                OmiCv1GestureEvent(
                    2_000,
                    OmiCv1GestureEventType.PHYSICAL_CONFIRMATION,
                    operation = "firmware_update",
                ),
            ),
            confirmWithLease(2_001),
        )
        assertEquals(emptyList(), confirmWithLease(2_000))
        assertEquals(emptyList(), confirmWithLease(1_999))
    }
}
