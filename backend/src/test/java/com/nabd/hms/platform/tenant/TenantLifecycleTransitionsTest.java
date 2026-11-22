package com.nabd.hms.platform.tenant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-020: the whole 7 x 7 transition table, so a new edge cannot appear (or vanish) unnoticed. */
class TenantLifecycleTransitionsTest {

    private static final List<String> STATES = List.of("provisioning", "trialing", "active", "overdue", "suspended", "offboarding", "offboarded");

    private static final Map<String, Set<String>> EXPECTED = Map.of(
            "provisioning", Set.of("trialing"),
            "trialing", Set.of("active", "suspended", "offboarding"),
            "active", Set.of("overdue", "suspended", "offboarding"),
            "overdue", Set.of("active", "suspended", "offboarding"),
            "suspended", Set.of("active", "offboarding"),
            "offboarding", Set.of("offboarded"),
            "offboarded", Set.of());

    @Test
    void everyPairOfTheSevenStatesIsAllowedOrRefusedExactlyAsTheTableSays() {
        for (String from : STATES) {
            for (String to : STATES) {
                assertThat(TenantLifecycleService.allowedFrom(from).contains(to)).as(from + " -> " + to).isEqualTo(EXPECTED.get(from).contains(to));
            }
        }
    }

    @Test
    void noStateMovesToItselfOffboardedIsTerminalAndOffboardedIsOnlyReachedThroughOffboarding() {
        for (String s : STATES) {
            assertThat(TenantLifecycleService.allowedFrom(s)).as(s).doesNotContain(s);
            if (!"offboarding".equals(s)) {
                assertThat(TenantLifecycleService.allowedFrom(s)).as(s).doesNotContain("offboarded");
            }
        }
        assertThat(TenantLifecycleService.allowedFrom("offboarded")).isEmpty();
        assertThat(TenantLifecycleService.allowedFrom("no-such-state")).isEmpty();
    }

    @Test
    void theWriteBlockLetsOnlySessionExportSupportAndReadOnlyPostsThrough() {
        for (String ok : List.of("/v1/auth/refresh", "/v1/auth/logout", "/v1/auth/mfa/verify", "/v1/setup/export-jobs",
                "/v1/support/tickets", "/v1/support/tickets/redaction-preview",
                "/v1/files/11111111-1111-4111-8111-111111111111/download-url", "/v1/pharmacy/rx-lines/search")) {
            assertThat(SuspendedTenantWriteBlock.ALLOWED.matcher(ok).matches()).as(ok).isTrue();
        }
        for (String blocked : List.of("/v1/queue/check-in", "/v1/patients", "/v1/setup/export-jobs/extra", "/v1/setup/subscription/change",
                "/v1/files/uploads", "/v1/staff/11111111-1111-4111-8111-111111111111", "/v1/authx/refresh",
                "/v1/support/tickets/11111111-1111-4111-8111-111111111111/close")) {
            assertThat(SuspendedTenantWriteBlock.ALLOWED.matcher(blocked).matches()).as(blocked).isFalse();
        }
    }
}
