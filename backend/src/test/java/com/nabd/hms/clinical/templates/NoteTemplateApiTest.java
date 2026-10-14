package com.nabd.hms.clinical.templates;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DOC-027. Written to the ClinicalDocumentApiTest conventions; NOT executed in the authoring environment (no Docker/Testcontainers). */
class NoteTemplateApiTest extends ApiTestBase {

    private static final String URL = "/v1/clinical/note-templates";

    @Test
    void templatesStartEmptyAndRoundTripWithTagsAndFavourite() {
        SeededTenant tenant = seedTenant();
        UUIDHolder doc = doctor(tenant, "tpl1@a.com", "+919600040001");
        assertThat(exchange(URL, HttpMethod.GET, authed(doc.token()), List.class).getBody()).isEmpty();

        ResponseEntity<Map> created = exchange(URL, HttpMethod.POST, authedJsonBody(doc.token(), Map.of(
                "name", "  My follow-up ", "plan", "typed by the doctor", "tags", List.of("Review", "review", " Chronic "))), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("name")).isEqualTo("My follow-up");
        assertThat(created.getBody().get("tags")).isEqualTo(List.of("Review", "Chronic"));
        assertThat(created.getBody().get("favourite")).isEqualTo(false);
        String id = (String) created.getBody().get("id");

        ResponseEntity<Map> fav = exchange(URL + "/" + id, HttpMethod.PATCH, authedJsonBody(doc.token(), Map.of("favourite", true)), Map.class);
        assertThat(fav.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fav.getBody().get("favourite")).isEqualTo(true);
        assertThat(fav.getBody().get("plan")).isEqualTo("typed by the doctor"); // untouched by a partial update

        assertThat(exchange(URL + "/" + id, HttpMethod.DELETE, authed(doc.token()), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(URL, HttpMethod.GET, authed(doc.token()), List.class).getBody()).isEmpty();
    }

    @Test
    void aTemplateBelongsToItsDoctorAndOtherDoctorsGetNotFound() {
        SeededTenant tenant = seedTenant();
        UUIDHolder a = doctor(tenant, "tpl2@a.com", "+919600040002");
        UUIDHolder b = doctor(tenant, "tpl3@a.com", "+919600040003");
        String id = (String) exchange(URL, HttpMethod.POST, authedJsonBody(a.token(), Map.of("name", "Mine")), Map.class).getBody().get("id");

        assertThat(exchange(URL, HttpMethod.GET, authed(b.token()), List.class).getBody()).isEmpty();
        assertThat(exchange(URL + "/" + id, HttpMethod.PATCH, authedJsonBody(b.token(), Map.of("name", "Taken")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(URL + "/" + id, HttpMethod.DELETE, authed(b.token()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(URL, HttpMethod.GET, authed(a.token()), List.class).getBody()).hasSize(1);
    }

    @Test
    void invalidTemplatesAreRefusedAndNeverTruncated() {
        SeededTenant tenant = seedTenant();
        UUIDHolder d = doctor(tenant, "tpl4@a.com", "+919600040004");
        assertThat(exchange(URL, HttpMethod.POST, authedJsonBody(d.token(), Map.of("name", "  ")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(URL, HttpMethod.POST, authedJsonBody(d.token(), Map.of("name", "ok", "plan", "x".repeat(4001))), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(URL, HttpMethod.GET, authed(d.token()), List.class).getBody()).isEmpty();
    }

    @Test
    void readingNeedsClinicalViewAndWritingNeedsClinicalEdit() {
        SeededTenant tenant = seedTenant();
        UUIDHolder d = doctor(tenant, "tpl5@a.com", "+919600040005");
        java.util.UUID viewerRole = seedRole(tenant.id(), "Viewer", false, new com.nabd.hms.common.ModuleGrant("clinical", true, false, false, false, false, false, false));
        String viewer = loginAndGetAccessToken(seedStaff(tenant, viewerRole, "tpl-view@a.com", "+919600040006", false));
        assertThat(exchange(URL, HttpMethod.GET, authed(viewer), List.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(URL, HttpMethod.POST, authedJsonBody(viewer, Map.of("name", "x")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(d.token()).isNotBlank();
    }

    private record UUIDHolder(String token) {
    }

    private final java.util.Map<java.util.UUID, java.util.UUID> ownerRoleByTenant = new java.util.HashMap<>();

    /** One full-access role per tenant (role names may be unique per tenant), several doctors on it. */
    private UUIDHolder doctor(SeededTenant tenant, String email, String phone) {
        java.util.UUID roleId = ownerRoleByTenant.computeIfAbsent(tenant.id(), this::seedFullAccessRole);
        return new UUIDHolder(loginAndGetAccessToken(seedStaff(tenant, roleId, email, phone, false)));
    }
}
