package com.nabd.hms.identity.abdm;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.PhoneNumbers;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.identity.integration.AbdmShareGateway;
import com.nabd.hms.patient.api.PatientDirectory;
import com.nabd.hms.queue.api.SelfCheckIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IDC-018 ABDM Scan &amp; Share: the gateway says a patient shared their ABHA profile at a counter; Core finds the patient by
 * exact ABHA number (or address) or registers a new one (data-processing consent stays pending: no consent row), issues a
 * token through {@link SelfCheckIn} (source abdm_scan_share) and answers ABDM with the token number. A closed clinic, a full
 * session, an unknown counter or an incomplete profile is answered "please see the desk" and creates nothing. One outcome per
 * request id (a repeat gets the stored outcome). The counter code is the doctor's staff id (shortcut: no counter register yet).
 * No @Transactional here: the clinic is resolved first, then each step runs in TenantScope; the ABDM answer goes out after the callback is acknowledged.
 */
@Service
class AbdmShareService {

    private static final Logger log = LoggerFactory.getLogger(AbdmShareService.class);
    static final String SOURCE = "abdm_scan_share";
    private static final String VERIFIED_VIA = "abdm_scan_share";

    record Profile(String abhaNumber, String abhaAddress, String name, String gender, Integer yearOfBirth, Integer monthOfBirth,
                   Integer dayOfBirth, String phoneNumber) {
    }

    /** token null = see the desk; replay = the request id was answered before. */
    record Outcome(Integer token, String reason, boolean replay) {
    }

