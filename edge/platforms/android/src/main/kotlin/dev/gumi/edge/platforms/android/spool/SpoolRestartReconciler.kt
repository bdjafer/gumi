package dev.gumi.edge.platforms.android.spool

import dev.gumi.edge.runtime.spool.DurableChunk
import dev.gumi.edge.runtime.spool.DurablePayloadReadResult
import dev.gumi.edge.runtime.spool.DurablePayloadStore
import dev.gumi.edge.runtime.spool.SpoolState
import dev.gumi.edge.runtime.spool.SpoolStore
import dev.gumi.edge.runtime.spool.SpoolStoreLoadResult
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SpoolRestartReconciler(
    private val payloadStore: DurablePayloadStore,
    private val concretePayloadStore: EncryptedPayloadFileStore,
    private val fileOps: DurableFileOps,
    private val orphanGracePeriodMillis: Long,
    private val clockMillis: () -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun reconcile(metadataStore: SpoolStore): AndroidSpoolReconciliationReport =
        withContext(ioDispatcher) {
            when (val loaded = metadataStore.load()) {
                is SpoolStoreLoadResult.Unavailable -> emptyReport(
                    status = AndroidSpoolReconciliationStatus.SKIPPED_METADATA_UNAVAILABLE,
                    failureCodes = setOf(loaded.failure.code),
                )
                is SpoolStoreLoadResult.Loaded -> reconcileTrustedState(loaded.state)
            }
        }

    private suspend fun reconcileTrustedState(state: SpoolState): AndroidSpoolReconciliationReport {
        val failures = linkedSetOf<String>()
        val referenced = LinkedHashMap<String, MutableList<DurableChunk>>()
        state.captures.values.forEach { capture ->
            capture.streams.values.forEach { stream ->
                stream.chunks.values.forEach { record ->
                    referenced.getOrPut(record.chunk.payloadRef.value, ::mutableListOf)
                        .add(record.chunk)
                }
            }
        }

        var verified = 0
        var missingOrInvalid = 0
        referenced.values.forEach { chunks ->
            if (chunks.size != 1) {
                missingOrInvalid += chunks.size
                failures += "ANDROID_SPOOL_PAYLOAD_REFERENCE_COLLISION"
            } else {
                when (val read = payloadStore.readAndVerify(chunks.single())) {
                    is DurablePayloadReadResult.Verified -> verified += 1
                    is DurablePayloadReadResult.Unavailable -> {
                        missingOrInvalid += 1
                        failures += read.failure.code
                    }
                }
            }
        }

        val directory = concretePayloadStore.directory()
        val files = try {
            fileOps.ensureDirectory(directory)
            directory.listFiles()
                ?: throw SpoolStorageOperationException("ANDROID_SPOOL_DIRECTORY_LIST_FAILED")
        } catch (failure: Exception) {
            val mapped = if (failure is SpoolStorageOperationException) {
                failure.failureCode
            } else {
                AndroidSpoolFailureMapper.map(
                    failure,
                    "ANDROID_SPOOL_DIRECTORY_LIST_FAILED",
                ).code
            }
            return AndroidSpoolReconciliationReport(
                status = AndroidSpoolReconciliationStatus.COMPLETED_WITH_FAILURES,
                referencedPayloadCount = referenced.size,
                verifiedPayloadCount = verified,
                missingOrInvalidReferencedPayloadCount = missingOrInvalid,
                retainedYoungOrphanCount = 0,
                deletedOrphanCount = 0,
                untouchedUnknownFileCount = 0,
                failureCodes = failures + mapped,
            )
        }

        var retainedYoung = 0
        var deleted = 0
        var unknown = 0
        files.sortedBy { it.name }.forEach { file ->
            val finalReference = file.name
                .takeIf { it.endsWith(EncryptedPayloadFileStore.PAYLOAD_SUFFIX) }
                ?.removeSuffix(EncryptedPayloadFileStore.PAYLOAD_SUFFIX)
                ?.takeIf(PayloadIdentity::isValidReference)
            val recognizedTemporary = file.name.startsWith(EncryptedPayloadFileStore.TEMP_PREFIX) &&
                file.name.endsWith(EncryptedPayloadFileStore.TEMP_SUFFIX)
            val orphan = when {
                finalReference != null -> finalReference !in referenced
                recognizedTemporary -> true
                else -> {
                    unknown += 1
                    false
                }
            }
            if (!orphan) return@forEach
            if (!oldEnoughForCollection(file)) {
                retainedYoung += 1
                return@forEach
            }
            try {
                if (fileOps.deleteDurably(directory, file)) deleted += 1
            } catch (failure: Exception) {
                failures += AndroidSpoolFailureMapper.map(
                    failure,
                    "ANDROID_SPOOL_ORPHAN_DELETE_FAILED",
                ).code
            }
        }

        return AndroidSpoolReconciliationReport(
            status = if (failures.isEmpty()) {
                AndroidSpoolReconciliationStatus.COMPLETE
            } else {
                AndroidSpoolReconciliationStatus.COMPLETED_WITH_FAILURES
            },
            referencedPayloadCount = referenced.size,
            verifiedPayloadCount = verified,
            missingOrInvalidReferencedPayloadCount = missingOrInvalid,
            retainedYoungOrphanCount = retainedYoung,
            deletedOrphanCount = deleted,
            untouchedUnknownFileCount = unknown,
            failureCodes = failures,
        )
    }

    private fun oldEnoughForCollection(file: File): Boolean {
        val modified = file.lastModified()
        if (modified <= 0L) return false
        val now = clockMillis()
        if (now < modified) return false
        return now - modified >= orphanGracePeriodMillis
    }

    private fun emptyReport(
        status: AndroidSpoolReconciliationStatus,
        failureCodes: Set<String>,
    ) = AndroidSpoolReconciliationReport(
        status = status,
        referencedPayloadCount = 0,
        verifiedPayloadCount = 0,
        missingOrInvalidReferencedPayloadCount = 0,
        retainedYoungOrphanCount = 0,
        deletedOrphanCount = 0,
        untouchedUnknownFileCount = 0,
        failureCodes = failureCodes,
    )
}

private class SpoolStorageOperationException(val failureCode: String) : Exception(failureCode)
