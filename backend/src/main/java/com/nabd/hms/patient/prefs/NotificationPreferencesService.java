package com.nabd.hms.patient.prefs;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.patient.api.CareRecipients;
import com.nabd.hms.patient.api.NotificationPreferences;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.patient.prefs.NotificationPreferencesController.CaregiverPref;
import com.nabd.hms.patient.prefs.NotificationPreferencesController.Categories;
import com.nabd.hms.patient.prefs.NotificationPreferencesController.EligibleCaregiver;
import com.nabd.hms.patient.prefs.NotificationPreferencesController.PrefsRequest;
import com.nabd.hms.patient.prefs.NotificationPreferencesController.PrefsView;
import com.nabd.hms.patient.prefs.NotificationPreferencesController.QuietHours;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Time;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * COM-025: patient_notification_preferences. The desk reads and replaces them (PUT is idempotent: an unchanged body writes
 * nothing), the patient changes the language from the WhatsApp Settings menu, and the send path reads them live through
 * {@link NotificationPreferences}. A caregiver is accepted only while on the patient's logistics list (guardian or live proxy
 * with their own messaging consent, IDC-014), otherwise 422 CAREGIVER_NOT_AUTHORISED; reads drop one who lost it since.
 * Every change is audited (no names or numbers) and publishes patient.notification_preferences.changed.v1.
 */
@Service
class NotificationPreferencesService implements NotificationPreferences {

    private final JdbcTemplate jdbc;
    private final CareRecipients care;
    private final PatientRefs refs;
    private final TenantContext tenantContext;
    private final EventPublisher events;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final ObjectMapper json;

    NotificationPreferencesService(JdbcTemplate jdbc, CareRecipients care, PatientRefs refs, TenantContext tenantContext, EventPublisher events,
                                   AuditService audit, ActorDirectory actors, ObjectMapper json) {
        this.jdbc = jdbc;
        this.care = care;
        this.refs = refs;
        this.tenantContext = tenantContext;
        this.events = events;
        this.audit = audit;
        this.actors = actors;
        this.json = json;
    }

    private record Stored(String language, String channel, Map<String, Boolean> categories, LocalTime quietStart, LocalTime quietEnd,
                          List<Caregiver> caregivers, long version) {
        static final Stored DEFAULTS = new Stored(null, "whatsapp", Map.of(), null, null, List.of(), 0);
    }

    // ---- the send path (NotificationPreferences) ---------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Prefs of(UUID patientId) {
        Stored s = stored(patientId);
        Set<UUID> allowed = authorised(patientId).keySet();
        return new Prefs(s.language(), s.channel(), s.categories(), s.quietStart(), s.quietEnd(),
                s.caregivers().stream().filter(c -> allowed.contains(c.patientId())).toList(), s.version());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> copiesFor(UUID subject, String category) {
        List<UUID> chosen = of(subject).caregivers().stream().filter(c -> c.categories().contains(category)).map(Caregiver::patientId).toList();
        if (chosen.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> digits = new HashMap<>();
        jdbc.query("SELECT id, phone_digits FROM patients WHERE id = ANY (?::uuid[])",
                rs -> { digits.put(rs.getObject(1, UUID.class), rs.getString(2)); },
                (Object) chosen.stream().map(UUID::toString).toArray(String[]::new));
        String own = jdbc.queryForList("SELECT phone_digits FROM patients WHERE id = ?", String.class, subject).stream().findFirst().orElse(null);
        return GuardianReconciliation.onePerPhone(own, chosen, digits);
    }

    // ---- the desk -----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    PrefsView view(UUID patientId) {
        requirePatient(patientId);
        return view(patientId, of(patientId));
    }

