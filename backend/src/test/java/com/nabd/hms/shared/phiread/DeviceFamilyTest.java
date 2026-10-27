package com.nabd.hms.shared.phiread;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceFamilyTest {

    @Test
    void parsesFamiliesAndValidatesIp() {
        assertThat(DeviceFamily.of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)")).isEqualTo("iPhone");
        assertThat(DeviceFamily.of("Mozilla/5.0 (Linux; Android 14; Pixel 8) Mobile")).isEqualTo("Android phone");
        assertThat(DeviceFamily.of(null)).isEqualTo("Unknown");
        assertThat(DeviceFamily.of("x".repeat(5000))).isEqualTo("Other");
        assertThat(DeviceFamily.validIp("203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(DeviceFamily.validIp("2001:db8::1")).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(DeviceFamily.validIp("999.1.1.1")).isNull();
        assertThat(DeviceFamily.validIp("a<b>")).isNull();
        assertThat(DeviceFamily.validIp(null)).isNull();
    }
}
