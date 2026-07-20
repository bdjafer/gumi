package dev.gumi.edge.runtime.media.ogg

import dev.gumi.edge.sdk.OpaqueBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeterministicOggOpusMuxerTest {
    @Test
    fun `RFC 6716 inspector exposes bounded non-content TOC facts for every duration family`() {
        val cases = listOf(
            0u to 10_000u,
            1u to 20_000u,
            2u to 40_000u,
            3u to 60_000u,
            12u to 10_000u,
            13u to 20_000u,
            16u to 2_500u,
            17u to 5_000u,
            18u to 10_000u,
            19u to 20_000u,
            31u to 20_000u,
        )

        for ((configuration, durationUs) in cases) {
            val stereo = configuration % 2u == 1u
            val toc = (configuration shl 3) or if (stereo) 0x04u else 0u
            val result = Rfc6716OpusPacketInspector.inspect(bytes(toc.toByte(), 0x55))
            val info = assertIs<OpusPacketInspectionResult.Valid>(result).info
            assertEquals(2, info.packetBytes)
            assertEquals(configuration, info.configuration)
            assertEquals(stereo, info.encodedStereo)
            assertEquals(1u, info.frameCount)
            assertEquals(0u, info.frameCountCode)
            assertEquals(durationUs, info.frameDurationUs)
            assertEquals(durationUs * 48u / 1_000u, info.decodedSamples48k)
        }
    }

    @Test
    fun `inspector rejects empty oversized multi-frame and wrong-duration packets without content`() {
        assertEquals(
            OpusPacketInspectionFailure.EmptyPacket,
            assertIs<OpusPacketInspectionResult.Invalid>(
                Rfc6716OpusPacketInspector.inspect(bytes()),
            ).failure,
        )
        assertIs<OpusPacketInspectionFailure.PacketTooLarge>(
            assertIs<OpusPacketInspectionResult.Invalid>(
                Rfc6716OpusPacketInspector.inspect(
                    OpaqueBytes.copyOf(ByteArray(RFC6716_MAX_SINGLE_FRAME_PACKET_BYTES + 1)),
                ),
            ).failure,
        )
        for (frameCountCode in 1..3) {
            assertEquals(
                OpusPacketInspectionFailure.FrameCountNotOne(frameCountCode.toUInt()),
                assertIs<OpusPacketInspectionResult.Invalid>(
                    Rfc6716OpusPacketInspector.inspect(bytes((0x48 or frameCountCode).toByte())),
                ).failure,
            )
        }
        assertEquals(
            OpusPacketInspectionFailure.FrameDurationMismatch(10_000u, 20_000u),
            assertIs<OpusPacketInspectionResult.Invalid>(
                Rfc6716OpusPacketInspector.inspect(bytes(0x48), 10_000u),
            ).failure,
        )
    }

    @Test
    fun `golden single-packet stream has exact headers metadata CRC granule BOS and EOS`() {
        val muxer = DeterministicOggOpusMuxer(config())
        assertNull(accepted(muxer.accept(packet(10uL, 0x48, 0x11, 0x22))))
        val fragment = accepted(muxer.finish())
        val actual = fragment.bytes.copyBytes()

        assertContentEquals(GOLDEN_SINGLE_PACKET_STREAM.hexBytes(), actual)
        assertEquals(AudioPacketRange(10uL, 10uL), fragment.audioSequenceRange)
        assertEquals(0u, fragment.firstPageSequence)
        assertEquals(2u, fragment.lastPageSequence)
        assertTrue(fragment.beginsLogicalStream)
        assertTrue(fragment.endsLogicalStream)
        assertEquals(960uL, fragment.terminalGranulePosition)

        val pages = parsePages(actual)
        assertEquals(listOf(0u, 1u, 2u), pages.map(Page::sequence))
        assertEquals(listOf(0uL, 0uL, 960uL), pages.map(Page::granule))
        assertTrue(pages.first().headerType and 0x02 != 0)
        assertTrue(pages.last().headerType and 0x04 != 0)
        assertTrue(pages.all(Page::crcValid))
        assertEquals("OpusHead", pages[0].payload.copyOfRange(0, 8).decodeToString())
        assertEquals("OpusTags", pages[1].payload.copyOfRange(0, 8).decodeToString())
        assertEquals(listOf(19), pages[0].laces)
        assertEquals(listOf(20), pages[1].laces)
        assertEquals(listOf(3), pages[2].laces)
    }

    @Test
    fun `layout derives contiguous page sequences and granules and applies bounded terminal trim`() {
        val muxer = DeterministicOggOpusMuxer(config())
        assertNull(accepted(muxer.accept(packet(10uL))))
        val first = accepted(muxer.accept(packet(11uL)))!!
        val second = accepted(muxer.accept(packet(12uL)))!!
        val terminal = accepted(muxer.finish(endTrim48kSamples = 120u))

        val firstPages = parsePages(first.bytes.copyBytes())
        assertEquals(listOf(0u, 1u, 2u), firstPages.map(Page::sequence))
        assertEquals(960uL, firstPages.last().granule)
        assertTrue(first.beginsLogicalStream)
        assertFalse(first.endsLogicalStream)

        assertEquals(listOf(3u), parsePages(second.bytes.copyBytes()).map(Page::sequence))
        assertEquals(1_920uL, second.terminalGranulePosition)
        assertEquals(listOf(4u), parsePages(terminal.bytes.copyBytes()).map(Page::sequence))
        assertEquals(2_760uL, terminal.terminalGranulePosition)
        assertTrue(terminal.endsLogicalStream)
    }

    @Test
    fun `bounded composer batches contiguous page fragments without duplicating headers or EOS`() {
        val muxer = DeterministicOggOpusMuxer(config())
        assertNull(accepted(muxer.accept(packet(10uL))))
        val first = accepted(muxer.accept(packet(11uL)))!!
        val middle = accepted(muxer.accept(packet(12uL)))!!
        val terminal = accepted(muxer.finish())
        val expectedBytes = first.bytes.copyBytes() + middle.bytes.copyBytes() + terminal.bytes.copyBytes()

        val composed = composed(
            OggOpusFragmentComposer.compose(
                fragments = listOf(first, middle, terminal),
                maximumFragments = 3,
                maximumBytes = expectedBytes.size,
            ),
        )

        assertEquals(AudioPacketRange(10uL, 12uL), composed.audioSequenceRange)
        assertEquals(0u, composed.firstPageSequence)
        assertEquals(4u, composed.lastPageSequence)
        assertTrue(composed.beginsLogicalStream)
        assertTrue(composed.endsLogicalStream)
        assertEquals(2_880uL, composed.terminalGranulePosition)
        assertContentEquals(expectedBytes, composed.bytes.copyBytes())
        val pages = parsePages(composed.bytes.copyBytes())
        assertEquals(listOf(0u, 1u, 2u, 3u, 4u), pages.map(Page::sequence))
        assertEquals(1, pages.count { it.headerType and 0x02 != 0 })
        assertEquals(1, pages.count { it.headerType and 0x04 != 0 })

        assertEquals(
            OggOpusFragmentCompositionFailure.FragmentLimitExceeded(3, 2),
            compositionRejected(
                OggOpusFragmentComposer.compose(listOf(first, middle, terminal), 2, expectedBytes.size),
            ),
        )
        assertIs<OggOpusFragmentCompositionFailure.ByteLimitExceeded>(
            compositionRejected(
                OggOpusFragmentComposer.compose(
                    listOf(first, middle, terminal),
                    3,
                    expectedBytes.size - 1,
                ),
            ),
        )
    }

    @Test
    fun `composer rejects audio page and EOS discontinuities before concatenation`() {
        val muxer = DeterministicOggOpusMuxer(config())
        accepted(muxer.accept(packet(10uL)))
        val first = accepted(muxer.accept(packet(11uL)))!!
        val middle = accepted(muxer.accept(packet(12uL)))!!
        val terminal = accepted(muxer.finish())

        assertEquals(
            OggOpusFragmentCompositionFailure.NonContiguousAudioSequence(11uL, 12uL),
            compositionRejected(
                OggOpusFragmentComposer.compose(listOf(first, terminal), 2, 10_000),
            ),
        )
        assertEquals(
            OggOpusFragmentCompositionFailure.NonContiguousPageSequence(3u, 99u),
            compositionRejected(
                OggOpusFragmentComposer.compose(
                    listOf(first, middle.copy(firstPageSequence = 99u)),
                    2,
                    10_000,
                ),
            ),
        )
        assertEquals(
            OggOpusFragmentCompositionFailure.LogicalStreamEndedBeforeFinalFragment(10uL),
            compositionRejected(
                OggOpusFragmentComposer.compose(
                    listOf(first.copy(endsLogicalStream = true), middle),
                    2,
                    10_000,
                ),
            ),
        )
    }

    @Test
    fun `one packet page uses terminating zero lace at an exact 255-byte boundary`() {
        val payload = ByteArray(255).also { it[0] = 0x48 }
        val muxer = DeterministicOggOpusMuxer(config())
        assertNull(accepted(muxer.accept(packet(10uL, payload = payload))))
        val audioPage = parsePages(accepted(muxer.finish()).bytes.copyBytes()).last()

        assertEquals(listOf(255, 0), audioPage.laces)
        assertContentEquals(payload, audioPage.payload)
        assertTrue(audioPage.crcValid)
    }

    @Test
    fun `bounded packet buffer and CRC witness reject overflow and corruption`() {
        val maximum = ByteArray(RFC6716_MAX_SINGLE_FRAME_PACKET_BYTES).also { it[0] = 0x48 }
        val muxer = DeterministicOggOpusMuxer(config())
        assertNull(accepted(muxer.accept(packet(10uL, payload = maximum))))
        assertEquals(RFC6716_MAX_SINGLE_FRAME_PACKET_BYTES, muxer.bufferedAudioBytes)
        val fragment = accepted(muxer.finish())
        assertEquals(0, muxer.bufferedAudioBytes)

        val corrupted = fragment.bytes.copyBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertFalse(parsePages(corrupted, requireValidCrc = false).last().crcValid)

        val oversized = DeterministicOggOpusMuxer(config())
        val failure = rejected(
            oversized.accept(
                packet(
                    10uL,
                    payload = ByteArray(RFC6716_MAX_SINGLE_FRAME_PACKET_BYTES + 1),
                ),
            ),
        )
        assertIs<OggOpusMuxFailure.InvalidOpusPacket>(failure)
        assertIs<OpusPacketInspectionFailure.PacketTooLarge>(failure.failure)
        assertEquals(0, oversized.bufferedAudioBytes)
    }

    @Test
    fun `source gap duplicate and discontinuity latch a typed terminal failure`() {
        val gapMuxer = DeterministicOggOpusMuxer(config())
        accepted(gapMuxer.accept(packet(10uL)))
        val gap = rejected(gapMuxer.accept(packet(12uL)))
        assertEquals(OggOpusMuxFailure.NonContiguousAudioSequence(11uL, 12uL), gap)
        assertEquals(gap, rejected(gapMuxer.accept(packet(11uL))))
        assertEquals(gap, rejected(gapMuxer.finish()))

        val discontinuous = DeterministicOggOpusMuxer(config())
        val discontinuity = rejected(
            discontinuous.accept(packet(10uL, discontinuityBefore = true)),
        )
        assertEquals(OggOpusMuxFailure.SourceDiscontinuity(10uL), discontinuity)
        assertEquals(discontinuity, rejected(discontinuous.accept(packet(10uL))))
    }

    @Test
    fun `versioned snapshot restores byte-identical output only for a replayable source`() {
        val expectedConfig = config()
        val original = DeterministicOggOpusMuxer(expectedConfig)
        accepted(original.accept(packet(10uL)))
        val pendingPacket = packet(11uL, 0x48, 0x11, 0x22)
        val durableFirst = accepted(original.accept(pendingPacket))!!
        val snapshot = accepted(original.snapshot())

        assertEquals(OGG_OPUS_MUX_SNAPSHOT_VERSION, snapshot.schemaVersion)
        assertEquals(expectedConfig.configurationId, snapshot.configurationId)
        assertEquals(11uL, snapshot.nextSourceSequence)
        assertEquals(3u, snapshot.nextPageSequence)
        assertEquals(1_920uL, snapshot.decodedGranule48k)
        assertTrue(snapshot.headersEmitted)
        assertEquals(3, snapshot.pendingPacket!!.packetBytes)
        assertEquals(TEST_REPLAY_BINDING.sha256Hex, snapshot.pendingPacket.replaySha256Hex)
        assertFalse(snapshot.toString().contains("481122"))

        val restored = accepted(
            DeterministicOggOpusMuxer.restore(
                snapshot,
                expectedConfig,
                OggOpusResumeMode.REPLAYABLE_SEQUENCE_SOURCE,
                pendingPacket,
            ),
        )
        val originalNext = accepted(original.accept(packet(12uL)))!!
        val restoredNext = accepted(restored.accept(packet(12uL)))!!
        assertContentEquals(originalNext.bytes.copyBytes(), restoredNext.bytes.copyBytes())
        assertContentEquals(
            accepted(original.finish(96u)).bytes.copyBytes(),
            accepted(restored.finish(96u)).bytes.copyBytes(),
        )
        assertTrue(durableFirst.beginsLogicalStream)

        assertEquals(
            OggOpusMuxFailure.NonReplayableSourceCannotResume,
            rejected(
                DeterministicOggOpusMuxer.restore(
                    snapshot,
                    expectedConfig,
                    OggOpusResumeMode.NON_REPLAYABLE_LIVE_SOURCE,
                    null,
                ),
            ),
        )
    }

    @Test
    fun `snapshot restore rejects version config replay mismatch and derived-state corruption`() {
        val expectedConfig = config()
        val muxer = DeterministicOggOpusMuxer(expectedConfig)
        accepted(muxer.accept(packet(10uL)))
        val snapshot = accepted(muxer.snapshot())

        assertIs<OggOpusMuxFailure.SnapshotVersionUnsupported>(
            rejected(restore(snapshot.copy(schemaVersion = "future"), expectedConfig)),
        )
        assertIs<OggOpusMuxFailure.SnapshotConfigMismatch>(
            rejected(restore(snapshot.copy(configurationId = "other"), expectedConfig)),
        )
        assertIs<OggOpusMuxFailure.SnapshotConfigMismatch>(
            rejected(restore(snapshot, expectedConfig.copy(serialNumber = 9u))),
        )
        assertIs<OggOpusMuxFailure.SnapshotPendingIdentityMalformed>(
            rejected(
                restore(
                    snapshot.copy(
                        pendingPacket = snapshot.pendingPacket!!.copy(replaySha256Hex = "48AA"),
                    ),
                    expectedConfig,
                ),
            ),
        )
        assertEquals(
            OggOpusMuxFailure.SnapshotPendingPacketRequired(10uL),
            rejected(
                DeterministicOggOpusMuxer.restore(
                    snapshot,
                    expectedConfig,
                    OggOpusResumeMode.REPLAYABLE_SEQUENCE_SOURCE,
                    null,
                ),
            ),
        )
        assertEquals(
            OggOpusMuxFailure.SnapshotPendingPacketMismatch("replaySha256Hex"),
            rejected(
                restore(
                    snapshot,
                    expectedConfig,
                    packet(10uL, replayBinding = OTHER_REPLAY_BINDING),
                ),
            ),
        )
        val unboundMuxer = DeterministicOggOpusMuxer(expectedConfig)
        accepted(unboundMuxer.accept(packet(10uL, replayBinding = null)))
        assertEquals(
            OggOpusMuxFailure.PendingPacketReplayBindingRequired(10uL),
            rejected(unboundMuxer.snapshot()),
        )
        for ((field, corrupted) in listOf(
            "nextSourceSequence" to snapshot.copy(nextSourceSequence = 99uL),
            "nextPageSequence" to snapshot.copy(nextPageSequence = 99u),
            "decodedGranule48k" to snapshot.copy(decodedGranule48k = 999uL),
            "headersEmitted" to snapshot.copy(headersEmitted = true),
        )) {
            assertEquals(
                OggOpusMuxFailure.SnapshotStateMismatch(field),
                rejected(restore(corrupted, expectedConfig)),
            )
        }
    }

    @Test
    fun `u64 terminal source sequence remains finishable but cannot accept another packet`() {
        val expectedConfig = config().copy(firstAudioSequence = ULong.MAX_VALUE)
        val muxer = DeterministicOggOpusMuxer(expectedConfig)
        assertNull(accepted(muxer.accept(packet(ULong.MAX_VALUE))))
        val snapshot = accepted(muxer.snapshot())
        assertEquals(ULong.MAX_VALUE, snapshot.nextSourceSequence)
        assertEquals(2u, snapshot.nextPageSequence)
        assertEquals(960uL, snapshot.decodedGranule48k)
        assertEquals(
            OggOpusMuxFailure.AudioSequenceExhausted(ULong.MAX_VALUE),
            rejected(muxer.accept(packet(ULong.MAX_VALUE))),
        )

        val restored = accepted(
            DeterministicOggOpusMuxer.restore(
                snapshot,
                expectedConfig,
                OggOpusResumeMode.REPLAYABLE_SEQUENCE_SOURCE,
                packet(ULong.MAX_VALUE),
            ),
        )
        assertTrue(accepted(restored.finish()).endsLogicalStream)
    }

    @Test
    fun `snapshot restore rejects page and granule identity exhaustion before state use`() {
        val expectedConfig = config().copy(firstAudioSequence = 0uL)
        val seed = DeterministicOggOpusMuxer(expectedConfig)
        accepted(seed.accept(packet(0uL)))
        val base = accepted(seed.snapshot())
        val pending = requireNotNull(base.pendingPacket)

        assertEquals(
            OggOpusMuxFailure.PageSequenceExhausted,
            rejected(
                restore(
                    base.copy(
                        pendingPacket = pending.copy(
                            sequence = UInt.MAX_VALUE.toULong() - 1uL,
                        ),
                    ),
                    expectedConfig,
                ),
            ),
        )
        assertEquals(
            OggOpusMuxFailure.GranulePositionExhausted,
            rejected(
                restore(
                    base.copy(
                        pendingPacket = pending.copy(sequence = ULong.MAX_VALUE),
                    ),
                    expectedConfig,
                ),
            ),
        )
    }

    @Test
    fun `terminal publication rejects no audio excessive trim and pre-skip underflow`() {
        assertEquals(
            OggOpusMuxFailure.NoAudioPackets,
            rejected(DeterministicOggOpusMuxer(config()).finish()),
        )

        val trim = DeterministicOggOpusMuxer(config())
        accepted(trim.accept(packet(10uL)))
        assertEquals(
            OggOpusMuxFailure.TerminalTrimExceedsFinalPacket(961u, 960u),
            rejected(trim.finish(961u)),
        )

        val preSkip = DeterministicOggOpusMuxer(config().copy(preSkip48kSamples = 1_000u))
        accepted(preSkip.accept(packet(10uL)))
        assertEquals(
            OggOpusMuxFailure.TerminalGranulePrecedesPreSkip(960uL, 1_000u),
            rejected(preSkip.finish()),
        )
    }

    @Test
    fun `caller-supplied serial is deterministic while M1 tags stay fixed and metadata-free`() {
        val first = onePacketBytes(config())
        val replay = onePacketBytes(config())
        val otherSerial = onePacketBytes(config().copy(serialNumber = 8u))

        assertContentEquals(first, replay)
        assertFalse(first.contentEquals(otherSerial))
        val tags = parsePages(first)[1].payload
        assertContentEquals("OpusTags\u0004\u0000\u0000\u0000gumi\u0000\u0000\u0000\u0000".encodeToByteArray(), tags)
        assertEquals(M1_SINGLE_PACKET_PAGE_PROFILE, DeterministicOggOpusMuxer(config()).layoutProfile)
    }
}

