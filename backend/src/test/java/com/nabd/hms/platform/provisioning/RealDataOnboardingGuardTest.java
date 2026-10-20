package com.nabd.hms.platform.provisioning;

import com.nabd.hms.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OPS-048 AC4: prod-in (prod profile) is blocked unless the flag is explicitly opened after the gate passes. */
class RealDataOnboardingGuardTest {

    private static RealDataOnboardingGuard guard(String mode, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new RealDataOnboardingGuard(env, mode);
    }

    @Test
    void prodProfileWithoutFlagIsBlocked() {
        assertThat(guard("", "prod").blocked()).isTrue();
        assertThatThrownBy(() -> guard("", "prod").check()).isInstanceOf(ApiException.class)
                .hasMessageContaining("Phase 0 exit gate has not passed");
    }

    @Test
    void explicitFlagWinsOverProfile() {
        assertThat(guard("open", "prod").blocked()).isFalse();
        assertThat(guard("blocked").blocked()).isTrue();
        assertThat(guard(" open ").blocked()).isFalse();
    }

    @Test
    void nonProdWithoutFlagStaysOpen() {
        assertThat(guard("").blocked()).isFalse();
        guard("").check();
    }

    @Test
    void aTypoFailsTheBootInsteadOfSilentlyOpening() {
        assertThatThrownBy(() -> guard("opn", "prod")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard("true")).isInstanceOf(IllegalStateException.class);
    }
}
