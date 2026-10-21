package com.nabd.hms.organization.region;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.PhoneNumbers;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OPS-061: the regions table and everything that reads it. The four-region suites (tax, phone, timezone, currency) run
 * the same assertions for IN, AE, SA and QA; the expected values are the ones the story names.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class Ops061RegionsApiTest extends ApiTestBase {

    /** code, currency, tax regime, headline rate (percent; null = none), regulator, zone, phone country code, cell, a local phone, its E.164 */
    record R(String code, String currency, String taxRegime, String rate, String regulator, String zone, String cc, String cell,
             String localPhone, String e164) {
    }

    static final List<R> REGIONS = List.of(
            new R("IN", "INR", "GST", null, "NMC/CDSCO", "Asia/Kolkata", "+91", "in", "98450 22110", "+919845022110"),
            new R("AE", "AED", "VAT", "5.00", "MOHAP/DHA/DoH", "Asia/Dubai", "+971", "ae", "050 123 4567", "+971501234567"),
            new R("SA", "SAR", "VAT", "15.00", "MOH/SFDA", "Asia/Riyadh", "+966", "sa", "0501234567", "+966501234567"),
            new R("QA", "QAR", "NONE", "0.00", "MOPH", "Asia/Qatar", "+974", "qa", "33123456", "+97433123456"));

    static Stream<Arguments> regions() {
        return REGIONS.stream().map(Arguments::of);
    }

    @Autowired ClinicClock clinicClock;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    /** A tenant (seedTenant) moved into a region as provisioning would have created it: region and the region's zone. The
     * triggers are off for that one statement (a test-only shortcut, as in PhoneValidationApiTest); the insert-time default is
     * tested directly in timezoneDefaultsFromTheRegionAndCanNeverBeNull and through real provisioning below. */
    private SeededTenant tenantIn(String region) {
        SeededTenant t = seedTenant();
        try (java.sql.Connection c = superuserConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("UPDATE tenants SET region_code = '" + region + "', region = '" + region + "', timezone = (SELECT default_timezone FROM regions WHERE code = '"
                    + region + "') WHERE id = '" + t.id() + "'");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return t;
    }

    private String doctorToken(SeededTenant t, String tag) {
        return loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "ops061-" + tag + "@a.com", "+9195" + (10000000 + Math.abs(tag.hashCode() % 89999999)), false));
    }

    private String platformToken() {
        return platformLoginAndGetAccessToken(seedOperator("super-ops061-" + UUID.randomUUID() + "@nabd.health", "super_admin", false));
    }

    // ---- AC1: seed ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("regions")
    void theSeededRegionCarriesTheStoryValues(R r) {
        Map row = su.queryForMap("SELECT * FROM regions WHERE code = ?", r.code());
        assertThat(row.get("currency").toString().trim()).isEqualTo(r.currency());
        assertThat(((Number) row.get("minor_units")).intValue()).isEqualTo(2);
        assertThat(row.get("tax_regime")).isEqualTo(r.taxRegime());
        assertThat(row.get("regulator")).isEqualTo(r.regulator());
        assertThat(row.get("default_timezone")).isEqualTo(r.zone());
        assertThat(row.get("phone_country_code")).isEqualTo(r.cc());
        assertThat(row.get("cell")).isEqualTo(r.cell());
        assertThat(row.get("active")).isEqualTo(true);
        if (r.rate() == null) {
            assertThat(row.get("standard_tax_rate")).isNull();
        } else {
            assertThat((BigDecimal) row.get("standard_tax_rate")).isEqualByComparingTo(r.rate());
        }
        assertThat(su.queryForObject("SELECT count(*) FROM regions", Integer.class)).isEqualTo(4);
    }

    @Test
    void getRegionsListsTheFourForStaffAndOperatorsAndNotForAnonymous() {
        SeededTenant t = seedTenant();
        String staff = doctorToken(t, "list");
        for (var call : List.of(Map.entry("/v1/regions", staff), Map.entry("/v1/platform/regions", platformToken()))) {
            ResponseEntity<Map> resp = exchange(call.getKey(), HttpMethod.GET, authed(call.getValue()), Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<Map> items = (List<Map>) resp.getBody().get("items");
            assertThat(items).extracting(m -> m.get("code")).containsExactly("AE", "IN", "QA", "SA");
            Map ae = items.stream().filter(m -> m.get("code").equals("AE")).findFirst().orElseThrow();
            assertThat(ae).containsEntry("currency", "AED").containsEntry("minorUnits", 2).containsEntry("taxRegime", "VAT")
                    .containsEntry("regulator", "MOHAP/DHA/DoH").containsEntry("defaultTimezone", "Asia/Dubai").containsEntry("cell", "ae");
            assertThat(((Number) ae.get("standardTaxRate")).doubleValue()).isEqualTo(5.0);
            assertThat((Map) ae.get("phoneRules")).containsEntry("countryCode", "+971");
        }
        assertThat(exchange("/v1/regions", HttpMethod.GET, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/platform/regions", HttpMethod.GET, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/platform/regions", HttpMethod.GET, authed(staff), Map.class).getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void theApiRoleCannotWriteTheRegionsTable() {
        JdbcTemplate app = new JdbcTemplate(dataSourceFor("nabd_app", "nabd_app"));
        assertThat(app.queryForObject("SELECT count(*) FROM regions", Integer.class)).isEqualTo(4);
        assertThatThrownBy(() -> app.update("UPDATE regions SET currency = 'XXX' WHERE code = 'IN'")).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> app.update("DELETE FROM regions WHERE code = 'IN'")).hasStackTraceContaining("permission denied");
    }

    // ---- AC2 + AC6: one suite, four regions ----------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("regions")
    void aClinicInTheRegionGetsItsTimezoneCurrencyTaxAndPhoneRules(R r) {
        SeededTenant t = tenantIn(r.code());
        // timezone: NOT NULL, defaulted from the region by the database (AC5), read through ClinicClock
        assertThat(su.queryForObject("SELECT timezone FROM tenants WHERE id = ?", String.class, t.id())).isEqualTo(r.zone());
        assertThat(clinicClock.zone(t.id())).isEqualTo(ZoneId.of(r.zone()));
        assertThat(clinicClock.region(t.id())).isEqualTo(r.code());

        String token = doctorToken(t, "all-" + r.code());
        UUID doctor = su.queryForObject("SELECT id FROM staff WHERE tenant_id = ? LIMIT 1", UUID.class, t.id());

        // phone: the region's local format is accepted and stored as E.164 with the region's country code; a short number is refused
        ResponseEntity<Map> reg = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Region Patient", "phone", r.localPhone(), "dob", "1990-01-01", "gender", "female")), Map.class);
        assertThat(reg.getStatusCode()).as(String.valueOf(reg.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(reg.getBody().get("phone")).isEqualTo(r.e164());
        assertThat(r.e164()).startsWith(r.cc());
        assertThat(PhoneNumbers.e164OrNull("12345", r.code())).isNull();
        ResponseEntity<Map> bad = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Region Short", "phone", "12345", "dob", "1990-01-01", "gender", "female")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().get("code")).isEqualTo("INVALID_PHONE");

        // appointment: booked at 10:00 on the clinic's wall clock, stored as the instant in the region's zone
        LocalDate day = clinicClock.today(clinicClock.zone(t.id())).plusDays(1);
        ResponseEntity<Map> wh = exchange("/v1/doctors/" + doctor + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dayOfWeek", day.getDayOfWeek().getValue() % 7, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        assertThat(wh.getStatusCode()).as(String.valueOf(wh.getBody())).isIn(HttpStatus.CREATED, HttpStatus.OK);
        String patientId = (String) reg.getBody().get("id");
        ResponseEntity<Map> appt = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(token, Map.of("doctorId", doctor.toString(),
                "patientId", patientId, "startTime", day.atTime(10, 0).atZone(ZoneId.of(r.zone())).toInstant().toString())), Map.class);
        assertThat(appt.getStatusCode()).as(String.valueOf(appt.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(su.queryForObject("SELECT (start_time AT TIME ZONE ?)::text FROM appointments WHERE id = ?::uuid", String.class,
                r.zone(), appt.getBody().get("id"))).isEqualTo(day + " 10:00:00");

        // invoice: currency from the region, a free-text line without its own rate takes the region's headline rate
        String queueEntry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctor.toString())), Map.class).getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + queueEntry + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
        ResponseEntity<Map> inv = exchange("/v1/billing/checkout/" + queueEntry, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "X",
                        "category", "Service", "quantity", 1, "unitPrice", 100)))), Map.class);
        assertThat(inv.getStatusCode()).as(String.valueOf(inv.getBody())).isIn(HttpStatus.OK, HttpStatus.CREATED);
        assertThat(inv.getBody().get("currency")).isEqualTo(r.currency());
        BigDecimal expectedTax = new BigDecimal(r.rate() == null ? "0" : r.rate());
        assertThat(new BigDecimal(inv.getBody().get("tax").toString())).isEqualByComparingTo(expectedTax);

        // reports: the day's money report uses the same currency and tax regime
        ResponseEntity<Map> money = exchange("/v1/reports/money?from=" + clinicClock.today(t.id()) + "&to=" + clinicClock.today(t.id()),
                HttpMethod.GET, authed(token), Map.class);
        assertThat(money.getStatusCode()).as(String.valueOf(money.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(money.getBody().get("currency")).isEqualTo(r.currency());
        assertThat(money.getBody().get("taxLabel")).isEqualTo(r.taxRegime());
    }

    @ParameterizedTest
    @MethodSource("regions")
    void theTablePhoneRuleAgreesWithTheValidatorForEveryRegion(R r) {
        Map row = su.queryForMap("SELECT phone_country_code, phone_nsn_length FROM regions WHERE code = ?", r.code());
        String e164 = PhoneNumbers.e164OrNull(r.localPhone(), r.code());
        assertThat(e164).isEqualTo(r.e164());
        assertThat(e164).startsWith((String) row.get("phone_country_code"));
        Integer[] lengths;
        try {
            lengths = (Integer[]) ((java.sql.Array) row.get("phone_nsn_length")).getArray();
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
        assertThat(List.of(lengths)).contains(e164.length() - ((String) row.get("phone_country_code")).length());
    }

    // ---- AC3: KSA -> SA ------------------------------------------------------------------------------------------

    @Test
    void aTenantWrittenAsKsaReadsSaAndTheBackfillMovesOldRows() throws Exception {
        // an old writer still sends the old value: the insert trigger maps it
        UUID viaTrigger = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, slug, name, region, status) VALUES (?,?,?,'KSA','active')", viaTrigger,
                "k" + viaTrigger.toString().replace("-", "").substring(0, 12), "Old writer");
        assertThat(su.queryForMap("SELECT region, region_code FROM tenants WHERE id = ?", viaTrigger))
                .containsEntry("region", "SA").containsEntry("region_code", "SA");

        // a row that predates the migration (triggers off): old value, no region_code. The migration's own backfill statements fix it.
        UUID old = UUID.randomUUID();
        try (java.sql.Connection c = superuserConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("INSERT INTO tenants (id, slug, name, region, status, timezone) VALUES ('" + old + "', 'o" + old.toString().replace("-", "").substring(0, 12)
                    + "', 'Pre-migration', 'KSA', 'active', 'UTC')");
        }
        assertThat(su.queryForObject("SELECT region_code FROM tenants WHERE id = ?", String.class, old)).isNull();
        String migration = java.nio.file.Files.readString(java.nio.file.Path.of("../db/migrations/V1991__tenants_region_code_expand.sql"));
        for (String stmt : migration.split(";")) {
            String s = stmt.strip();
            if (s.contains("UPDATE tenants SET region_code = CASE") || s.contains("UPDATE tenants SET region = 'SA'")) {
                su.execute(s.lines().filter(l -> !l.startsWith("--")).reduce("", (a, b) -> a + "\n" + b));
            }
        }
        assertThat(su.queryForMap("SELECT region, region_code FROM tenants WHERE id = ?", old))
                .containsEntry("region", "SA").containsEntry("region_code", "SA");
        assertThat(su.queryForObject("SELECT count(*) FROM tenants WHERE region = 'KSA' OR region_code = 'KSA'", Integer.class)).isZero();
        assertThat(su.queryForObject("SELECT count(*) FROM tenants WHERE region_code IS NULL", Integer.class)).isZero();
    }

    // ---- AC4: immutability --------------------------------------------------------------------------------------

    @Test
    void theDatabaseRefusesToChangeARegion() {
        SeededTenant t = seedTenant(); // 'active': a live tenant
        assertThatThrownBy(() -> su.update("UPDATE tenants SET region_code = 'AE' WHERE id = ?", t.id()))
                .hasMessageContaining("REGION_IMMUTABLE");
        assertThatThrownBy(() -> su.update("UPDATE tenants SET region = 'AE' WHERE id = ?", t.id()))
                .hasMessageContaining("REGION_IMMUTABLE");
        assertThat(su.queryForObject("SELECT region_code FROM tenants WHERE id = ?", String.class, t.id())).isEqualTo("IN");
    }

    @Test
    void theApiAnswers409RegionImmutableAndTheSameRegionIsANoOp() {
        SeededTenant t = tenantIn("AE");
        String token = platformToken();
        ResponseEntity<Map> refused = exchange("/v1/platform/tenants/" + t.id() + "/region", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("regionCode", "SA")), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code")).isEqualTo("REGION_IMMUTABLE");
        assertThat((String) refused.getBody().get("type")).endsWith("region-immutable");
        assertThat(su.queryForObject("SELECT region_code FROM tenants WHERE id = ?", String.class, t.id())).isEqualTo("AE");

        ResponseEntity<Map> same = exchange("/v1/platform/tenants/" + t.id() + "/region", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("regionCode", "AE")), Map.class);
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(same.getBody().get("regionCode")).isEqualTo("AE");

        assertThat(exchange("/v1/platform/tenants/" + t.id() + "/region", HttpMethod.PATCH, authedJsonBody(token, Map.of("regionCode", "KSA")),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/platform/tenants/" + UUID.randomUUID() + "/region", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("regionCode", "AE")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aClinicUserCannotCallTheRegionEndpointAndABillingOperatorCannotEither() {
        SeededTenant t = tenantIn("AE");
        String staff = doctorToken(t, "nope");
        assertThat(exchange("/v1/platform/tenants/" + t.id() + "/region", HttpMethod.PATCH, authedJsonBody(staff, Map.of("regionCode", "AE")),
                Map.class).getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        String billing = platformLoginAndGetAccessToken(seedOperator("billing-ops061@nabd.health", "billing", false));
        assertThat(exchange("/v1/platform/tenants/" + t.id() + "/region", HttpMethod.PATCH, authedJsonBody(billing, Map.of("regionCode", "AE")),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- AC5: timezone NOT NULL, defaulted from the region ------------------------------------------------------

    @ParameterizedTest
    @MethodSource("regions")
    void timezoneDefaultsFromTheRegionAndCanNeverBeNull(R r) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, slug, name, region_code, status) VALUES (?,?,?,?,'active')", id,
                "z" + id.toString().replace("-", "").substring(0, 12), "Zone default", r.code());
        assertThat(su.queryForObject("SELECT timezone FROM tenants WHERE id = ?", String.class, id)).isEqualTo(r.zone());
        assertThat(su.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'tenants' AND column_name = 'timezone'",
                String.class)).isEqualTo("NO");
        assertThatThrownBy(() -> su.update("UPDATE tenants SET timezone = NULL WHERE id = ?", id)).hasMessageContaining("null value");
        UUID explicit = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, slug, name, region_code, status, timezone) VALUES (?,?,?,?,'active','UTC')", explicit,
                "e" + explicit.toString().replace("-", "").substring(0, 12), "Explicit zone", r.code());
        assertThat(su.queryForObject("SELECT timezone FROM tenants WHERE id = ?", String.class, explicit)).isEqualTo("UTC");
    }

    // ---- provisioning: region chosen there, pinned at go-live, cell event ------------------------------------------

    @Test
    void provisioningAnAeClinicPinsTheRegionAndPublishesTheCellEvent() throws Exception {
        String token = platformToken();
        String slug = "ae-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> created = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(token, Map.of(
                "tenantSlug", slug, "tenantName", "Dubai Clinic", "region", "AE", "ownerEmail", "owner-" + slug + "@nabd.health",
                "ownerName", "Test Owner", "ownerMobile", "050 123 4567", "brandName", "Brand " + slug, "path", "self_serve")), Map.class);
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = null;
        for (int i = 0; i < 6; i++) {
            body = exchange("/v1/platform/provisioning-jobs/" + created.getBody().get("id") + "/advance", HttpMethod.POST, authed(token), Map.class).getBody();
        }
        assertThat(body.get("status")).isEqualTo("done");
        UUID tenantId = UUID.fromString((String) body.get("createdTenantId"));
        assertThat(su.queryForMap("SELECT region_code, region, timezone FROM tenants WHERE id = ?", tenantId))
                .containsEntry("region_code", "AE").containsEntry("region", "AE").containsEntry("timezone", "Asia/Dubai");

        String json = su.queryForObject("SELECT envelope::text FROM outbox_cloudevents WHERE tenant_id = ? "
                + "AND envelope ->> 'type' = 'organization.tenant.region_pinned.v1'", String.class, tenantId);
        Map envelope = objectMapper.readValue(json, Map.class);
        assertThat(envelope.get("type")).isEqualTo("organization.tenant.region_pinned.v1");
        assertThat(envelope.get("region")).isEqualTo("ae");
        Map data = (Map) envelope.get("data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("tenantId", "regionCode", "cell", "version");
        assertThat(data).containsEntry("regionCode", "AE").containsEntry("cell", "ae").containsEntry("tenantId", tenantId.toString());
    }
}
