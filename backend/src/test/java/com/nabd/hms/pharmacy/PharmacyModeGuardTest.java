package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PHL-004: the mode ladder (pure logic of the request guard). */
class PharmacyModeGuardTest {

    @Test
    void externalIsRefusedWith403AndHybridNeedingInHouseWith409() {
        assertThatThrownBy(() -> PharmacyModeGuard.check(PharmacyMode.EXTERNAL, PharmacyMode.HYBRID))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.typeSlug()).isEqualTo("pharmacy-mode-external");
                });
        assertThatThrownBy(() -> PharmacyModeGuard.check(PharmacyMode.EXTERNAL, PharmacyMode.IN_HOUSE))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.typeSlug()).isEqualTo("pharmacy-mode-external"));
        assertThatThrownBy(() -> PharmacyModeGuard.check(PharmacyMode.HYBRID, PharmacyMode.IN_HOUSE))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.typeSlug()).isEqualTo("feature-requires-inhouse");
                });
    }

    @Test
    void sameOrHigherModePasses() {
        for (PharmacyMode m : PharmacyMode.values()) {
            assertThatCode(() -> PharmacyModeGuard.check(m, PharmacyMode.EXTERNAL)).doesNotThrowAnyException();
            assertThatCode(() -> PharmacyModeGuard.check(m, m)).doesNotThrowAnyException();
        }
        assertThatCode(() -> PharmacyModeGuard.check(PharmacyMode.IN_HOUSE, PharmacyMode.HYBRID)).doesNotThrowAnyException();
    }

    @Test
    void featuresGrowWithTheMode() {
        assertThat(PharmacyMode.EXTERNAL.features).isEmpty();
        assertThat(PharmacyMode.HYBRID.features).containsExactly("items");
        assertThat(PharmacyMode.IN_HOUSE.features).containsExactly("items", "batches", "worklist", "registers", "procurement", "offline");
        assertThat(PharmacyMode.of("garbage")).isEqualTo(PharmacyMode.EXTERNAL);
    }
}
