#include "gumi/omi_v3012_functional_io.h"

#include <errno.h>
#include <stddef.h>
#include <stdint.h>

#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/pwm.h>
#include <zephyr/kernel.h>

#define GUMI_PRIVACY_RED_PERCENT UINT32_C(100)
#define GUMI_AMBER_GREEN_PERCENT UINT32_C(30)
#define GUMI_VOICE_BLUE_PERCENT UINT32_C(50)

typedef struct {
    uint16_t duration_ms;
    bool on;
} haptic_segment;

static const haptic_segment haptic_ready[] = {{80U, true}};
static const haptic_segment haptic_recording_started[] = {
    {80U, true}, {70U, false}, {80U, true},
};
static const haptic_segment haptic_recording_stopped[] = {{220U, true}};
static const haptic_segment haptic_refused[] = {
    {80U, true}, {70U, false}, {80U, true}, {70U, false}, {80U, true},
};
static const haptic_segment haptic_fault[] = {
    {200U, true}, {100U, false}, {200U, true}, {100U, false}, {200U, true},
};

static const struct pwm_dt_spec red = PWM_DT_SPEC_GET(DT_NODELABEL(led_red));
static const struct pwm_dt_spec green = PWM_DT_SPEC_GET(DT_NODELABEL(led_green));
static const struct pwm_dt_spec blue = PWM_DT_SPEC_GET(DT_NODELABEL(led_blue));
static const struct gpio_dt_spec button =
    GPIO_DT_SPEC_GET_OR(DT_NODELABEL(usr_btn), gpios, {0});
static const struct gpio_dt_spec motor =
    GPIO_DT_SPEC_GET_OR(DT_NODELABEL(motor_pin), gpios, {0});

K_MUTEX_DEFINE(gumi_haptic_lock);
static struct k_work_delayable gumi_haptic_work;
static const haptic_segment *gumi_haptic_segments;
static size_t gumi_haptic_segment_count;
static size_t gumi_haptic_segment_index;
static gumi_feedback_pattern gumi_previous_pattern = GUMI_FEEDBACK_PATTERN_NONE;
static uint64_t gumi_pattern_started_at_ms;

static int set_pwm_percent(const struct pwm_dt_spec *led, uint32_t percent)
{
    uint64_t pulse;

    if (!pwm_is_ready_dt(led)) {
        return -ENODEV;
    }
    if (percent > UINT32_C(100)) {
        return -EINVAL;
    }
    pulse = ((uint64_t)led->period * percent) / UINT64_C(100);
    return pwm_set_pulse_dt(led, (uint32_t)pulse);
}

static int set_rgb(uint32_t red_percent, uint32_t green_percent, uint32_t blue_percent)
{
    int error;

    error = set_pwm_percent(&red, red_percent);
    if (error < 0) {
        return error;
    }
    error = set_pwm_percent(&green, green_percent);
    if (error < 0) {
        return error;
    }
    return set_pwm_percent(&blue, blue_percent);
}

static bool pulse_in_cycle(
    uint64_t elapsed_ms,
    uint64_t cycle_ms,
    uint64_t first_start_ms,
    uint64_t first_end_ms,
    uint64_t second_start_ms,
    uint64_t second_end_ms
)
{
    uint64_t position = cycle_ms == UINT64_C(0)
        ? UINT64_C(0)
        : elapsed_ms % cycle_ms;

    return (position >= first_start_ms && position < first_end_ms) ||
           (position >= second_start_ms && position < second_end_ms);
}

static void haptic_work_handler(struct k_work *work)
{
    const haptic_segment *segment;

    ARG_UNUSED(work);
    k_mutex_lock(&gumi_haptic_lock, K_FOREVER);
    if (gumi_haptic_segments == NULL ||
        gumi_haptic_segment_index >= gumi_haptic_segment_count) {
        (void)gpio_pin_set_dt(&motor, 0);
        gumi_haptic_segments = NULL;
        gumi_haptic_segment_count = 0U;
        gumi_haptic_segment_index = 0U;
        k_mutex_unlock(&gumi_haptic_lock);
        return;
    }
    segment = &gumi_haptic_segments[gumi_haptic_segment_index];
    gumi_haptic_segment_index += 1U;
    (void)gpio_pin_set_dt(&motor, segment->on ? 1 : 0);
    (void)k_work_reschedule(&gumi_haptic_work, K_MSEC(segment->duration_ms));
    k_mutex_unlock(&gumi_haptic_lock);
}

