#include <errno.h>
#include <stdint.h>
#include <string.h>

#include <zephyr/kernel.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/ztest.h>

#include "gumi/omi_v3012_codec.h"

#define FIRST_SESSION_ID UINT64_C(101)
#define REOPEN_CYCLES 16U

static int16_t zero_samples[16000];
static uint8_t first_packet[GUMI_OMI_V3012_CODEC_MAX_PACKET_BYTES];
static uint8_t current_first_packet[GUMI_OMI_V3012_CODEC_MAX_PACKET_BYTES];
static size_t first_packet_size;
static size_t current_first_packet_size;
static uint64_t expected_session_id;
static uint64_t callback_count;
static uint64_t last_packet_sequence;
static uint64_t fault_session_id;
static int fault_error;
static int self_close_error;
static atomic_t callback_error = ATOMIC_INIT(0);
static atomic_t fault_count = ATOMIC_INIT(0);
static atomic_t attempt_self_close = ATOMIC_INIT(0);

static void reset_callback_observation(uint64_t session_id)
{
    expected_session_id = session_id;
    callback_count = UINT64_C(0);
    last_packet_sequence = UINT64_C(0);
    current_first_packet_size = 0U;
    memset(current_first_packet, 0, sizeof(current_first_packet));
    atomic_set(&callback_error, 0);
    atomic_set(&fault_count, 0);
    fault_session_id = UINT64_C(0);
    fault_error = 0;
    self_close_error = 0;
    atomic_set(&attempt_self_close, 0);
}

static void packet_handler(const gumi_omi_v3012_codec_packet *packet, void *context)
{
    gumi_omi_v3012_codec_close_result ignored;

    ARG_UNUSED(context);
    if (packet == NULL || packet->session_id != expected_session_id ||
        packet->packet_sequence != callback_count + UINT64_C(1) ||
        packet->bytes == NULL || packet->size == 0U ||
        packet->size > GUMI_OMI_V3012_CODEC_MAX_PACKET_BYTES ||
        packet->pcm_sample_count != GUMI_OMI_V3012_CODEC_FRAME_SAMPLES) {
        atomic_set(&callback_error, -EINVAL);
        return;
    }
    if (callback_count == UINT64_C(0)) {
        current_first_packet_size = packet->size;
        memcpy(current_first_packet, packet->bytes, packet->size);
    }
    callback_count += UINT64_C(1);
    last_packet_sequence = packet->packet_sequence;
    if (atomic_cas(&attempt_self_close, 1, 0)) {
        self_close_error = gumi_omi_v3012_codec_close(
            packet->session_id,
            GUMI_OMI_V3012_CODEC_CLOSE_DISCARD,
            &ignored
        );
    }
}

static void fault_handler(uint64_t session_id, int error, void *context)
{
    ARG_UNUSED(context);
    fault_session_id = session_id;
    fault_error = error;
    atomic_inc(&fault_count);
}

static void assert_clean_close(
    const gumi_omi_v3012_codec_close_result *result,
    uint64_t session_id,
    uint64_t submitted,
    uint64_t emitted,
    uint64_t discarded,
    uint64_t packets
)
{
    zassert_equal(result->session_id, session_id, "wrong close session");
    zassert_equal(result->submitted_samples, submitted, "wrong submitted sample count");
    zassert_equal(result->emitted_samples, emitted, "wrong emitted sample count");
    zassert_equal(result->discarded_samples, discarded, "wrong discarded sample count");
    zassert_equal(result->emitted_packets, packets, "wrong emitted packet count");
    zassert_equal(result->terminal_error, 0, "unexpected terminal error");
    zassert_equal(emitted + discarded, submitted, "close accounting is not exact");
    zassert_equal(atomic_get(&callback_error), 0, "packet callback contract failed");
}

