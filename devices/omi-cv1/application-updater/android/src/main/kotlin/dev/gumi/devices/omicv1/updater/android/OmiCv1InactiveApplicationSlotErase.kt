package dev.gumi.devices.omicv1.updater.android

import dev.gumi.edge.sdk.EndpointCandidate
import dev.gumi.edge.sdk.firmware.FirmwareImageHash
import dev.gumi.edge.sdk.firmware.FirmwareImageSlot
import dev.gumi.edge.sdk.firmware.FirmwareImageStateInspection
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal data class OmiCv1PreparedInactiveApplicationSlotErase(
    val planId: String,
    val endpoint: EndpointCandidate,
    val expectedActiveApplicationHash: FirmwareImageHash,
)

internal class OmiCv1InactiveApplicationSlotEraseAuthorization internal constructor(
    private val plan: OmiCv1PreparedInactiveApplicationSlotErase,
    private val planId: String,
    private val expiresAtMonotonicMillis: Long,
) {
    private val consumed = AtomicBoolean(false)

    init {
        require(plan.planId == planId) { "Owner authorization is bound to another erase plan" }
        require(expiresAtMonotonicMillis > 0) { "Owner authorization expiry must be positive" }
    }

    fun consume(nowMonotonicMillis: Long): OmiCv1PreparedInactiveApplicationSlotErase {
        if (!consumed.compareAndSet(false, true)) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.AUTHORIZATION_REUSED,
                "Inactive-slot erase authorization has already been consumed",
            )
        }
        if (nowMonotonicMillis >= expiresAtMonotonicMillis) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.AUTHORIZATION_EXPIRED,
                "Inactive-slot erase authorization expired before execution",
            )
        }
        return plan
    }
}

internal enum class OmiCv1InactiveApplicationSlotEraseStage {
    VERIFYING_PREFLIGHT_STATE,
    ERASING_INACTIVE_SLOT,
    VERIFYING_ACTIVE_IMAGE_UNCHANGED,
}

internal data class OmiCv1InactiveApplicationSlotEraseValidation(
    val activeApplicationHash: FirmwareImageHash,
    val inactiveApplicationSlotAbsent: Boolean,
)

internal object OmiCv1InactiveApplicationSlotErasePlanner {
    fun prepare(
        endpoint: EndpointCandidate,
        inspection: FirmwareImageStateInspection,
        expectedActiveApplicationHash: FirmwareImageHash,
    ): OmiCv1PreparedInactiveApplicationSlotErase {
        requireSafeState(inspection, endpoint, expectedActiveApplicationHash, requireEmpty = false)
        val planId = listOf(
            RELEASE_ID,
            endpoint.transport.name,
            endpoint.ephemeralId,
            expectedActiveApplicationHash.hex,
            OmiCv1ApplicationUpdatePlanner.APPLICATION_IMAGE_NUMBER.toString(),
            OmiCv1ApplicationUpdatePlanner.SECONDARY_SLOT_NUMBER.toString(),
        ).joinToString("\u0000").encodeToByteArray().sha256Hex()
        return OmiCv1PreparedInactiveApplicationSlotErase(
            planId = planId,
            endpoint = endpoint,
            expectedActiveApplicationHash = expectedActiveApplicationHash,
        )
    }

    fun requirePreflightState(
        inspection: FirmwareImageStateInspection,
        plan: OmiCv1PreparedInactiveApplicationSlotErase,
    ) {
        requireSafeState(
            inspection,
            plan.endpoint,
            plan.expectedActiveApplicationHash,
            requireEmpty = false,
        )
    }

    fun validate(
        inspection: FirmwareImageStateInspection,
        plan: OmiCv1PreparedInactiveApplicationSlotErase,
    ): OmiCv1InactiveApplicationSlotEraseValidation {
        requireSafeState(
            inspection,
            plan.endpoint,
            plan.expectedActiveApplicationHash,
            requireEmpty = true,
        )
        return OmiCv1InactiveApplicationSlotEraseValidation(
            activeApplicationHash = plan.expectedActiveApplicationHash,
            inactiveApplicationSlotAbsent = true,
        )
    }