int gumi_omi_v3012_functional_io_init(void)
{
    int error;

    if (!pwm_is_ready_dt(&red) || !pwm_is_ready_dt(&green) ||
        !pwm_is_ready_dt(&blue) || !gpio_is_ready_dt(&button) ||
        !gpio_is_ready_dt(&motor)) {
        return -ENODEV;
    }
    error = gpio_pin_configure_dt(&button, GPIO_INPUT);
    if (error < 0) {
        return error;
    }
    error = gpio_pin_configure_dt(&motor, GPIO_OUTPUT_INACTIVE);
    if (error < 0) {
        return error;
    }
    k_work_init_delayable(&gumi_haptic_work, haptic_work_handler);
    return set_rgb(0U, 0U, 0U);
}

int gumi_omi_v3012_functional_button_pressed(bool *pressed)
{
    int level;

    if (pressed == NULL) {
        return -EINVAL;
    }
    level = gpio_pin_get_dt(&button);
    if (level < 0) {
        return level;
    }
    *pressed = level != 0;
    return 0;
}

int gumi_omi_v3012_functional_privacy_set(bool asserted)
{
    return asserted ? set_rgb(GUMI_PRIVACY_RED_PERCENT, 0U, 0U)
                    : set_rgb(0U, 0U, 0U);
}

int gumi_omi_v3012_functional_feedback_apply(
    gumi_feedback_pattern pattern,
    uint64_t at_ms
)
{
    uint64_t elapsed;
    bool active;
    uint32_t level;

    if (pattern < GUMI_FEEDBACK_PATTERN_NONE ||
        pattern > GUMI_FEEDBACK_PATTERN_DISCONNECTED_STATUS) {
        return -EINVAL;
    }
    if (pattern != gumi_previous_pattern) {
        gumi_previous_pattern = pattern;
        gumi_pattern_started_at_ms = at_ms;
    }
    elapsed = at_ms >= gumi_pattern_started_at_ms
        ? at_ms - gumi_pattern_started_at_ms
        : UINT64_C(0);

    switch (pattern) {
        case GUMI_FEEDBACK_PATTERN_NONE:
            return set_rgb(0U, 0U, 0U);
        case GUMI_FEEDBACK_PATTERN_PRIVACY_RECORDING:
        case GUMI_FEEDBACK_PATTERN_PRIVACY_UNKNOWN:
            return set_rgb(GUMI_PRIVACY_RED_PERCENT, 0U, 0U);
        case GUMI_FEEDBACK_PATTERN_PRIVACY_VOICE_TURN:
            active = (elapsed % UINT64_C(500)) < UINT64_C(250);
            return set_rgb(
                GUMI_PRIVACY_RED_PERCENT,
                0U,
                active ? GUMI_VOICE_BLUE_PERCENT : 0U
            );
        case GUMI_FEEDBACK_PATTERN_BOOTING:
            active = pulse_in_cycle(
                elapsed, UINT64_C(2400), 0U, 200U, 400U, 600U
            );
            return set_rgb(0U, 0U, active ? 100U : 0U);
        case GUMI_FEEDBACK_PATTERN_PAIRING:
            active = (elapsed % UINT64_C(500)) < UINT64_C(250);
            return set_rgb(0U, 0U, active ? 100U : 0U);
        case GUMI_FEEDBACK_PATTERN_UPDATING:
            level = (uint32_t)(elapsed % UINT64_C(2000));
            level = level <= 1000U ? level / 10U : (2000U - level) / 10U;
            return set_rgb(0U, 0U, level);
        case GUMI_FEEDBACK_PATTERN_VALIDATING:
            active = pulse_in_cycle(
                elapsed, UINT64_C(1800), 0U, 150U, 300U, 450U
            );
            return set_rgb(0U, 0U, active ? 100U : 0U);
        case GUMI_FEEDBACK_PATTERN_RECOVERY_REQUIRED:
            active = pulse_in_cycle(
                elapsed, UINT64_C(3000), 0U, 300U, 600U, 900U
            );
            return set_rgb(
                active ? 100U : 0U,
                active ? GUMI_AMBER_GREEN_PERCENT : 0U,
                0U
            );
        case GUMI_FEEDBACK_PATTERN_RECOVERABLE_FAULT:
            active = pulse_in_cycle(
                elapsed, UINT64_C(2450), 0U, 150U, 300U, 450U
            ) || (elapsed % UINT64_C(2450) >= UINT64_C(600) &&
                  elapsed % UINT64_C(2450) < UINT64_C(750));
            return set_rgb(
                active ? 100U : 0U,
                active ? GUMI_AMBER_GREEN_PERCENT : 0U,
                0U
            );
        case GUMI_FEEDBACK_PATTERN_CHARGING:
            active = (elapsed % UINT64_C(4000)) < UINT64_C(200);
            return set_rgb(0U, active ? 100U : 0U, 0U);
        case GUMI_FEEDBACK_PATTERN_LOW_POWER:
            active = (elapsed % UINT64_C(10000)) < UINT64_C(200);
            return set_rgb(
                active ? 100U : 0U,
                active ? GUMI_AMBER_GREEN_PERCENT : 0U,
                0U
            );
        case GUMI_FEEDBACK_PATTERN_READY_LINK_STATUS:
            return set_rgb(0U, 0U, elapsed < UINT64_C(300) ? 100U : 0U);
        case GUMI_FEEDBACK_PATTERN_DISCONNECTED_STATUS:
            active = elapsed < UINT64_C(300);
            return set_rgb(
                active ? 100U : 0U,
                active ? GUMI_AMBER_GREEN_PERCENT : 0U,
                0U
            );
        default:
            return -EINVAL;
    }
}