private fun restore(
    snapshot: OggOpusMuxSnapshot,
    config: OggOpusStreamConfig,
    replayedPendingPacket: SequencedRawOpusPacket? = snapshot.pendingPacket?.let {
        packet(it.sequence)
    },
): OggOpusMuxResult<DeterministicOggOpusMuxer> = DeterministicOggOpusMuxer.restore(
    snapshot,
    config,
    OggOpusResumeMode.REPLAYABLE_SEQUENCE_SOURCE,
    replayedPendingPacket,
)

private fun onePacketBytes(config: OggOpusStreamConfig): ByteArray {
    val muxer = DeterministicOggOpusMuxer(config)
    accepted(muxer.accept(packet(config.firstAudioSequence, 0x48, 0x11, 0x22)))
    return accepted(muxer.finish()).bytes.copyBytes()
}

private fun config(): OggOpusStreamConfig = OggOpusStreamConfig(
    configurationId = "opus-16000-mono-20ms-v1",
    serialNumber = 0x0102_0304u,
    firstAudioSequence = 10uL,
    channelCount = 1u,
    inputSampleRateHz = 16_000u,
    preSkip48kSamples = 312u,
    expectedFrameDurationUs = 20_000u,
)

private fun packet(
    sequence: ULong,
    vararg content: Byte,
    payload: ByteArray = if (content.isEmpty()) byteArrayOf(0x48) else content,
    discontinuityBefore: Boolean = false,
    replayBinding: OpusPacketReplayBinding? = TEST_REPLAY_BINDING,
): SequencedRawOpusPacket = SequencedRawOpusPacket(
    sequence = sequence,
    payload = OpaqueBytes.copyOf(payload),
    discontinuityBefore = discontinuityBefore,
    replayBinding = replayBinding,
)

