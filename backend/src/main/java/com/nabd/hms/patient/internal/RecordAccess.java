package com.nabd.hms.patient.internal;

import com.nabd.hms.patient.api.ConsentGateSql;
import com.nabd.hms.patient.api.PatientRefs;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * COM-050: the one decision "whose records may this verified phone open". In scope: the people of the phone's household as
 * PatientRefs.household defines it (the patients registered on the number and the minors they are guardian of; a dependant who
 * turned 18 or lost the guardian link is not in it), plus adults who gave a patient on this number an accepted proxy that includes
 * records (BOOK_AND_RECORDS or FULL: the rule of ProxyAccess.requireRecords). A pending, BOOK_ONLY or revoked proxy is out, and so is
 * anyone whose data may not be processed (IDC-006). Nothing is cached: every list, every link and every link open asks again.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class RecordAccess {

    /** relation: self (registered on the phone), dependent (a minor of the household) or proxy. */
    public record Subject(UUID patientId, String patientRef, String firstName, String relation) {
    }

    private final JdbcTemplate jdbc;
    private final PatientRefs refs;

    RecordAccess(JdbcTemplate jdbc, PatientRefs refs) {
        this.jdbc = jdbc;
        this.refs = refs;
    }

    /** Everyone whose records the phone may open, the people registered on it first. */
    public List<Subject> subjects(String phoneHash) {
        List<Subject> out = new ArrayList<>();
        for (PatientRefs.HouseholdMember m : refs.household(phoneHash)) {
            refs.patientIdFor(m.patientRef()).filter(this::processable)
                    .ifPresent(id -> out.add(new Subject(id, m.patientRef(), m.firstName(), "dependent".equals(m.relation()) ? "dependent" : "self")));
        }
        out.sort(java.util.Comparator.comparing(s -> !"self".equals(s.relation()))); // stable: household order within each group
        for (UUID adult : out.stream().filter(s -> "self".equals(s.relation())).map(Subject::patientId).toList()) {
            for (Subject p : jdbc.query("""
                    SELECT s.id, split_part(trim(s.name), ' ', 1) FROM patient_proxies x
                    JOIN patients s ON s.tenant_id = x.tenant_id AND s.id = x.subject_patient_id AND s.status = 'active'
                    WHERE x.tenant_id = app_tenant_id() AND x.proxy_patient_id = ? AND x.status = 'active' AND x.scope IN ('BOOK_AND_RECORDS', 'FULL')
                    ORDER BY x.invited_at, x.id""", (rs, i) -> new Subject(rs.getObject(1, UUID.class), null, rs.getString(2), "proxy"), adult)) {
                if (out.stream().noneMatch(s -> s.patientId().equals(p.patientId())) && processable(p.patientId())) {
                    out.add(new Subject(p.patientId(), refs.refFor(p.patientId()), p.firstName(), "proxy"));
                }
            }
        }
        return out;
    }

    /**
     * The patient on this phone a link to the subject's record is addressed to (the one-time code goes to that patient's number on
     * file, which is this phone); empty when the phone may not see the subject's records.
     */
    public Optional<UUID> viewer(String phoneHash, UUID subject) {
        if (subjects(phoneHash).stream().noneMatch(s -> s.patientId().equals(subject))) {
            return Optional.empty();
        }
        List<UUID> onPhone = jdbc.query("""
                SELECT id FROM patients WHERE tenant_id = app_tenant_id() AND status = 'active'
                  AND encode(sha256(decode(phone_digits, 'escape')), 'hex') = ? ORDER BY dob, id""", (rs, i) -> rs.getObject(1, UUID.class), phoneHash);
        return onPhone.contains(subject) ? Optional.of(subject) : onPhone.stream().findFirst();
    }

    /** The serve-time check of a chat-issued link: may the recipient's own number still open this patient's records? */
    public boolean mayView(UUID recipient, UUID subject) {
        return jdbc.query("""
                SELECT encode(sha256(decode(phone_digits, 'escape')), 'hex') FROM patients
                WHERE tenant_id = app_tenant_id() AND id = ? AND status = 'active' AND phone_digits IS NOT NULL""", (rs, i) -> rs.getString(1), recipient)
                .stream().findFirst().flatMap(hash -> viewer(hash, subject)).isPresent();
    }

    private boolean processable(UUID patient) {
        return !jdbc.queryForList("SELECT 1 FROM patients p WHERE p.id = ? AND p.tenant_id = app_tenant_id() AND p.status = 'active' "
                + ConsentGateSql.gate("p.id", "p.tenant_id"), patient).isEmpty();
    }
}
