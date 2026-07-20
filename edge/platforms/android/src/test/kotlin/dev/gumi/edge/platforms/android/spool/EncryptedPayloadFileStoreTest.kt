package dev.gumi.edge.platforms.android.spool

import dev.gumi.edge.runtime.spool.DurableChunk
import dev.gumi.edge.runtime.spool.DurablePayloadReadResult
import dev.gumi.edge.runtime.spool.DurablePayloadWriteResult
import dev.gumi.edge.sdk.OpaqueBytes
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class EncryptedPayloadFileStoreTest {
    @Test
    fun `immutable encrypted write is idempotent flushed and exactly readable`() = runBlocking {
        withStore { store, keyring, fileOps ->
            val payload = "audio-secret-marker".encodeToByteArray()
            val descriptor = testDescriptor(payload)

            val first = assertIs<DurablePayloadWriteResult.Stored>(
                store.writeAndFlush(descriptor, OpaqueBytes.copyOf(payload)),
            )
            val duplicate = assertIs<DurablePayloadWriteResult.Stored>(
                store.writeAndFlush(descriptor, OpaqueBytes.copyOf(payload)),
            )

            assertEquals(first.payloadRef, duplicate.payloadRef)
            assertTrue(fileOps.directorySyncCount >= 2)
            val file = store.payloadFile(first.payloadRef.value)
            assertTrue(file.isFile)
            assertFalse(file.name.contains(descriptor.captureSessionId.value))
            assertFalse(file.name.contains(descriptor.streamId.value))
            assertFalse(file.name.contains(descriptor.chunkId.value))
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("audio-secret-marker"))
            val verified = assertIs<DurablePayloadReadResult.Verified>(
                store.readAndVerify(DurableChunk(descriptor, first.payloadRef)),
            )
            assertContentEquals(payload, verified.payload.copyBytes())
            assertEquals(first.payloadRef, PayloadIdentity.reference(descriptor, keyring))
        }
    }

    @Test
    fun `tamper descriptor swap and wrong key fail before plaintext escapes`() = runBlocking {
        withStore { store, _, _ ->
            val payload = byteArrayOf(1, 3, 3, 7)
            val descriptor = testDescriptor(payload)
            val stored = assertIs<DurablePayloadWriteResult.Stored>(
                store.writeAndFlush(descriptor, OpaqueBytes.copyOf(payload)),
            )

            val swappedDescriptor = testDescriptor(payload, index = 2, sequence = 2uL)
            val swapped = assertIs<DurablePayloadReadResult.Unavailable>(
                store.readAndVerify(DurableChunk(swappedDescriptor, stored.payloadRef)),
            )
            assertEquals("ANDROID_SPOOL_PAYLOAD_BINDING_MISMATCH", swapped.failure.code)

            val file = store.payloadFile(stored.payloadRef.value)
            RandomAccessFile(file, "rw").use { random ->
                val offset = file.length() - 1
                random.seek(offset)
                val original = random.read()
                random.seek(offset)
                random.write(original xor 0x01)
            }
            val tampered = assertIs<DurablePayloadReadResult.Unavailable>(
                store.readAndVerify(DurableChunk(descriptor, stored.payloadRef)),
            )
            assertEquals("ANDROID_SPOOL_INTEGRITY_FAILED", tampered.failure.code)
            assertFalse(tampered.toString().contains(payload.joinToString()))
        }
    }

    @Test
    fun `old payload remains readable after key rotation and unavailable after key loss`() = runBlocking {
        withStore { store, keyring, _ ->
            val firstPayload = byteArrayOf(1, 2)
            val firstDescriptor = testDescriptor(firstPayload, index = 1, sequence = 1uL)
            val first = assertIs<DurablePayloadWriteResult.Stored>(
                store.writeAndFlush(firstDescriptor, OpaqueBytes.copyOf(firstPayload)),
            )

            keyring.rotateTo(2)
            val secondPayload = byteArrayOf(3, 4)
            val secondDescriptor = testDescriptor(secondPayload, index = 2, sequence = 2uL)
            val second = assertIs<DurablePayloadWriteResult.Stored>(
                store.writeAndFlush(secondDescriptor, OpaqueBytes.copyOf(secondPayload)),
            )

            assertIs<DurablePayloadReadResult.Verified>(
                store.readAndVerify(DurableChunk(firstDescriptor, first.payloadRef)),
            )
            assertIs<DurablePayloadReadResult.Verified>(
                store.readAndVerify(DurableChunk(secondDescriptor, second.payloadRef)),
            )
            keyring.remove(1)
            val lost = assertIs<DurablePayloadReadResult.Unavailable>(
                store.readAndVerify(DurableChunk(firstDescriptor, first.payloadRef)),
            )
            assertEquals("ANDROID_SPOOL_KEY_VERSION_UNAVAILABLE", lost.failure.code)
        }
    }

    @Test
    fun `digest length and physical quota failures have stable policy mappings`() = runBlocking {
        withStore(usableBytes = 100L) { store, _, _ ->
            val payload = ByteArray(16) { it.toByte() }
            val descriptor = testDescriptor(payload)
            val exhausted = assertIs<DurablePayloadWriteResult.Unavailable>(
                store.writeAndFlush(descriptor, OpaqueBytes.copyOf(payload)),
            )
            assertEquals("ANDROID_SPOOL_STORAGE_EXHAUSTED", exhausted.failure.code)
            assertTrue(exhausted.failure.retryable)

            val wrongBytes = payload.copyOf().apply { this[0] = 99 }
            val digestMismatch = assertIs<DurablePayloadWriteResult.Unavailable>(
                store.writeAndFlush(descriptor, OpaqueBytes.copyOf(wrongBytes)),
            )
            assertEquals("ANDROID_SPOOL_PAYLOAD_DIGEST_MISMATCH", digestMismatch.failure.code)
            assertFalse(digestMismatch.failure.retryable)
        }
    }

    @Test
    fun `closed payload port cannot outlive released storage ownership`() = runBlocking {
        withStore { store, _, _ ->
            val payload = byteArrayOf(1, 2, 3)
            val descriptor = testDescriptor(payload)
            store.close()

            val write = assertIs<DurablePayloadWriteResult.Unavailable>(
                store.writeAndFlush(descriptor, OpaqueBytes.copyOf(payload)),
            )
            assertEquals("ANDROID_SPOOL_PAYLOAD_STORE_CLOSED", write.failure.code)
            val read = assertIs<DurablePayloadReadResult.Unavailable>(
                store.readAndVerify(
                    DurableChunk(descriptor, PayloadIdentity.reference(descriptor, TestSpoolKeyring())),
                ),
            )
            assertEquals("ANDROID_SPOOL_PAYLOAD_STORE_CLOSED", read.failure.code)
        }
    }

    private suspend fun withStore(
        usableBytes: Long = Long.MAX_VALUE,
        block: suspend (EncryptedPayloadFileStore, TestSpoolKeyring, TestDurableFileOps) -> Unit,
    ) {
        val root = Files.createTempDirectory("gumi-spool-payload-test-").toFile()
        try {
            val keyring = TestSpoolKeyring()
            val fileOps = TestDurableFileOps(usableBytes)
            val store = EncryptedPayloadFileStore(
                directory = root,
                keyring = keyring,
                fileOps = fileOps,
                minimumFreeBytes = 64L,
                maximumPayloadBytes = 1_024,
            )
            block(store, keyring, fileOps)
        } finally {
            root.deleteRecursively()
        }
    }
}