    @Transactional
    PrefsView save(UUID staffId, String ip, UUID patientId, PrefsRequest req) {
        requirePatient(patientId);
        UUID tenant = tenantContext.current();
        Map<UUID, CareRecipients.Recipient> allowed = authorised(patientId);
        Map<UUID, Set<String>> chosen = new LinkedHashMap<>();
        for (CaregiverPref c : req.caregivers() == null ? List.<CaregiverPref>of() : req.caregivers()) {
            if (!allowed.containsKey(c.personRef())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "caregiver-not-authorised", "Caregiver not authorised",
                        "Only a guardian or a family member the patient linked, with their own messaging consent and number, can receive the patient's messages.",
                        Map.of("code", "CAREGIVER_NOT_AUTHORISED"));
            }
            chosen.computeIfAbsent(c.personRef(), k -> new TreeSet<>()).addAll(c.categories());
        }
        Categories cat = req.categories() == null ? new Categories(null, null, null, null) : req.categories();
        Map<String, Boolean> categories = new LinkedHashMap<>();
        categories.put(CARE_REMINDERS, !Boolean.FALSE.equals(cat.careReminders()));
        categories.put(RECALLS, !Boolean.FALSE.equals(cat.recalls()));
        categories.put(FEEDBACK, !Boolean.FALSE.equals(cat.feedback()));
        categories.put(REVIEWS, !Boolean.FALSE.equals(cat.reviews()));
        LocalTime qs = req.quietHours() == null ? null : LocalTime.parse(req.quietHours().start());
        LocalTime qe = req.quietHours() == null ? null : LocalTime.parse(req.quietHours().end());
        List<Caregiver> caregivers = chosen.entrySet().stream().filter(e -> !e.getValue().isEmpty()).map(e -> new Caregiver(e.getKey(), e.getValue())).toList();