ZTEST(codec_port_lifecycle, test_session_reset_and_close_barriers)
{
    gumi_omi_v3012_codec_close_result result;
    uint64_t callback_barrier_count;
    unsigned int barrier_yield;
    unsigned int cycle;
    int error;

    zassert_equal(
        gumi_omi_v3012_codec_init(packet_handler, fault_handler, NULL),
        0,
        "codec init failed"
    );
    zassert_equal(
        gumi_omi_v3012_codec_get_truth(),
        GUMI_OMI_V3012_CODEC_CLOSED,
        "codec did not initialize closed"
    );

    reset_callback_observation(FIRST_SESSION_ID);
    zassert_equal(gumi_omi_v3012_codec_open(FIRST_SESSION_ID), 0, "first open failed");
    zassert_equal(
        gumi_omi_v3012_codec_submit_pcm(UINT64_C(999), zero_samples, 320U),
        -ESTALE,
        "stale producer token was accepted"
    );
    zassert_equal(
        gumi_omi_v3012_codec_submit_pcm(FIRST_SESSION_ID, zero_samples, 640U),
        0,
        "first PCM submission failed"
    );
    zassert_equal(
        gumi_omi_v3012_codec_close(
            FIRST_SESSION_ID,
            GUMI_OMI_V3012_CODEC_CLOSE_DRAIN_COMPLETE,
            &result
        ),
        0,
        "first drain close failed"
    );
    assert_clean_close(&result, FIRST_SESSION_ID, 640U, 640U, 0U, 2U);
    zassert_equal(callback_count, 2U, "drain did not emit both complete frames");
    zassert_equal(last_packet_sequence, 2U, "packet sequence was not monotonic");
    first_packet_size = current_first_packet_size;
    memcpy(first_packet, current_first_packet, first_packet_size);
    callback_barrier_count = callback_count;
    for (barrier_yield = 0U; barrier_yield < 32U; barrier_yield += 1U) {
        k_yield();
    }
    zassert_equal(
        callback_count,
        callback_barrier_count,
        "callback ran after successful close returned"
    );
    zassert_equal(
        gumi_omi_v3012_codec_submit_pcm(FIRST_SESSION_ID, zero_samples, 320U),
        -ESTALE,
        "closed session accepted late PCM"
    );

    reset_callback_observation(UINT64_C(102));
    zassert_equal(gumi_omi_v3012_codec_open(UINT64_C(102)), 0, "partial open failed");
    zassert_equal(
        gumi_omi_v3012_codec_submit_pcm(UINT64_C(102), zero_samples, 100U),
        0,
        "partial submission failed"
    );
    zassert_equal(
        gumi_omi_v3012_codec_close(
            UINT64_C(102),
            GUMI_OMI_V3012_CODEC_CLOSE_DRAIN_COMPLETE,
            &result
        ),
        0,
        "partial drain close failed"
    );
    assert_clean_close(&result, UINT64_C(102), 100U, 0U, 100U, 0U);
    zassert_equal(callback_count, 0U, "partial frame escaped the session");

    reset_callback_observation(UINT64_C(103));
    zassert_equal(gumi_omi_v3012_codec_open(UINT64_C(103)), 0, "reset open failed");
    zassert_equal(
        gumi_omi_v3012_codec_submit_pcm(UINT64_C(103), zero_samples, 320U),
        0,
        "reset submission failed"
    );
    zassert_equal(
        gumi_omi_v3012_codec_close(
            UINT64_C(103),
            GUMI_OMI_V3012_CODEC_CLOSE_DRAIN_COMPLETE,
            &result
        ),
        0,
        "reset drain close failed"
    );
    assert_clean_close(&result, UINT64_C(103), 320U, 320U, 0U, 1U);
    zassert_equal(last_packet_sequence, 1U, "new session sequence did not reset");
    zassert_equal(current_first_packet_size, first_packet_size, "fresh Opus size drifted");
    zassert_mem_equal(
        current_first_packet,
        first_packet,
        first_packet_size,
        "fresh Opus state did not reproduce the first packet"
    );

    reset_callback_observation(UINT64_C(104));
    zassert_equal(gumi_omi_v3012_codec_open(UINT64_C(104)), 0, "self-close open failed");
    atomic_set(&attempt_self_close, 1);
    zassert_equal(
        gumi_omi_v3012_codec_submit_pcm(UINT64_C(104), zero_samples, 320U),
        0,
        "self-close submission failed"
    );
    zassert_equal(
        gumi_omi_v3012_codec_close(
            UINT64_C(104),
            GUMI_OMI_V3012_CODEC_CLOSE_DRAIN_COMPLETE,
            &result
        ),
        0,
        "outer close failed"
    );
    assert_clean_close(&result, UINT64_C(104), 320U, 320U, 0U, 1U);
    zassert_equal(self_close_error, -EDEADLK, "codec callback could self-join");

    reset_callback_observation(UINT64_C(105));
    k_sched_lock();
    zassert_equal(gumi_omi_v3012_codec_open(UINT64_C(105)), 0, "discard open failed");
    zassert_equal(
        gumi_omi_v3012_codec_submit_pcm(UINT64_C(105), zero_samples, 320U),
        0,
        "discard submission failed"
    );
    error = gumi_omi_v3012_codec_close(
        UINT64_C(105),
        GUMI_OMI_V3012_CODEC_CLOSE_DISCARD,
        &result
    );
    k_sched_unlock();
    zassert_equal(error, 0, "discard close failed");
    assert_clean_close(&result, UINT64_C(105), 320U, 0U, 320U, 0U);
    zassert_equal(callback_count, 0U, "discard close emitted queued audio");

    reset_callback_observation(UINT64_C(106));
    k_sched_lock();
    zassert_equal(gumi_omi_v3012_codec_open(UINT64_C(106)), 0, "overflow open failed");
    zassert_equal(
        gumi_omi_v3012_codec_submit_pcm(UINT64_C(106), zero_samples, 16000U),
        0,
        "capacity-sized submission failed"
    );
    error = gumi_omi_v3012_codec_submit_pcm(UINT64_C(106), zero_samples, 320U);
    k_sched_unlock();
    zassert_equal(error, -ENOSPC, "ring exhaustion did not fail closed");
    zassert_equal(atomic_get(&fault_count), 1, "overflow fault callback count is wrong");
    zassert_equal(fault_session_id, UINT64_C(106), "overflow fault session is wrong");
    zassert_equal(fault_error, -ENOSPC, "overflow fault code is wrong");
    zassert_equal(
        gumi_omi_v3012_codec_close(
            UINT64_C(106),
            GUMI_OMI_V3012_CODEC_CLOSE_DISCARD,
            &result
        ),
        -ENOSPC,
        "faulted close did not preserve terminal error"
    );
    zassert_equal(result.session_id, UINT64_C(106), "fault close session is wrong");
    zassert_equal(result.submitted_samples, 16000U, "fault close submitted count is wrong");
    zassert_equal(result.emitted_samples, 0U, "faulted session emitted audio");
    zassert_equal(result.discarded_samples, 16000U, "fault close discard count is wrong");
    zassert_equal(result.terminal_error, -ENOSPC, "fault close terminal error is wrong");
    zassert_equal(callback_count, 0U, "faulted session emitted a packet");

    for (cycle = 0U; cycle < REOPEN_CYCLES; cycle += 1U) {
        uint64_t session_id = UINT64_C(1000) + cycle;

        reset_callback_observation(session_id);
        zassert_equal(gumi_omi_v3012_codec_open(session_id), 0, "cycle open failed");
        zassert_equal(
            gumi_omi_v3012_codec_submit_pcm(session_id, zero_samples, 320U),
            0,
            "cycle submit failed"
        );
        zassert_equal(
            gumi_omi_v3012_codec_close(
                session_id,
                GUMI_OMI_V3012_CODEC_CLOSE_DRAIN_COMPLETE,
                &result
            ),
            0,
            "cycle close failed"
        );
        assert_clean_close(&result, session_id, 320U, 320U, 0U, 1U);
        zassert_equal(callback_count, 1U, "cycle callback count is wrong");
        zassert_equal(last_packet_sequence, 1U, "cycle sequence leaked across sessions");
        zassert_equal(current_first_packet_size, first_packet_size, "cycle Opus size drifted");
        zassert_mem_equal(
            current_first_packet,
            first_packet,
            first_packet_size,
            "cycle Opus state leaked across sessions"
        );
    }

    zassert_equal(
        gumi_omi_v3012_codec_get_truth(),
        GUMI_OMI_V3012_CODEC_CLOSED,
        "codec did not finish closed"
    );
}

ZTEST_SUITE(codec_port_lifecycle, NULL, NULL, NULL, NULL, NULL);
