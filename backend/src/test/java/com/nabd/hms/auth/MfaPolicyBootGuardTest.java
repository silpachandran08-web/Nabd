package com.nabd.hms.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-005: the prod profile cannot boot with forced MFA switched off; the grant rule itself. */
class MfaPolicyBootGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(MfaPolicy.class);

    @Test
    void prodWithEnforcementOffFailsToStart() {
        runner.withPropertyValues("spring.profiles.active=prod", "app.auth.mfa.enforce-privileged=false")
                .run(c -> assertThat(c).hasFailed());
    }

    @Test
    void prodByDefaultOrExplicitlyOnStartsAndEnforces() {
        runner.withPropertyValues("spring.profiles.active=prod").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(MfaPolicy.class).requiredForOperator()).isTrue();
            assertThat(c.getBean(MfaPolicy.class).requiredForOwner()).isTrue();
        });
        runner.withPropertyValues("spring.profiles.active=prod", "app.auth.mfa.enforce-privileged=true").run(c -> assertThat(c).hasNotFailed());
    }

    @Test
    void nonProdMayTurnItOff() {
        runner.withPropertyValues("app.auth.mfa.enforce-privileged=false").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(MfaPolicy.class).requiredForRole(true, List.of())).isFalse();
        });
    }

    @Test
    void grantRule() {
        assertThat(MfaPolicy.privilegedGrants(List.of("billing:view", "billing:refundDiscount"))).isTrue();
        assertThat(MfaPolicy.privilegedGrants(List.of("pharmacy:approve"))).isTrue();
        assertThat(MfaPolicy.privilegedGrants(List.of("queue:view", "patients:edit", "billing.discount"))).isFalse();
        assertThat(MfaPolicy.privilegedRole(true, List.of())).isTrue();
        assertThat(MfaPolicy.privilegedRole(false, List.of())).isFalse();
    }
}