        Prefs before = of(patientId);
        Stored now = new Stored(req.language(), req.channel(), categories, qs, qe, caregivers, before.version());
        if (same(before, now)) {
            return view(patientId, before); // PUT is idempotent: nothing changed, nothing written
        }
        ActorDirectory.Actor actor = actors.findActor(tenant, staffId).orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "staff-not-found", "Forbidden", "Forbidden"));
        jdbc.update("""
                INSERT INTO patient_notification_preferences (tenant_id, patient_id, language, channel, categories, quiet_start, quiet_end, caregiver_recipients, updated_by)
                VALUES (?,?,?,?,?::jsonb,?,?,?::jsonb,?)
                ON CONFLICT (tenant_id, patient_id) DO UPDATE SET language = EXCLUDED.language, channel = EXCLUDED.channel, categories = EXCLUDED.categories,
                  quiet_start = EXCLUDED.quiet_start, quiet_end = EXCLUDED.quiet_end, caregiver_recipients = EXCLUDED.caregiver_recipients,
                  version = patient_notification_preferences.version + 1, updated_by = EXCLUDED.updated_by, updated_at = now()
                """, tenant, patientId, req.language(), req.channel(), json.writeValueAsString(categories), qs == null ? null : Time.valueOf(qs),
                qe == null ? null : Time.valueOf(qe), json.writeValueAsString(caregiversJson(caregivers)), staffId.toString());
        audit.record(tenant, "staff", staffId, actor.name(), actor.role(), ip, "patient.notification_preferences_changed", "patient", patientId,
                summary(before.language(), before.channel(), before.categories(), before.caregivers().size()), summary(req.language(), req.channel(), categories, caregivers.size()));
        events.publish(tenant, DomainEventType.PATIENT_NOTIFICATION_PREFERENCES_CHANGED, patientId, Map.of("patientId", patientId));
        return view(patientId, of(patientId));
    }

    // ---- the WhatsApp Settings menu (Patient Access Edge) ---------------------------------------------------------------

    /**
     * Sets the language of everyone in the household of a verified phone (the people whose messages arrive on it). No OTP: the
     * language is not PHI (COM-025). Only members whose language actually changes get a new version and an event.
     * @return how many members changed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    int setHouseholdLanguage(String phoneHash, String language, String serviceId) {
        UUID tenant = tenantContext.current();
        List<UUID> members = refs.household(phoneHash).stream().map(m -> refs.patientIdFor(m.patientRef()).orElse(null)).filter(Objects::nonNull).toList();
        if (members.isEmpty()) {
            throw ApiException.notFound("No household for that phone.");
        }
        List<UUID> changed = new ArrayList<>();
        for (UUID p : members) {
            int n = jdbc.update("""
                    INSERT INTO patient_notification_preferences (tenant_id, patient_id, language, updated_by) VALUES (?,?,?,'whatsapp')
                    ON CONFLICT (tenant_id, patient_id) DO UPDATE SET language = EXCLUDED.language, version = patient_notification_preferences.version + 1,
                      updated_by = 'whatsapp', updated_at = now()
                    WHERE patient_notification_preferences.language IS DISTINCT FROM EXCLUDED.language
                    """, tenant, p, language);
            if (n == 1) {
                changed.add(p);
                events.publish(tenant, DomainEventType.PATIENT_NOTIFICATION_PREFERENCES_CHANGED, p, Map.of("patientId", p));
            }
        }
        if (!changed.isEmpty()) {
            audit.record(tenant, "system", null, "patient via edge", serviceId, null, "patient.notification_language_changed_via_edge", "household", null, null,
                    Map.of("language", language, "members", changed.stream().map(refs::refFor).toList()));
        }
        return changed.size();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private void requirePatient(UUID patientId) {
        if (jdbc.queryForList("SELECT 1 FROM patients WHERE id = ?", patientId).isEmpty()) {
            throw ApiException.notFound("The requested resource was not found.");
        }
    }

    /** Logistics recipients other than the patient: who may be a caregiver recipient right now. */
    private Map<UUID, CareRecipients.Recipient> authorised(UUID patientId) {
        Map<UUID, CareRecipients.Recipient> out = new LinkedHashMap<>();
        for (CareRecipients.Recipient r : care.recipients(patientId, CareRecipients.MessageClass.LOGISTICS)) {
            if (!"self".equals(r.relation())) {
                out.put(r.patientId(), r);
            }
        }
        return out;
    }

    private Stored stored(UUID patientId) {
        return jdbc.query("""
                SELECT language, channel, categories::text, quiet_start, quiet_end, caregiver_recipients::text, version
                FROM patient_notification_preferences WHERE patient_id = ?""", rs -> {
            if (!rs.next()) {
                return Stored.DEFAULTS;
            }
            Map<String, Boolean> cats = new LinkedHashMap<>();
            json.readTree(rs.getString(3)).properties().forEach(e -> cats.put(e.getKey(), e.getValue().asBoolean()));
            List<Caregiver> cgs = new ArrayList<>();
            for (JsonNode c : json.readTree(rs.getString(6))) {
                Set<String> cs = new TreeSet<>();
                c.path("categories").forEach(x -> cs.add(x.asString()));
                cgs.add(new Caregiver(UUID.fromString(c.path("personRef").asString()), cs));
            }
            Time qs = rs.getTime(4), qe = rs.getTime(5);
            return new Stored(rs.getString(1), rs.getString(2), cats, qs == null ? null : qs.toLocalTime(), qe == null ? null : qe.toLocalTime(), cgs, rs.getLong(7));
        }, patientId);
    }

    private PrefsView view(UUID patientId, Prefs p) {
        Map<UUID, CareRecipients.Recipient> allowed = authorised(patientId);
        Map<UUID, String> first = new HashMap<>();
        if (!allowed.isEmpty()) {
            jdbc.query("SELECT id, split_part(trim(name), ' ', 1) FROM patients WHERE id = ANY (?::uuid[])",
                    rs -> { first.put(rs.getObject(1, UUID.class), rs.getString(2)); },
                    (Object) allowed.keySet().stream().map(UUID::toString).toArray(String[]::new));
        }
        return new PrefsView(p.language(), p.channel(),
                new Categories(p.wants(CARE_REMINDERS), p.wants(RECALLS), p.wants(FEEDBACK), p.wants(REVIEWS)),
                p.quietStart() == null ? null : new QuietHours(p.quietStart().toString(), p.quietEnd().toString()),
                p.caregivers().stream().map(c -> new CaregiverPref(c.patientId(), List.copyOf(c.categories()))).toList(),
                allowed.values().stream().map(r -> new EligibleCaregiver(r.patientId(), first.get(r.patientId()), r.relation())).toList(),
                p.version());
    }

    private static boolean same(Prefs before, Stored now) {
        Map<String, Boolean> b = new LinkedHashMap<>();
        for (String c : CATEGORIES) {
            b.put(c, before.wants(c));
        }
        return before.version() > 0 && Objects.equals(before.language(), now.language()) && before.channel().equals(now.channel())
                && b.equals(now.categories()) && Objects.equals(before.quietStart(), now.quietStart()) && Objects.equals(before.quietEnd(), now.quietEnd())
                && new LinkedHashSet<>(before.caregivers()).equals(new LinkedHashSet<>(now.caregivers()));
    }

    private static List<Map<String, Object>> caregiversJson(List<Caregiver> caregivers) {
        return caregivers.stream().map(c -> Map.<String, Object>of("personRef", c.patientId().toString(), "categories", List.copyOf(c.categories()))).toList();
    }

    /** Audit body: no names, numbers or caregiver ids, just what kind of messages and how many caregivers. */
    private static Map<String, Object> summary(String language, String channel, Map<String, Boolean> categories, int caregivers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("language", language == null ? "default" : language);
        m.put("channel", channel);
        m.put("categories", categories);
        m.put("caregivers", caregivers);
        return m;
    }
}