    private final JdbcTemplate jdbc;
    private final TenantScope scope;
    private final TenantContext tenantContext;
    private final AbhaIdentifiers ids;
    private final PatientDirectory patients;
    private final SelfCheckIn checkIn;
    private final AuditService audit;
    private final ClinicClock clock;
    private final AbdmShareGateway gateway;
    private final ExecutorService replies = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "abdm-share-reply");
        t.setDaemon(true);
        return t;
    });

    AbdmShareService(JdbcTemplate jdbc, TenantScope scope, TenantContext tenantContext, AbhaIdentifiers ids, PatientDirectory patients,
                     SelfCheckIn checkIn, AuditService audit, ClinicClock clock, AbdmShareGateway gateway) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.patients = patients;
        this.checkIn = checkIn;
        this.audit = audit;
        this.clock = clock;
        this.gateway = gateway;
    }

    /** @return empty when no enabled, verified clinic owns the facility id (the callback is acknowledged and dropped) */
    Optional<Outcome> handle(String hipId, String requestId, String counter, Profile profile) {
        UUID tenant = jdbc.queryForObject("SELECT abdm_tenant_for_hfr(?)", UUID.class, hipId);
        if (tenant == null) {
            log.warn("abdm share for an unknown or disabled facility id ignored");
            return Optional.empty();
        }
        Outcome out;
        try {
            out = scope.run(tenant, () -> process(requestId, counter, profile));
        } catch (RuntimeException e) { // identifiers only: the cause may hold profile values
            log.warn("abdm share failed for tenant {} ({}); answered see-desk", tenant, e.getClass().getSimpleName());
            out = scope.run(tenant, () -> record(requestId, null, null, null, "unavailable"));
        }
        if (!out.replay()) {
            Integer token = out.token();
            replies.execute(() -> answer(requestId, token));
        }
        return Optional.of(out);
    }

    private void answer(String requestId, Integer token) {
        try {
            gateway.respond(requestId, token);
        } catch (RuntimeException e) {
            log.warn("abdm share answer not delivered ({})", e.getClass().getSimpleName()); // the desk still sees the token
        }
    }

    private Outcome process(String requestId, String counter, Profile p) {
        Optional<Outcome> seen = stored(requestId);
        if (seen.isPresent()) {
            return seen.get();
        }
        UUID doctor = parseUuid(counter);
        Optional<SelfCheckIn.Refusal> refusal = doctor == null ? Optional.of(SelfCheckIn.Refusal.NO_DOCTOR) : checkIn.refusal(doctor);
        if (refusal.isPresent()) {
            return record(requestId, null, null, null, refusal.get().name().toLowerCase(Locale.ROOT));
        }
        String number = p.abhaNumber() == null ? "" : AbhaIdentifiers.normalise(AbhaIdentifiers.NUMBER, p.abhaNumber());
        if (number.length() != 14) {
            return record(requestId, null, null, null, "incomplete_profile");
        }
        String address = p.abhaAddress() == null || p.abhaAddress().isBlank() ? null : AbhaIdentifiers.normalise(AbhaIdentifiers.ADDRESS, p.abhaAddress());
        Optional<UUID> byNumber = ids.findPatient(AbhaIdentifiers.NUMBER, number);
        UUID patient = byNumber.or(() -> address == null ? Optional.empty() : ids.findPatient(AbhaIdentifiers.ADDRESS, address)).orElse(null);
        if (patient == null) {
            patient = register(p);
            if (patient == null) {
                return record(requestId, null, null, null, "incomplete_profile");
            }
            ids.link(patient, AbhaIdentifiers.NUMBER, number, VERIFIED_VIA);
            if (address != null) { // cannot clash: neither id was known a moment ago (a race fails the request, answered see-desk)
                ids.link(patient, AbhaIdentifiers.ADDRESS, address, VERIFIED_VIA);
            }
        } else if (byNumber.isEmpty()) { // found by address only: the number is new for this patient
            ids.link(patient, AbhaIdentifiers.NUMBER, number, VERIFIED_VIA);
        }
        SelfCheckIn.Token t = checkIn.checkIn(patient, doctor, SOURCE);
        UUID tenant = tenantContext.current();
        audit.record(tenant, "system", null, "ABDM Scan & Share", "system", null, "identity.abdm_scan_share", "queue_entry", t.entryId(), null,
                Map.of("patientId", patient, "tokenNumber", t.tokenNumber()));
        return record(requestId, patient, t.entryId(), t.tokenNumber(), null);
    }

    /** A new patient from the shared profile, or null when name, phone, date of birth or gender is missing or not valid. */
    private UUID register(Profile p) {
        String phone = PhoneNumbers.e164OrNull(p.phoneNumber(), clock.region(tenantContext.current()));
        String gender = gender(p.gender());
        LocalDate dob = dob(p);
        if (p.name() == null || p.name().isBlank() || p.name().length() > 200 || phone == null || gender == null || dob == null) {
            return null;
        }
        return patients.createImported(p.name().strip(), phone, dob, gender, null);
    }

    private static String gender(String g) {
        if (g == null) {
            return null;
        }
        return switch (g.strip().toUpperCase(Locale.ROOT)) {
            case "M", "MALE" -> "male";
            case "F", "FEMALE" -> "female";
            case "O", "OTHER", "T" -> "other";
            default -> null;
        };
    }

    private LocalDate dob(Profile p) {
        if (p.yearOfBirth() == null) {
            return null;
        }
        try {
            LocalDate d = LocalDate.of(p.yearOfBirth(), p.monthOfBirth() == null ? 1 : p.monthOfBirth(), p.dayOfBirth() == null ? 1 : p.dayOfBirth());
            return d.getYear() < 1900 || d.isAfter(clock.today(tenantContext.current())) ? null : d;
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    private static UUID parseUuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Optional<Outcome> stored(String requestId) {
        return jdbc.query("SELECT token_number, reason FROM abdm_share_requests WHERE tenant_id = app_tenant_id() AND request_id = ?",
                (rs, i) -> new Outcome(rs.getObject(1, Integer.class), rs.getString(2), true), requestId).stream().findFirst();
    }

    private Outcome record(String requestId, UUID patient, UUID entry, Integer token, String reason) {
        try {
            jdbc.update("INSERT INTO abdm_share_requests (tenant_id, request_id, outcome, reason, patient_id, queue_entry_id, token_number) VALUES (app_tenant_id(),?,?,?,?,?,?)",
                    requestId, token == null ? "see_desk" : "token_issued", reason, patient, entry, token);
        } catch (DuplicateKeyException race) { // the same request id answered at once by another replica: its outcome stands
            return stored(requestId).orElseThrow();
        }
        return new Outcome(token, reason, false);
    }
}
