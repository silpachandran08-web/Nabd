package com.nabd.control;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.data.redis.connection.stream.StreamRecords;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tenant directory feed. Event data comes from the examples in Core's published schemas (api/events), so a change to the
 * contract shows up here; Core's side is Plt061TenantDirectoryFeedApiTest in the backend.
 */
class TenantFeedTest extends ControlPlaneTestBase {

    static final String STREAM = "ev:in:organization";
    @Autowired ObjectMapper json;
    @Autowired TestRestTemplate http;

    /** The envelope of events.md section 2 around the schema's own example, for this tenant. */
    private void publish(String stream, String type, UUID tenant, long version, Consumer<ObjectNode> change) {
        try {
            ObjectNode data = (ObjectNode) json.readTree(Files.readString(Path.of("../../api/events/" + type + ".schema.json"))).path("examples").get(0);
            data.put("tenantId", tenant.toString()).put("version", version);
            change.accept(data);
            ObjectNode ce = json.createObjectNode().put("specversion", "1.0").put("id", String.valueOf(version)).put("source", "core/organization")
                    .put("type", type).put("subject", tenant.toString()).put("tenantid", tenant.toString()).put("region", "in");
            ce.set("data", data);
            valkey.opsForStream().add(StreamRecords.string(Map.of("ce", json.writeValueAsString(ce))).withStreamKey(stream));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> row(UUID tenant) {
        return jdbc.queryForList("SELECT * FROM tenant_directory WHERE tenant_id = ?", tenant).stream().findFirst().orElse(null);
    }

    @Test
    void healthEndpointAnswers() {
        assertEquals("UP", http.getForObject("/healthz", Map.class).get("status"));
    }

    @Test
    void aTenantCreatedInCoreAppearsInTheDirectoryWithinAMinute() {
        UUID t = UUID.randomUUID();
        long start = System.currentTimeMillis();
        // what go-live publishes: provisioning -> trialing, then the pinned region
        publish(STREAM, "organization.tenant.status_changed.v1", t, 10, d -> d.put("from", "provisioning").put("to", "trialing"));
        publish(STREAM, "organization.tenant.region_pinned.v1", t, 11, d -> d.put("regionCode", "IN").put("cell", "in"));
        eventually(60_000, () -> row(t) != null && row(t).get("cell") != null);
        assertTrue(System.currentTimeMillis() - start < 60_000);
        Map<String, Object> row = row(t);
        assertEquals("trialing", row.get("status"));
        assertEquals("IN", row.get("region_code"));
        assertEquals("in", row.get("cell"));
        assertNull(row.get("brand_id"), "a first clinic's brand and owner are in no event: the directory sync fills them");
    }

    @Test
    void aStatusChangeUpdatesTheRowAndAnOlderOrRepeatedEventNeverOverwritesANewerOne() {
        UUID t = UUID.randomUUID();
        publish(STREAM, "organization.tenant.status_changed.v1", t, 20, d -> d.put("from", "provisioning").put("to", "trialing"));
        publish(STREAM, "organization.tenant.status_changed.v1", t, 30, d -> d.put("from", "trialing").put("to", "suspended"));
        eventually(10_000, () -> row(t) != null && "suspended".equals(row(t).get("status")));
        // at-least-once delivery: the relay may send both again, in any order relative to newer entries
        publish(STREAM, "organization.tenant.status_changed.v1", t, 20, d -> d.put("from", "provisioning").put("to", "trialing"));
        publish(STREAM, "organization.tenant.status_changed.v1", t, 30, d -> d.put("from", "trialing").put("to", "suspended"));
        UUID marker = UUID.randomUUID();
        publish(STREAM, "organization.tenant.status_changed.v1", marker, 31, d -> d.put("to", "active"));
        eventually(10_000, () -> row(marker) != null); // the repeats before it have been handled
        assertEquals("suspended", row(t).get("status"));
        assertEquals(30L, row(t).get("status_version"));
    }

    @Test
    void aNewBranchBringsItsBrandAndOwner() {
        UUID t = UUID.randomUUID(), brand = UUID.randomUUID(), owner = UUID.randomUUID();
        publish(STREAM, "organization.tenant.status_changed.v1", t, 40, d -> d.put("to", "trialing"));
        publish(STREAM, "organization.branch.created.v1", t, 41, d -> d.put("brandId", brand.toString()).put("ownerId", owner.toString()));
        eventually(10_000, () -> row(t) != null && brand.equals(row(t).get("brand_id")));
        assertEquals(owner, row(t).get("owner_id"));
        assertEquals("trialing", row(t).get("status"));
    }

    @Test
    void otherOrganizationEventsAndOtherCellsAreIgnored() {
        UUID other = UUID.randomUUID(), marker = UUID.randomUUID();
        publish(STREAM, "organization.config.changed.v1", other, 50, d -> { });
        publish("ev:ae:organization", "organization.tenant.status_changed.v1", other, 51, d -> d.put("to", "active")); // control-plane.regions = in
        publish(STREAM, "organization.tenant.status_changed.v1", marker, 52, d -> d.put("to", "active"));
        eventually(10_000, () -> row(marker) != null);
        assertNull(row(other));
    }

    @Test
    void aPoisonEventDoesNotBlockTheFeedAndEndsInTheDeadLetterStream() {
        UUID good = UUID.randomUUID();
        long before = size("ev:in:dlq");
        valkey.opsForStream().add(StreamRecords.string(Map.of("ce", "{\"specversion\":\"1.0\",\"type\":\"organization.tenant.status_changed.v1\","
                + "\"tenantid\":\"not-a-uuid\",\"data\":{\"to\":\"active\",\"version\":60}}")).withStreamKey(STREAM));
        publish(STREAM, "organization.tenant.status_changed.v1", good, 61, d -> d.put("to", "active"));
        eventually(10_000, () -> row(good) != null); // not held up behind the poison entry
        eventually(45_000, () -> size("ev:in:dlq") == before + 1); // 5 attempts, one every 5 s
        JsonNode last = json.valueToTree(valkey.opsForStream().reverseRange("ev:in:dlq", org.springframework.data.domain.Range.unbounded()).get(0).getValue());
        assertEquals(STREAM, last.path("originalStream").asString(""));
        assertEquals(TenantFeed.GROUP, last.path("consumer").asString(""));
    }

    private long size(String stream) {
        Long n = valkey.opsForStream().size(stream);
        return n == null ? 0 : n;
    }
}
