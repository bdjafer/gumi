package dev.gumi.edge.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class CapabilitySetTest {
    @Test
    fun `typed descriptor and handle resolve through their exact capability token`() {
        val descriptor = AudioDescriptor(sampleRateHz = 16_000)
        val handle = AudioHandle(descriptor)

        val result = CapabilitySet.negotiate(
            advertised = listOf(descriptor),
            bindings = listOf(CapabilityBinding(AudioCapability, descriptor, handle)),
        )

        val set = assertIs<OperationResult.Success<CapabilitySet>>(result).value
        assertSame(descriptor, set.descriptor(AudioCapability))
        assertSame(handle, set.handle(AudioCapability))
        assertEquals(setOf(AudioCapability.key), set.keys())
        assertEquals(0, set.unrecognizedDescriptors.size)
    }

    @Test
    fun `a different token cannot cast a handle merely by reusing its key`() {
        val descriptor = AudioDescriptor(sampleRateHz = 16_000)
        val set = assertIs<OperationResult.Success<CapabilitySet>>(
            CapabilitySet.negotiate(
                advertised = listOf(descriptor),
                bindings = listOf(CapabilityBinding(AudioCapability, descriptor, AudioHandle(descriptor))),
            ),
        ).value

        assertNull(set.handle(SpoofedAudioCapability))
    }

    @Test
    fun `unknown optional descriptor remains visible without a handle`() {
        val optional = CapabilityDescriptor(
            key = CapabilityKey("vendor.future-sensor"),
            version = SemanticVersion(1u, 4u),
        )

        val set = assertIs<OperationResult.Success<CapabilitySet>>(
            CapabilitySet.negotiate(advertised = listOf(optional), bindings = emptyList()),
        ).value

        assertEquals(listOf(optional), set.descriptors)
        assertEquals(listOf(optional), set.unrecognizedDescriptors)
    }

    @Test
    fun `unknown required capability fails explicitly`() {
        val required = CapabilityDescriptor(
            key = CapabilityKey("vendor.required-sensor"),
            version = SemanticVersion(1u, 0u),
            required = true,
        )

        val failure = assertIs<OperationResult.Failure>(
            CapabilitySet.negotiate(advertised = listOf(required), bindings = emptyList()),
        ).failure

        assertEquals(FailureCategory.INCOMPATIBLE, failure.category)
        assertEquals("UNKNOWN_REQUIRED_CAPABILITY", failure.code.value)
        assertEquals("vendor.required-sensor", failure.redactedEvidence["capability"])
    }

    @Test
    fun `unsupported optional major is preserved while unsupported required major fails`() {
        val optional = AudioDescriptor(version = SemanticVersion(2u, 0u), required = false)
        val optionalSet = assertIs<OperationResult.Success<CapabilitySet>>(
            CapabilitySet.negotiate(
                advertised = listOf(optional),
                bindings = emptyList(),
                supportedTypes = setOf(AudioCapability),
            ),
        ).value
        assertEquals(listOf(optional), optionalSet.unrecognizedDescriptors)

        val required = optional.copy(required = true)
        val failure = assertIs<OperationResult.Failure>(
            CapabilitySet.negotiate(
                advertised = listOf(required),
                bindings = emptyList(),
                supportedTypes = setOf(AudioCapability),
            ),
        ).failure
        assertEquals("UNSUPPORTED_REQUIRED_CAPABILITY_VERSION", failure.code.value)
    }

    @Test
    fun `compatible advertised capability without a handle fails as a driver defect`() {
        val descriptor = AudioDescriptor()

        val failure = assertIs<OperationResult.Failure>(
            CapabilitySet.negotiate(
                advertised = listOf(descriptor),
                bindings = emptyList(),
                supportedTypes = setOf(AudioCapability),
            ),
        ).failure

        assertEquals("MISSING_CAPABILITY_HANDLE", failure.code.value)
    }

    @Test
    fun `duplicate descriptors fail instead of selecting one`() {
        val descriptor = AudioDescriptor()

        val failure = assertIs<OperationResult.Failure>(
            CapabilitySet.negotiate(
                advertised = listOf(descriptor, descriptor.copy(sampleRateHz = 24_000)),
                bindings = emptyList(),
                supportedTypes = setOf(AudioCapability),
            ),
        ).failure

        assertEquals("DUPLICATE_CAPABILITY_DESCRIPTOR", failure.code.value)
    }

    @Test
    fun `binding rejects a handle that reports another descriptor`() {
        val descriptor = AudioDescriptor(sampleRateHz = 16_000)
        val other = descriptor.copy(sampleRateHz = 24_000)

        assertFailsWith<IllegalArgumentException> {
            CapabilityBinding(AudioCapability, descriptor, AudioHandle(other))
        }
    }

    private data class AudioDescriptor(
        override val version: SemanticVersion = SemanticVersion(1u, 0u),
        override val required: Boolean = false,
        val sampleRateHz: Int = 16_000,
    ) : CapabilityDescriptor {
        override val key = AUDIO_KEY
    }

    private data class AudioHandle(
        override val descriptor: AudioDescriptor,
    ) : CapabilityHandle<AudioDescriptor>

    private object AudioCapability : CapabilityType<AudioDescriptor, AudioHandle> {
        override val key = AUDIO_KEY
        override val supportedMajor = 1u
    }

    private object SpoofedAudioCapability : CapabilityType<AudioDescriptor, AudioHandle> {
        override val key = AUDIO_KEY
        override val supportedMajor = 1u
    }

    private companion object {
        val AUDIO_KEY = CapabilityKey("gumi.audio-input")
    }
}
