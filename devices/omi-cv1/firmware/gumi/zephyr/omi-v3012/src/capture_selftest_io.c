#include "gumi/omi_v3012_capture_selftest.h"

#include <errno.h>
#include <stdint.h>

#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/pwm.h>

#define GUMI_DIAGNOSTIC_RED_PERCENT UINT32_C(100)

static const struct pwm_dt_spec red = PWM_DT_SPEC_GET(DT_NODELABEL(led_red));
static const struct pwm_dt_spec green = PWM_DT_SPEC_GET(DT_NODELABEL(led_green));
static const struct pwm_dt_spec blue = PWM_DT_SPEC_GET(DT_NODELABEL(led_blue));
static const struct gpio_dt_spec button =
    GPIO_DT_SPEC_GET_OR(DT_NODELABEL(usr_btn), gpios, {0});

static int set_pwm_percent(const struct pwm_dt_spec *led, uint32_t percent)
{
    uint32_t pulse;

    if (!pwm_is_ready_dt(led)) {
        return -ENODEV;
    }
    if (percent > UINT32_C(100)) {
        return -EINVAL;
    }
    pulse = (led->period / UINT32_C(100)) * percent;
    return pwm_set_pulse_dt(led, pulse);
}

int gumi_omi_v3012_capture_selftest_privacy_set(bool asserted)
{
    return set_pwm_percent(
        &red,
        asserted ? GUMI_DIAGNOSTIC_RED_PERCENT : UINT32_C(0)
    );
}

int gumi_omi_v3012_capture_selftest_io_init(void)
{
    int error;

    if (!pwm_is_ready_dt(&red) || !pwm_is_ready_dt(&green) ||
        !pwm_is_ready_dt(&blue) || !gpio_is_ready_dt(&button)) {
        return -ENODEV;
    }
    error = gpio_pin_configure_dt(&button, GPIO_INPUT);
    if (error < 0) {
        return error;
    }
    error = set_pwm_percent(&green, UINT32_C(0));
    if (error < 0) {
        return error;
    }
    error = set_pwm_percent(&blue, UINT32_C(0));
    if (error < 0) {
        return error;
    }
    return gumi_omi_v3012_capture_selftest_privacy_set(false);
}

int gumi_omi_v3012_capture_selftest_button_pressed(bool *pressed)
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
