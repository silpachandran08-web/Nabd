package com.nabd.edge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityTest extends EdgeTestBase {

    @Autowired TestRestTemplate http;
    @Autowired TenantTx tx;
    @Autowired ConversationStore store;
    @Autowired JdbcTemplate appJdbc;

    @Test
    void publicSurfaceIsHealthzOnlyAndPerIpLimitedWithProblemJson() {
        assertEquals(200, http.getForEntity("/healthz", String.class).getStatusCode().value());
        for (String path : new String[] {"/v1/availability", "/internal/v1/tenants/x/availability", "/actuator/prometheus", "/conversations"}) {
            assertEquals(404, http.getForEntity(path, String.class).getStatusCode().value(), path + " must not exist on the public port");
        }
        int limited = 0;
        ResponseEntity<String> last = null;
        for (int i = 0; i < 80; i++) {
            last = http.getForEntity("/healthz", String.class);
            if (last.getStatusCode().value() == 429) {
                limited++;
                assertTrue(last.getHeaders().getContentType().toString().startsWith("application/problem+json"));
                assertEquals("60", last.getHeaders().getFirst("Retry-After"));
            }
        }
        assertTrue(limited > 0, "the 30/min per-IP limit applied");
    }

    @Test
    void rowLevelSecurityKeepsTenantsApart() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        store.load(a, "whatsapp", "hash-rls");
        assertEquals(1, tx.<Integer>run(a, (j, s) -> j.queryForObject("select count(*) from conversations", Integer.class)).intValue());
        assertEquals(0, tx.<Integer>run(b, (j, s) -> j.queryForObject("select count(*) from conversations where contact_hash = 'hash-rls'", Integer.class)).intValue());
        assertEquals(0, appJdbc.queryForObject("select count(*) from conversations", Integer.class).intValue(), "no tenant bound sees nothing");
        assertThrows(DataAccessException.class, () -> tx.run(b, (j, s) -> j.update(
                "insert into conversations (id, tenant_id, channel, contact_hash, expires_at) values (?, ?, 'whatsapp', 'x', now())",
                UUID.randomUUID(), a)), "writing another tenant's row is refused");
    }

    @Test
    void edgeSchemaHasNoClinicalOrRawContactColumns() {
        var cols = superuser().queryForList("select column_name from information_schema.columns where table_schema = 'public' "
                + "and table_name in ('conversations','processed_messages','booking_holds')", String.class);
        for (String banned : new String[] {"phone", "diagnosis", "dob", "email"}) {
            assertTrue(cols.stream().noneMatch(c -> c.contains(banned)), "column containing " + banned);
        }
        for (String banned : new String[] {"name", "first_name", "body", "text", "message"}) {
            assertTrue(!cols.contains(banned), "column " + banned);
        }
    }
}
