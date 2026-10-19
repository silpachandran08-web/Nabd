package com.nabd.hms.platform.provisioning;

import com.nabd.hms.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Independent QA (S11 OPS-048 AC4): the guard as Spring wires it, with the profile and the REAL_DATA_ONBOARDING
 * variable resolved through the real Environment (env vars reach Spring as properties of the same name).
 */
class S11QaRealDataOnboardingBootTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(RealDataOnboardingGuard.class);

    private static void assertRefuses(RealDataOnboardingGuard g) {
        ApiException e = catchThrowableOfType(g::check, ApiException.class);
        assertThat(e).as("provisioning must be refused").isNotNull();
        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.typeSlug()).isEqualTo("real-data-onboarding-blocked");
    }

    @Test
    void prodProfileWithNothingSetBlocksProvisioning() {
        runner.withPropertyValues("spring.profiles.active=prod").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(RealDataOnboardingGuard.class).blocked()).isTrue();
            assertRefuses(c.getBean(RealDataOnboardingGuard.class));
        });
    }

    @Test
    void prodProfileWithEmptyVariableStaysBlocked() {
        runner.withPropertyValues("spring.profiles.active=prod", "REAL_DATA_ONBOARDING=").run(c ->
                assertThat(c.getBean(RealDataOnboardingGuard.class).blocked()).isTrue());
    }

    @Test
    void prodProfileWithEnvOpenAllowsProvisioning() {
        runner.withPropertyValues("spring.profiles.active=prod", "REAL_DATA_ONBOARDING=open").run(c -> {
            assertThat(c).hasNotFailed();
            RealDataOnboardingGuard g = c.getBean(RealDataOnboardingGuard.class);
            assertThat(g.blocked()).isFalse();
            g.check();
        });
    }

    @Test
    void prodProfileAmongOthersStillBlocks() {
        runner.withPropertyValues("spring.profiles.active=staging,prod").run(c ->
                assertThat(c.getBean(RealDataOnboardingGuard.class).blocked()).isTrue());
    }

    @Test
    void envBlockedBlocksEvenWithoutProdProfile() {
        runner.withPropertyValues("REAL_DATA_ONBOARDING=blocked").run(c -> assertRefuses(c.getBean(RealDataOnboardingGuard.class)));
    }

    @Test
    void nonProdWithNothingSetIsOpen() {
        runner.run(c -> assertThat(c.getBean(RealDataOnboardingGuard.class).blocked()).isFalse());
    }

    @Test
    void wrongCaseOrTypoFailsTheBootRatherThanOpening() {
        runner.withPropertyValues("spring.profiles.active=prod", "REAL_DATA_ONBOARDING=OPEN").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("spring.profiles.active=prod", "REAL_DATA_ONBOARDING=true").run(c -> assertThat(c).hasFailed());
    }

    /** Documents precedence: the Spring property wins over the env variable (both are operator-set, so no bypass). */
    @Test
    void springPropertyTakesPrecedenceOverTheEnvVariable() {
        runner.withPropertyValues("spring.profiles.active=prod", "REAL_DATA_ONBOARDING=open", "app.provisioning.real-data-onboarding=blocked")
                .run(c -> assertThat(c.getBean(RealDataOnboardingGuard.class).blocked()).isTrue());
    }
}