    private fun requireSafeState(
        inspection: FirmwareImageStateInspection,
        endpoint: EndpointCandidate,
        expectedActiveApplicationHash: FirmwareImageHash,
        requireEmpty: Boolean,
    ) {
        rejectUnless(inspection.endpoint == endpoint) {
            "Inactive-slot erase inspection came from another endpoint"
        }
        rejectUnless(inspection.protocol == OmiCv1ApplicationUpdatePlanner.MCUMGR_SMP_PROTOCOL) {
            "Inactive-slot erase requires MCU Manager SMP evidence"
        }
        rejectUnless(inspection.slots.none(FirmwareImageSlot::pending)) {
            "Inactive-slot erase is forbidden while any image is pending"
        }
        val activeApplications = inspection.slots.filter {
            it.imageNumber == OmiCv1ApplicationUpdatePlanner.APPLICATION_IMAGE_NUMBER && it.active
        }
        rejectUnless(activeApplications.size == 1) {
            "Inactive-slot erase requires exactly one active application image"
        }
        val active = activeApplications.single()
        rejectUnless(
            active.slotNumber == 0 &&
                active.hash == expectedActiveApplicationHash &&
                active.bootable &&
                active.confirmed &&
                !active.permanent,
        ) {
            "Inactive-slot erase requires the exact confirmed reclaimer in primary slot 0"
        }
        val inactiveApplications = inspection.slots.filter {
            it.imageNumber == OmiCv1ApplicationUpdatePlanner.APPLICATION_IMAGE_NUMBER && !it.active
        }
        rejectUnless(inactiveApplications.all {
            it.slotNumber == OmiCv1ApplicationUpdatePlanner.SECONDARY_SLOT_NUMBER &&
                !it.pending &&
                !it.permanent &&
                !it.confirmed
        }) {
            "Inactive-slot erase observed an unsafe application secondary state"
        }
        if (requireEmpty) {
            rejectUnless(inactiveApplications.none { it.isPopulated() }) {
                "Inactive application slot 1 is still populated after erase"
            }
        }
    }

    private fun FirmwareImageSlot.isPopulated(): Boolean =
        hash != null || version != null || bootable || pending || confirmed || permanent

    private fun rejectUnless(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) {
            throw OmiCv1ApplicationUpdateException(
                OmiCv1ApplicationUpdateFailureCode.PRECONDITION_FAILED,
                message(),
            )
        }
    }

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    const val RELEASE_ID =
        "omi-cv1-legacy-storage-reclaimer-0002-inactive-application-slot-erase-0001"
}

internal class OmiCv1InactiveApplicationSlotEraseExecutor(
    private val sessions: OmiCv1ApplicationImage0UpdateSessionFactory,
    private val clock: MonotonicMillisClock,
) {
    suspend fun execute(
        authorization: OmiCv1InactiveApplicationSlotEraseAuthorization,
        onProgress: (OmiCv1InactiveApplicationSlotEraseStage) -> Unit,
    ): OmiCv1InactiveApplicationSlotEraseValidation {
        val plan = authorization.consume(clock.now())
        val session = sessions.open(plan.endpoint)
        return try {
            onProgress(OmiCv1InactiveApplicationSlotEraseStage.VERIFYING_PREFLIGHT_STATE)
            OmiCv1InactiveApplicationSlotErasePlanner.requirePreflightState(
                session.inspect(),
                plan,
            )
            onProgress(OmiCv1InactiveApplicationSlotEraseStage.ERASING_INACTIVE_SLOT)
            session.eraseInactiveApplicationSlot()
            onProgress(OmiCv1InactiveApplicationSlotEraseStage.VERIFYING_ACTIVE_IMAGE_UNCHANGED)
            OmiCv1InactiveApplicationSlotErasePlanner.validate(session.inspect(), plan)
        } finally {
            session.release()
        }
    }
}