private fun bytes(vararg content: Byte): OpaqueBytes = OpaqueBytes.copyOf(content)

private fun <T> accepted(result: OggOpusMuxResult<T>): T = when (result) {
    is OggOpusMuxResult.Accepted -> result.value
    is OggOpusMuxResult.Rejected -> error("Expected accepted result, got ${result.failure}")
}

private fun rejected(result: OggOpusMuxResult<*>): OggOpusMuxFailure =
    assertIs<OggOpusMuxResult.Rejected>(result).failure

private fun composed(result: OggOpusFragmentCompositionResult): OggOpusPageFragment =
    assertIs<OggOpusFragmentCompositionResult.Composed>(result).fragment

private fun compositionRejected(
    result: OggOpusFragmentCompositionResult,
): OggOpusFragmentCompositionFailure =
    assertIs<OggOpusFragmentCompositionResult.Rejected>(result).failure

private data class Page(
    val headerType: Int,
    val granule: ULong,
    val serial: UInt,
    val sequence: UInt,
    val laces: List<Int>,
    val payload: ByteArray,
    val crcValid: Boolean,
)

private fun parsePages(bytes: ByteArray, requireValidCrc: Boolean = true): List<Page> {
    val pages = mutableListOf<Page>()
    var offset = 0
    while (offset < bytes.size) {
        require(offset + 27 <= bytes.size)
        require(bytes.copyOfRange(offset, offset + 4).decodeToString() == "OggS")
        require(bytes[offset + 4] == 0.toByte())
        val segmentCount = bytes[offset + 26].toInt() and 0xff
        val segmentTableEnd = offset + 27 + segmentCount
        require(segmentTableEnd <= bytes.size)
        val laces = (0 until segmentCount).map { bytes[offset + 27 + it].toInt() and 0xff }
        val end = segmentTableEnd + laces.sum()
        require(end <= bytes.size)
        val pageBytes = bytes.copyOfRange(offset, end)
        val crcValid = pageBytes.uintLe(22) == independentOggCrc(pageBytes)
        if (requireValidCrc) require(crcValid)
        pages += Page(
            headerType = bytes[offset + 5].toInt() and 0xff,
            granule = bytes.ulongLe(offset + 6),
            serial = bytes.uintLe(offset + 14),
            sequence = bytes.uintLe(offset + 18),
            laces = laces,
            payload = bytes.copyOfRange(segmentTableEnd, end),
            crcValid = crcValid,
        )
        offset = end
    }
    require(offset == bytes.size)
    return pages
}

