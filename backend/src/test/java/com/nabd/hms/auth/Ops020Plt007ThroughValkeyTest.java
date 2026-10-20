package com.nabd.hms.auth;

import com.nabd.hms.support.ValkeyTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * OPS-020 "no behaviour change": every PLT-007 revocation scenario (operator logout, tenant suspension, role and scope
 * change, 12 h cap, step-up binding, staff suspension) run again with the Valkey revocation cache in front.
 */
class Ops020Plt007ThroughValkeyTest extends Plt007SessionRevocationApiTest {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();

    @DynamicPropertySource
    static void valkey(DynamicPropertyRegistry r) {
        r.add("app.valkey.url", VALKEY::url);
        r.add("app.rate-limit.user-per-minute", () -> "100000"); // the fixtures sign in many times from 127.0.0.1
        r.add("app.rate-limit.user-burst", () -> "100000");
        r.add("app.rate-limit.ip-per-minute", () -> "100000");
        r.add("app.rate-limit.ip-burst", () -> "100000");
    }

    @AfterAll
    static void stopValkey() {
        VALKEY.close();
    }
}