int gumi_omi_v3012_functional_haptic_play(gumi_capture_haptic haptic)
{
    const haptic_segment *segments = NULL;
    size_t count = 0U;

    switch (haptic) {
        case GUMI_CAPTURE_HAPTIC_NONE:
            break;
        case GUMI_CAPTURE_HAPTIC_READY:
        case GUMI_CAPTURE_HAPTIC_VOICE_READY:
            segments = haptic_ready;
            count = ARRAY_SIZE(haptic_ready);
            break;
        case GUMI_CAPTURE_HAPTIC_RECORDING_STARTED:
            segments = haptic_recording_started;
            count = ARRAY_SIZE(haptic_recording_started);
            break;
        case GUMI_CAPTURE_HAPTIC_RECORDING_STOPPED:
            segments = haptic_recording_stopped;
            count = ARRAY_SIZE(haptic_recording_stopped);
            break;
        case GUMI_CAPTURE_HAPTIC_REFUSED:
            segments = haptic_refused;
            count = ARRAY_SIZE(haptic_refused);
            break;
        case GUMI_CAPTURE_HAPTIC_FAULT:
            segments = haptic_fault;
            count = ARRAY_SIZE(haptic_fault);
            break;
        default:
            return -EINVAL;
    }

    (void)k_work_cancel_delayable(&gumi_haptic_work);
    k_mutex_lock(&gumi_haptic_lock, K_FOREVER);
    (void)gpio_pin_set_dt(&motor, 0);
    gumi_haptic_segments = segments;
    gumi_haptic_segment_count = count;
    gumi_haptic_segment_index = 0U;
    if (segments != NULL) {
        (void)k_work_reschedule(&gumi_haptic_work, K_NO_WAIT);
    }
    k_mutex_unlock(&gumi_haptic_lock);
    return 0;
}