/** Bit-at-a-time oracle, intentionally independent from the production lookup-table CRC. */
private fun independentOggCrc(page: ByteArray): UInt {
    var crc = 0u
    for (index in page.indices) {
        val value = if (index in 22..25) 0u else page[index].toUInt() and 0xffu
        crc = crc xor (value shl 24)
        repeat(8) {
            crc = if ((crc and 0x8000_0000u) != 0u) {
                (crc shl 1) xor 0x04c1_1db7u
            } else {
                crc shl 1
            }
        }
    }
    return crc
}

private fun ByteArray.uintLe(offset: Int): UInt {
    var value = 0u
    repeat(4) { byte -> value = value or ((this[offset + byte].toUInt() and 0xffu) shl (byte * 8)) }
    return value
}

private fun ByteArray.ulongLe(offset: Int): ULong {
    var value = 0uL
    repeat(8) { byte -> value = value or ((this[offset + byte].toULong() and 0xffuL) shl (byte * 8)) }
    return value
}

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

private const val GOLDEN_SINGLE_PACKET_STREAM =
    "4f676753000200000000000000000403020100000000da616e3901134f7075734865616401013801803e" +
        "00000000004f676753000000000000000000000403020101000000ba1398e201144f7075735461677304" +
        "00000067756d69000000004f6767530004c00300000000000004030201020000004702777e0103481122"

private val TEST_REPLAY_BINDING = OpusPacketReplayBinding("11".repeat(32))
private val OTHER_REPLAY_BINDING = OpusPacketReplayBinding("22".repeat(32))
