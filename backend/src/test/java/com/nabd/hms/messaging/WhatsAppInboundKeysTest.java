package com.nabd.hms.messaging;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-001 unit: one item and one dedupe key per message, status and template event. */
class WhatsAppInboundKeysTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private List<WhatsAppInboundKeys.Item> split(String json) {
        JsonNode root = mapper.readTree(json);
        return WhatsAppInboundKeys.split(root, mapper);
    }

    @Test
    void messageKeyIsTheWamidAndEveryMessageOfABatchGetsItsOwnRow() {
        var items = split("""
                {"entry":[{"time":1700000000,"changes":[{"field":"messages","value":{"metadata":{"phone_number_id":"109"},
                 "messages":[{"id":"wamid.A","type":"text"},{"id":"wamid.B","type":"text"}]}}]}]}""");
        assertThat(items).extracting(WhatsAppInboundKeys.Item::key).containsExactly("msg:wamid.A", "msg:wamid.B");
        assertThat(items).extracting(WhatsAppInboundKeys.Item::numberId).containsOnly("109");
        assertThat(items.get(0).body().path("kind").asString()).isEqualTo("message");
    }

    @Test
    void statusKeyIsWamidPlusStatusSoDeliveredAndReadAreDifferentRows() {
        var items = split("""
                {"entry":[{"changes":[{"field":"messages","value":{"statuses":[
                 {"id":"wamid.S","status":"delivered"},{"id":"wamid.S","status":"read"},{"id":"wamid.S","status":"read"}]}}]}]}""");
        assertThat(items).extracting(WhatsAppInboundKeys.Item::key).containsExactly("st:wamid.S:delivered", "st:wamid.S:read", "st:wamid.S:read");
    }

    @Test
    void templateKeysCarryIdEventAndTimestamp() {
        var items = split("""
                {"entry":[{"time":1700000123,"changes":[
                 {"field":"message_template_status_update","value":{"message_template_id":"77","event":"APPROVED"}},
                 {"field":"message_template_quality_update","value":{"message_template_id":"77","new_quality_score":"RED","timestamp":"1700000999"}}]}]}""");
        assertThat(items).extracting(WhatsAppInboundKeys.Item::key).containsExactly("tpl:77:APPROVED:1700000123", "tplq:77:RED:1700000999");
    }

    @Test
    void fieldsWithoutAnIdKeyOnAHashOfTheirValueSoIdenticalOnesDedupe() {
        String a = """
                {"entry":[{"changes":[{"field":"phone_number_quality_update","value":{"current_limit":"TIER_1K"}}]}]}""";
        assertThat(split(a).get(0).key()).startsWith("h:phone_number_quality_update:").isEqualTo(split(a).get(0).key());
        assertThat(split(a.replace("TIER_1K", "TIER_10K")).get(0).key()).isNotEqualTo(split(a).get(0).key());
    }

    @Test
    void aSignedBodyWithoutIdsCannotCollapseUnrelatedItemsOntoOneKey() {
        var items = split("""
                {"entry":[{"changes":[{"field":"messages","value":{"messages":[{"type":"text","text":{"body":"one"}},{"type":"text","text":{"body":"two"}}]}}]}]}""");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).key()).isNotEqualTo(items.get(1).key());
    }

    @Test
    void emptyOrUnknownBodiesYieldNoItems() {
        assertThat(split("{}")).isEmpty();
        assertThat(split("{\"entry\":[{\"changes\":[{\"field\":\"messages\",\"value\":{}}]}]}")).isEmpty();
    }
}
