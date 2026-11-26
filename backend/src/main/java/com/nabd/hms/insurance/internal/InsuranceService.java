package com.nabd.hms.insurance.internal;

import com.nabd.hms.billing.api.PayerLedger;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.insurance.internal.InsuranceRepository.CoverageRow;
import com.nabd.hms.insurance.internal.InsuranceRepository.PayerRow;
import com.nabd.hms.insurance.internal.InsuranceRepository.PlanRow;
import com.nabd.hms.insurance.internal.InsuranceRepository.PolicyRow;
import com.nabd.hms.insurance.web.InsuranceDtos.Coverage;
import com.nabd.hms.insurance.web.InsuranceDtos.CoveragePolicy;
import com.nabd.hms.insurance.web.InsuranceDtos.CoverageRequest;
import com.nabd.hms.insurance.web.InsuranceDtos.Payer;
import com.nabd.hms.insurance.web.InsuranceDtos.PayerList;
import com.nabd.hms.insurance.web.InsuranceDtos.PayerPaymentRequest;
import com.nabd.hms.insurance.web.InsuranceDtos.PayerPosition;
import com.nabd.hms.insurance.web.InsuranceDtos.PayerRequest;
import com.nabd.hms.insurance.web.InsuranceDtos.Plan;
import com.nabd.hms.insurance.web.InsuranceDtos.PlanRequest;
import com.nabd.hms.insurance.web.InsuranceDtos.Policy;
import com.nabd.hms.insurance.web.InsuranceDtos.PolicyList;
import com.nabd.hms.insurance.web.InsuranceDtos.PolicyRequest;
import com.nabd.hms.patient.api.PatientSummaries;
import com.nabd.hms.queue.api.VisitQueries;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * FIN-036: payers and plans (the global master plus the clinic's own), a patient's policies and the coverage decision on a visit.
 * The tenant is the one bound to the transaction. Member id and policy number are encrypted at field level (FieldCipher) and
 * never audited or published. The payer / patient split and the payer's payments are billing's: reached through billing.api only.
 */
@Service
public class InsuranceService {

    /** Per-market payer identifiers the claims adapters use (the same list as the insurance_payers.identifiers CHECK). */
    static final Set<String> IDENTIFIER_KEYS = Set.of("irdai", "dha_payer_id", "nphies_id", "shafafiya_id");

    private final InsuranceRepository repo;
    private final TenantContext tenantContext;
    private final FieldCipher cipher;
    private final PatientSummaries patients;
    private final VisitQueries visits;
    private final PayerLedger ledger;
    private final ClinicClock clock;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final EventPublisher events;

    InsuranceService(InsuranceRepository repo, TenantContext tenantContext, FieldCipher cipher, PatientSummaries patients, VisitQueries visits,
                     PayerLedger ledger, ClinicClock clock, AuditService audit, ActorDirectory actors, EventPublisher events) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.cipher = cipher;
        this.patients = patients;
        this.visits = visits;
        this.ledger = ledger;
        this.clock = clock;
        this.audit = audit;
        this.actors = actors;
        this.events = events;
    }

    // ---------------------------------------------------------------- payers and plans

    @Transactional
    public PayerList payers(String jurisdiction, String q) {
        UUID tenantId = tenantContext.current();
        String j = jurisdiction == null || jurisdiction.isBlank() ? null : jurisdiction.trim().toUpperCase();
        if (j != null && !j.matches("[A-Z]{2}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-jurisdiction", "Unknown market", "The market must be a two-letter region code, for example IN or AE.");
        }
        List<PayerRow> rows = repo.payers(tenantId, j, q == null || q.isBlank() ? null : q.trim());
        Map<UUID, List<Plan>> plans = new LinkedHashMap<>();
        for (PlanRow p : repo.plans(tenantId, rows.stream().map(PayerRow::id).toList())) {
            plans.computeIfAbsent(p.payerId(), k -> new java.util.ArrayList<>()).add(plan(p));
        }
        return new PayerList(rows.stream().map(r -> payer(r, plans.getOrDefault(r.id(), List.of()))).toList());
    }

    /** A payer of this clinic's own, in the clinic's market. 409 duplicate-payer on a name the clinic already has. */
    @Transactional
    public Payer createPayer(UUID staffId, String ip, PayerRequest req) {
        UUID tenantId = tenantContext.current();
        Map<String, String> ids = new TreeMap<>();
        if (req.identifiers() != null) {
            req.identifiers().forEach((k, v) -> {
                if (!IDENTIFIER_KEYS.contains(k)) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-identifier", "Unknown identifier",
                            "\"" + k + "\" is not a payer identifier Nabd knows. Use one of: irdai, dha_payer_id, nphies_id, shafafiya_id.",
                            Map.of("code", "UNKNOWN_IDENTIFIER"));
                }
                ids.put(k, v.trim());
            });
        }
        UUID id;
        try {
            id = repo.insertPayer(tenantId, req.name().trim(), req.kind(), clock.region(tenantId), ids);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "duplicate-payer", "Insurer already listed",
                    "Your clinic already has an insurer with this name.", Map.of("code", "DUPLICATE_PAYER"));
        }
        record(tenantId, staffId, ip, "insurance.payer_created", "insurance_payer", id, null,
                Map.of("name", req.name().trim(), "kind", req.kind(), "identifiers", ids));
        return payer(repo.findPayer(tenantId, id).orElseThrow(), List.of());
    }

    /** A plan of this clinic's own on a payer it can see (its own or a master one). 404 unknown payer, 409 duplicate-plan. */
    @Transactional
    public Plan createPlan(UUID staffId, String ip, UUID payerId, PlanRequest req) {
        UUID tenantId = tenantContext.current();
        repo.findPayer(tenantId, payerId).filter(PayerRow::active).orElseThrow(() -> ApiException.notFound("Insurer not found."));
        UUID id;
        try {
            id = repo.insertPlan(tenantId, payerId, req.name().trim(), req.copayPercent());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "duplicate-plan", "Plan already listed",
                    "This insurer already has a plan with this name.", Map.of("code", "DUPLICATE_PLAN"));
        }
        record(tenantId, staffId, ip, "insurance.plan_created", "insurance_plan", id, null,
                Map.of("payerId", payerId, "name", req.name().trim(), "copayPercent", req.copayPercent()));
        return plan(repo.findPlan(tenantId, id).orElseThrow());
    }

    // ---------------------------------------------------------------- patient policies

    @Transactional
    public PolicyList policies(UUID patientId) {
        UUID tenantId = tenantContext.current();
        patients.find(patientId).orElseThrow(() -> ApiException.notFound("Patient not found."));
        LocalDate today = clock.clinicDate(tenantId);
        return new PolicyList(repo.policies(tenantId, patientId).stream().map(p -> policy(tenantId, p, today)).toList());
    }

    /** 404 unknown patient; 422 PAYER_UNKNOWN / PLAN_UNKNOWN / INVALID_VALIDITY. */
    @Transactional
    public Policy addPolicy(UUID staffId, String ip, UUID patientId, PolicyRequest req) {
        UUID tenantId = tenantContext.current();
        patients.find(patientId).orElseThrow(() -> ApiException.notFound("Patient not found."));
        repo.findPayer(tenantId, req.payerId()).filter(PayerRow::active).orElseThrow(() -> unprocessable("payer-unknown", "PAYER_UNKNOWN",
                "Unknown insurer", "That insurer is not on your clinic's list. Pick one from the list or add it first."));
        if (req.planId() != null) {
            repo.findPlan(tenantId, req.planId()).filter(p -> p.active() && p.payerId().equals(req.payerId())).orElseThrow(() -> unprocessable(
                    "plan-unknown", "PLAN_UNKNOWN", "Unknown plan", "That plan does not belong to the insurer you picked."));
        }
        if (req.validTo().isBefore(req.validFrom())) {
            throw unprocessable("invalid-validity", "INVALID_VALIDITY", "Check the dates", "The policy's last day cannot be before its first day.");
        }
        UUID id = UUID.randomUUID(); // known before the insert: the ciphertexts are bound to the row id
        repo.insertPolicy(tenantId, id, patientId, req.payerId(), req.planId(), seal(tenantId, "member_id_enc", id, req.memberId().trim()),
                seal(tenantId, "policy_number_enc", id, req.policyNumber().trim()), req.validFrom(), req.validTo(), req.relationship(),
                req.cardDocumentIds() == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(req.cardDocumentIds())), staffId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("patientId", patientId);
        after.put("payerId", req.payerId());
        after.put("planId", req.planId());
        after.put("validFrom", req.validFrom().toString());
        after.put("validTo", req.validTo().toString());
        after.put("relationship", req.relationship());
        record(tenantId, staffId, ip, "insurance.policy_added", "patient_policy", id, null, after); // never the member id or policy number
        return policy(tenantId, repo.findPolicy(tenantId, id).orElseThrow(), clock.clinicDate(tenantId));
    }

    // ---------------------------------------------------------------- coverage of a visit

    @Transactional
    public Coverage coverage(UUID visitId) {
        UUID tenantId = tenantContext.current();
        VisitQueries.Visit visit = visits.visit(tenantId, visitId).orElseThrow(() -> ApiException.notFound("Visit not found."));
        return coverage(tenantId, visitId, visit);
    }

    /**
     * Marks the visit insured (under one of the patient's policies) or self-pay. 422 POLICY_REQUIRED / POLICY_UNKNOWN / POLICY_ENDED /
     * POLICY_EXPIRED / POLICY_NOT_STARTED (against the visit's own clinic date) / PLAN_REQUIRED (no plan = no co-pay rule).
     * 409 VISIT_BILLED once the visit has an invoice: the bill was split (or not) under the decision that stood then, and money
     * already taken is never re-split. Sending the decision that already stands is always accepted and changes nothing.
     */
    @Transactional
    public Coverage setCoverage(UUID staffId, String ip, UUID visitId, CoverageRequest req) {
        UUID tenantId = tenantContext.current();
        VisitQueries.Visit visit = visits.visit(tenantId, visitId).orElseThrow(() -> ApiException.notFound("Visit not found."));
        boolean insured = "insured".equals(req.mode());
        String ref = req.eligibilityRef() == null || req.eligibilityRef().isBlank() ? null : req.eligibilityRef().trim();
        CoverageRow current = repo.findCoverage(tenantId, visitId).orElse(null);
        String currentMode = current == null ? "self_pay" : current.mode();
        UUID policyId = insured ? req.policyId() : null;
        if (currentMode.equals(req.mode()) && Objects.equals(current == null ? null : current.policyId(), policyId)
                && Objects.equals(current == null ? null : current.eligibilityRef(), ref)) {
            return coverage(tenantId, visitId, visit);
        }
        if (ledger.visitBilled(tenantId, visitId)) {
            throw new ApiException(HttpStatus.CONFLICT, "visit-billed", "Visit already billed",
                    "This visit already has a bill, so its insurance cover can no longer be changed.", Map.of("code", "VISIT_BILLED"));
        }
        java.math.BigDecimal copay = null;
        if (insured) {
            if (policyId == null) {
                throw unprocessable("policy-required", "POLICY_REQUIRED", "Pick a policy", "Pick the policy this visit is covered by.");
            }
            PolicyRow p = repo.findPolicy(tenantId, policyId).filter(x -> x.patientId().equals(visit.patientId())).orElseThrow(() -> unprocessable(
                    "policy-unknown", "POLICY_UNKNOWN", "Unknown policy", "That policy is not on this patient's file."));
            LocalDate day = visits.visitDate(tenantId, visitId).orElseThrow(() -> ApiException.notFound("Visit not found."));
            String warning = warning(p, day);
            if (warning != null) throw unprocessable(warning.toLowerCase().replace('_', '-'), warning, title(warning), sentence(warning, p, day));
            copay = p.copayPercent();
        }
        repo.upsertCoverage(tenantId, visitId, req.mode(), policyId, ref, copay, staffId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("mode", currentMode);
        before.put("policyId", current == null ? null : current.policyId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("mode", req.mode());
        after.put("policyId", policyId);
        after.put("copayPercent", copay);
        record(tenantId, staffId, ip, "insurance.coverage_set", "visit", visitId, before, after);
        events.publish(tenantId, DomainEventType.INSURANCE_COVERAGE_SET, visitId, Map.of("visitId", visitId, "mode", req.mode()));
        return coverage(tenantId, visitId, visit);
    }

    /** Why a policy cannot cover a visit on {@code day}; null when it can. */
    static String warning(PolicyRow p, LocalDate day) {
        if (!"active".equals(p.status())) return "POLICY_ENDED";
        if (day.isAfter(p.validTo())) return "POLICY_EXPIRED";
        if (day.isBefore(p.validFrom())) return "POLICY_NOT_STARTED";
        return p.copayPercent() == null ? "PLAN_REQUIRED" : null;
    }

    private static String title(String warning) {
        return switch (warning) {
            case "POLICY_EXPIRED" -> "Policy expired";
            case "POLICY_NOT_STARTED" -> "Policy not started";
            case "POLICY_ENDED" -> "Policy ended";
            default -> "Plan needed";
        };
    }

    private static String sentence(String warning, PolicyRow p, LocalDate day) {
        return switch (warning) {
            case "POLICY_EXPIRED" -> "This policy expired on " + p.validTo() + ", before the visit on " + day + ". Add the renewed policy, or bill the visit as self-pay.";
            case "POLICY_NOT_STARTED" -> "This policy starts on " + p.validFrom() + ", after the visit on " + day + ". Bill the visit as self-pay, or pick another policy.";
            case "POLICY_ENDED" -> "This policy has been ended. Pick another policy, or bill the visit as self-pay.";
            default -> "This policy names no plan, so the patient's co-pay is not known. Add the policy again with its plan.";
        };
    }

    @Transactional
    private Coverage coverage(UUID tenantId, UUID visitId, VisitQueries.Visit visit) {
        LocalDate day = visits.visitDate(tenantId, visitId).orElseThrow(() -> ApiException.notFound("Visit not found."));
        CoverageRow c = repo.findCoverage(tenantId, visitId).orElse(null);
        List<CoveragePolicy> options = repo.policies(tenantId, visit.patientId()).stream().map(p -> new CoveragePolicy(p.id(), p.payerName(),
                p.planName(), p.copayPercent(), mask(open(tenantId, "member_id_enc", p.id(), p.memberIdEnc())), p.validFrom(), p.validTo(),
                warning(p, day))).toList();
        return new Coverage(visitId, day, c == null ? "self_pay" : c.mode(), c == null ? null : c.policyId(), c == null ? null : c.payerName(),
                c == null ? null : c.eligibilityRef(), c == null ? null : c.copayPercent(), ledger.visitBilled(tenantId, visitId), options);
    }

    // ---------------------------------------------------------------- what the payer paid

    /** The payer's payment against its share of an invoice, recorded by billing (method 'insurance'). See {@link PayerLedger}. */
    @Transactional
    public PayerPosition recordPayerPayment(UUID staffId, String ip, UUID invoiceId, PayerPaymentRequest req) {
        PayerLedger.Position p = ledger.recordPayerPayment(tenantContext.current(), staffId, ip, invoiceId, req.amount(), req.reference());
        return new PayerPosition(p.invoiceId(), p.payerId(), p.currency(), p.payerShare(), p.payerPaid(), p.pendingDecision(), p.patientShare());
    }

    // ---------------------------------------------------------------- helpers

    private byte[] seal(UUID tenantId, String column, UUID id, String value) {
        return cipher.encrypt(value.getBytes(StandardCharsets.UTF_8), FieldCipher.Aad.of(tenantId, "patient_policies", column, id));
    }

    private String open(UUID tenantId, String column, UUID id, byte[] blob) {
        return new String(cipher.decrypt(blob, FieldCipher.Aad.of(tenantId, "patient_policies", column, id)), StandardCharsets.UTF_8);
    }

    /** All but the last four characters hidden. */
    static String mask(String v) {
        return v.length() <= 4 ? "••••" : "•".repeat(v.length() - 4) + v.substring(v.length() - 4);
    }

    private Policy policy(UUID tenantId, PolicyRow p, LocalDate today) {
        return new Policy(p.id(), p.patientId(), p.payerId(), p.payerName(), p.planId(), p.planName(), p.copayPercent(),
                open(tenantId, "member_id_enc", p.id(), p.memberIdEnc()), open(tenantId, "policy_number_enc", p.id(), p.policyNumberEnc()),
                p.validFrom(), p.validTo(), p.relationship(), p.cardDocumentIds(), p.status(), today.isAfter(p.validTo()), p.createdAt());
    }

    private static Payer payer(PayerRow r, List<Plan> plans) {
        return new Payer(r.id(), r.name(), r.kind(), r.jurisdiction(), r.identifiers(), r.tenantId() == null, plans);
    }

    private static Plan plan(PlanRow p) {
        return new Plan(p.id(), p.name(), p.copayPercent(), p.tenantId() == null);
    }

    private static ApiException unprocessable(String slug, String code, String title, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, slug, title, detail, Map.of("code", code));
    }

    private void record(UUID tenantId, UUID staffId, String ip, String action, String entity, UUID id, Object before, Object after) {
        String[] actor = actors.findActor(tenantId, staffId).map(a -> new String[]{a.name(), a.role()}).orElse(new String[]{"Unknown", "Unknown"});
        audit.record(tenantId, "staff", staffId, actor[0], actor[1], ip, action, entity, id, before, after);
    }
}
