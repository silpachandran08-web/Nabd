package com.nabd.hms.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-013: X-Device-Label is display text: control/format characters and angle brackets go, at most 64 characters. */
class Plt013DeviceLabelTest {

    @Test
    void sanitises() {
        assertThat(RequestMeta.sanitiseDeviceLabel(null)).isEqualTo("Unknown device");
        assertThat(RequestMeta.sanitiseDeviceLabel(" \t​ ")).isEqualTo("Unknown device");
        assertThat(RequestMeta.sanitiseDeviceLabel(" Front\u0000desk\r\nPC‮ 2 ")).isEqualTo("Front desk PC 2");
        assertThat(RequestMeta.sanitiseDeviceLabel("<img src=x>")).isEqualTo("img src=x");
        assertThat(RequestMeta.sanitiseDeviceLabel("x".repeat(100))).hasSize(64);
        assertThat(RequestMeta.sanitiseDeviceLabel("😀".repeat(70)).codePointCount(0, 128)).isEqualTo(64);
    }
}
